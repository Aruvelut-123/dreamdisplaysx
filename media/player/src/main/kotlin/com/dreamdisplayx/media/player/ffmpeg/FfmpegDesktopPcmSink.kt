package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.media.player.util.MediaBufferEffects
import com.dreamdisplayx.util.log.DreamLog
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * Desktop [FfmpegPcmSink] backed by a Java Sound `SourceDataLine`.
 *
 * ## Never load this class on Android
 *
 * `javax.sound.sampled` does not exist in the Android runtime, so merely
 * resolving this type there throws `NoClassDefFoundError`. The only construction
 * site is [FfmpegPcmSinks.create], which checks `OsInfo.isAndroid` *before*
 * naming this class — the same arrangement [LibVlcAudioOutput] uses, and for the
 * same reason. Do not move the platform check inside this class: by then the
 * class is already being linked.
 *
 * ## Why the line is opened once and never stopped
 *
 * `SourceDataLine.stop()` / `start()` round-trips have crashed Java Sound's
 * native layer on pause/resume (see `LibVlcAudioOutput`'s notes — the same
 * 0xC0000409 / heap-corruption signature). The line here is therefore opened at
 * the first [open] and kept running; pause is expressed by the caller simply not
 * writing, and [flush] is only used for seek. The ring drains to silence on its
 * own, which is exactly the audible result of a pause.
 *
 * ## Buffer size
 *
 * ~0.2 s of stereo S16. Larger than libvlc's 0.1 s because FFmpeg paces frames
 * from this same clock: the lead has to be big enough to ride out a decode burst
 * (a B-frame reorder plus a keyframe is routinely 150 ms) without underrunning,
 * and small enough that a seek does not leave an audible remainder.
 */
internal class FfmpegDesktopPcmSink(
    private val debugLabel: String,
) : FfmpegPcmSink {

    private val logger = DreamLog.of("FfmpegDesktopAudio")

    private companion object {
        /** 44.1 kHz stereo signed 16-bit. */
        const val SAMPLE_RATE = FfmpegAudioDecoder.TARGET_RATE
        const val CHANNELS = FfmpegAudioDecoder.TARGET_CHANNELS
        const val BYTES_PER_FRAME = CHANNELS * 2

        /** Ring capacity in bytes (~0.2 s). Overridable for field diagnosis. */
        val LINE_BUFFER_BYTES: Int =
            (SAMPLE_RATE * BYTES_PER_FRAME * bufferMs() / 1000L).toInt().coerceAtLeast(4410)

        private fun bufferMs(): Int =
            System.getProperty("dreamdisplayx.ffmpegAudioBufferMs")?.trim()?.toIntOrNull()
                ?.coerceIn(40, 1000) ?: 200

        /**
         * How long a single [write] may keep retrying a full ring before giving
         * up on the remainder of that block.
         *
         * `SourceDataLine.write` blocks internally while the ring is full, so the
         * loop below polls [SourceDataLine.available] instead. A consumer that
         * stopped for good would otherwise park the decode thread forever, and
         * with it the video pipe (they share one thread in [FfmpegSession]).
         */
        const val WRITE_STALL_GIVE_UP_NS = 1_000_000_000L

        /** Poll interval while the ring is full. */
        const val WRITE_POLL_MS = 2L
    }

    @Volatile
    private var line: SourceDataLine? = null

    /**
     * Serialises access to the line. The decode thread writes; the control
     * thread flushes / polls position. Java Sound methods are individually
     * thread-safe, but flush-vs-write interleavings on a small ring underran the
     * native path on pause/resume (same crash family as libvlc's line), so every
     * touch of the line goes through this lock.
     */
    private val lineLock = Any()

    @Volatile
    private var gain = 1.0

    @Volatile
    private var written = 0L

    /** Frames the line had already emitted when [written] was last reset. */
    @Volatile
    private var baselineFrames = 0L

    @Volatile
    private var opened = false

    override val isOpen: Boolean get() = opened && line?.isOpen == true

    override fun open(): Boolean {
        if (isOpen) return true
        val fmt = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            SAMPLE_RATE.toFloat(),
            16,
            CHANNELS,
            BYTES_PER_FRAME,
            SAMPLE_RATE.toFloat(),
            false, // little-endian: matches the decoder's S16LE output
        )
        val info = DataLine.Info(SourceDataLine::class.java, fmt)
        if (!AudioSystem.isLineSupported(info)) {
            logger.warn("{} no PCM line for {} Hz/{}ch, audio stays silent", debugLabel, SAMPLE_RATE, CHANNELS)
            return false
        }
        return try {
            val newLine = AudioSystem.getLine(info) as SourceDataLine
            newLine.open(fmt, LINE_BUFFER_BYTES)
            newLine.start()
            synchronized(lineLock) {
                line = newLine
                written = 0L
                baselineFrames = runCatching { newLine.longFramePosition }.getOrDefault(0L)
                opened = true
            }
            logger.info(
                "{} audio line opened: {} Hz/{}ch, ring {} bytes (~{}ms)",
                debugLabel, SAMPLE_RATE, CHANNELS, LINE_BUFFER_BYTES,
                LINE_BUFFER_BYTES * 1000L / (SAMPLE_RATE * BYTES_PER_FRAME),
            )
            true
        } catch (t: Throwable) {
            // A missing sound device is not a playback failure: the picture must
            // still play. Log once per session and stay silent.
            logger.warn("{} audio line unavailable ({}), continuing without sound", debugLabel, t.message)
            false
        }
    }

    override fun write(pcm: ByteArray, offset: Int, length: Int): Int {
        val ln = line
        if (ln == null || !opened) return -1
        if (length <= 0) return 0
        if (offset < 0 || offset + length > pcm.size) return -1

        // Apply gain before writing: the line has no per-stream volume control,
        // and post-hoc scaling would need a second buffer plus another copy.
        val g = gain
        if (g != 1.0) MediaBufferEffects.applyVolumeS16LE(pcm, offset, length, g)

        var done = 0
        var stalledNs = 0L
        try {
            synchronized(lineLock) {
                while (done < length) {
                    val room = runCatching { ln.available() }.getOrDefault(0)
                    if (room > 0) {
                        // Whole frames only: a partial frame write would swap the
                        // channels for the rest of the session.
                        val want = minOf(room, length - done) / BYTES_PER_FRAME * BYTES_PER_FRAME
                        if (want <= 0) break
                        val n = ln.write(pcm, offset + done, want)
                        if (n <= 0) break
                        done += n
                        stalledNs = 0L
                        continue
                    }
                    if (stalledNs >= WRITE_STALL_GIVE_UP_NS) {
                        logger.warn(
                            "{} audio line stalled {} ms, dropping the rest of this block",
                            debugLabel, stalledNs / 1_000_000L,
                        )
                        break
                    }
                    runCatching { Thread.sleep(WRITE_POLL_MS) }
                    stalledNs += WRITE_POLL_MS * 1_000_000L
                }
                written += done
            }
        } catch (t: Throwable) {
            logger.warn("{} audio write failed: {}", debugLabel, t.message)
            return -1
        }
        return done
    }

    override val writtenBytes: Long get() = written

    /**
     * Bytes the device has emitted, as an offset from the position it was at
     * when this session opened.
     *
     * `SourceDataLine.getLongFramePosition()` is a lifetime counter that keeps
     * running across sessions (the line is intentionally never reopened), so the
     * baseline subtraction is what makes it "bytes played in this session".
     * Without it the master clock would start at however much sound the previous
     * video played — a display that had been running for an hour would report a
     * position of one hour on its next start.
     */
    override val playedBytes: Long
        get() {
            val ln = line ?: return 0L
            val frames = runCatching { ln.longFramePosition }.getOrDefault(0L)
            val deltaFrames = frames - baselineFrames
            if (deltaFrames <= 0L) return 0L
            return deltaFrames * BYTES_PER_FRAME
        }

    override fun flush() {
        val ln = line ?: return
        try {
            synchronized(lineLock) {
                ln.flush()
                // Re-anchor both counters: everything queued is gone, so the
                // lead is now zero and the clock restarts from here.
                written = 0L
                baselineFrames = runCatching { ln.longFramePosition }.getOrDefault(0L)
            }
        } catch (t: Throwable) {
            logger.warn("{} audio flush failed: {}", debugLabel, t.message)
        }
    }

    override fun setVolume(volume: Double) {
        gain = volume.coerceIn(0.0, 2.0)
    }

    override fun close() {
        val ln = line ?: return
        line = null
        opened = false
        runCatching { ln.stop() }
        runCatching { ln.flush() }
        runCatching { ln.close() }
        synchronized(lineLock) {
            written = 0L
            baselineFrames = 0L
        }
    }
}