package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.api.media.audio.service.AudioDspStage
import com.dreamdisplayx.media.player.util.MediaBufferEffects
import com.dreamdisplayx.util.log.DreamLog

/**
 * Drives one [FfmpegPcmSink] from decoded PCM: applies the DSP stage, paces the
 * writes so the device never runs far ahead of real time, and exposes the
 * playback position that becomes the master clock.
 *
 * ## Why pacing is not optional
 *
 * A file source decodes far faster than real time — a 44.1 kHz AAC track can be
 * produced at 10x speed. Writing every chunk as soon as it appears fills the
 * device's ring in a fraction of a second and then the two failure modes are
 * both audible and both wrong:
 *
 * - the ring never drains, so a pause or a seek has to wait for up to a second of
 *   already-queued sound before its effect is heard;
 * - the reported position is the device's *played* counter, so the video (paced
 *   from it) would stall as soon as the ring saturates, because nothing new
 *   could be written.
 *
 * So writes are bounded to [AudioSinkPolicy.MAX_LEAD_NANOS] of queued audio and
 * the decode loop waits when the bound is reached. That ties the decoder's
 * throughput to real time without ever letting the queue grow unbounded.
 *
 * ## Threading
 *
 * Every method here runs on the decode thread, except [setVolume] / [positionNanos]
 * / [flush], which are called from the control and render threads. Those three
 * only read or write volatile fields and never touch the sink except through
 * [flush], which is deliberately the only control-thread device call (a seek
 * cannot wait for the decode thread to notice).
 */
internal class FfmpegAudioOutput(
    private val debugLabel: String,

    /** Optional 3D / spatialisation stage; null keeps plain stereo. */
    private val dspStage: AudioDspStage? = null,

    /** Injectable for tests; production always uses the platform factory. */
    private val sinkFactory: (String) -> FfmpegPcmSink? = FfmpegPcmSinks::create,
) {

    private val logger = DreamLog.of("FfmpegAudioOutput")

    private companion object {
        /** How long [awaitLeadRoom] parks between device polls. */
        const val PACING_POLL_MS = 2L

        /**
         * Bound on a single pacing wait.
         *
         * A device whose played counter stops moving would otherwise park the
         * decode thread here forever, and with it the video pipe they share.
         * One second is comfortably longer than any legitimate drain.
         */
        const val PACING_MAX_WAIT_MS = 1000L
    }

    private val sink: FfmpegPcmSink? = sinkFactory(debugLabel)

    /** True once the device is open and accepting PCM. */
    @Volatile
    private var active = false

    /** Effective gain (user volume × distance attenuation), 0..2. */
    @Volatile
    private var gain = 1.0

    /** Content time this session started at; the clock counts forward from here. */
    @Volatile
    private var contentStartNanos = 0L

    /** Bytes accepted by the device in this session; mirrors the sink, kept locally so a flush resets both. */
    @Volatile
    private var written = 0L

    /** Bytes the device had already played when this session started. */
    @Volatile
    private var playedBaseline = 0L

    /** True once the first block reached the device (one-shot diagnostic). */
    @Volatile
    private var loggedFirstBlock = false

    /** Whether an output device exists at all for this platform/session. */
    val hasDevice: Boolean get() = sink != null

    val isActive: Boolean get() = active

    /**
     * Opens the device and starts a fresh clock at [startNanos].
     *
     * @return true when audio will actually be heard
     */
    fun open(startNanos: Long): Boolean {
        val s = sink ?: run {
            logger.info("{} no PCM sink on this platform; audio stays on the other backend", debugLabel)
            return false
        }
        if (active) {
            // Already open: only the clock needs re-anchoring. Reopening would
            // reset the device's counters and make the master clock jump.
            reanchor(startNanos)
            return true
        }
        if (!s.open()) {
            logger.warn("{} PCM sink refused to open; playing without sound", debugLabel)
            return false
        }
        runCatching { dspStage?.reset() }
        reanchor(startNanos)
        s.setVolume(gain)
        loggedFirstBlock = false
        active = true
        logger.info("{} audio output open at {}ns", debugLabel, startNanos)
        return true
    }

    /**
     * Resets the clock to [startNanos] and drops counters, without touching the
     * device (a seek calls this right after [flush]).
     */
    private fun reanchor(startNanos: Long) {
        contentStartNanos = if (startNanos > 0L) startNanos else 0L
        written = 0L
        playedBaseline = sink?.playedBytes ?: 0L
    }

    /**
     * Accepts one decoded chunk: DSP, pacing, then the device.
     *
     * Never throws — a failure here must cost one block of sound, not the whole
     * video, and this runs on the shared decode thread.
     */
    fun write(pcm: ByteArray, length: Int = pcm.size) {
        if (!active) return
        val s = sink ?: return
        if (length <= 0) return
        val bytes = minOf(length, pcm.size)

        // Gain and spatialisation happen on our copy, before the device. Doing it
        // after the write would need a second buffer and a full copy per block;
        // doing it in the sink would keep the DSP stage out of the picture.
        runCatching {
            if (dspStage != null) {
                dspStage.process(pcm, bytes, gain)
            } else {
                MediaBufferEffects.applyVolumeS16LE(pcm, 0, bytes, gain)
            }
        }.onFailure { logger.warn("{} DSP stage failed: {}", debugLabel, it.message) }

        if (!awaitLeadRoom(s)) {
            logger.debug("{} pacing wait capped; writing anyway to avoid a stall", debugLabel)
        }

        var offset = 0
        var stalledNs = 0L
        while (offset < bytes) {
            val room = AudioSinkPolicy.bytesAllowedNow(
                writtenBytes = written,
                playedBytes = s.playedBytes - playedBaseline,
                pendingBytes = bytes - offset,
            )
            // `bytesAllowedNow` returns 0 both for "queue full" and for "nothing
            // pending". Nothing pending is an empty remainder, which the loop
            // condition already excludes, so 0 here means the queue is full.
            if (room <= 0) {
                if (stalledNs >= PACING_MAX_WAIT_MS * 1_000_000L) break
                runCatching { Thread.sleep(PACING_POLL_MS) }
                stalledNs += PACING_POLL_MS * 1_000_000L
                continue
            }
            val n = s.write(pcm, offset, room)
            if (n < 0) {
                logger.warn("{} PCM sink is gone; audio muted for the rest of the session", debugLabel)
                active = false
                return
            }
            if (n == 0) {
                if (stalledNs >= PACING_MAX_WAIT_MS * 1_000_000L) break
                runCatching { Thread.sleep(PACING_POLL_MS) }
                stalledNs += PACING_POLL_MS * 1_000_000L
                continue
            }
            offset += n
            written += n
            stalledNs = 0L
        }

        if (!loggedFirstBlock && written > 0L) {
            loggedFirstBlock = true
            logger.info(
                "{} first audio block reached the device ({} bytes, {} of {} accepted)",
                debugLabel, bytes, offset, bytes,
            )
        }
    }

    /**
     * Parks until the device has room for more audio, bounded by [PACING_MAX_WAIT_MS].
     *
     * @return true when the lead is back under the ceiling; false when the wait
     *   was capped (the caller writes anyway rather than starving video)
     */
    private fun awaitLeadRoom(s: FfmpegPcmSink): Boolean {
        val start = System.nanoTime()
        var waitedNs = 0L
        while (true) {
            val queued = written - (s.playedBytes - playedBaseline)
            if (queued <= 0L) return true
            val wait = AudioSinkPolicy.waitNanos(written, s.playedBytes - playedBaseline, waitedNs)
            if (wait <= 0L) return true
            if (waitedNs >= PACING_MAX_WAIT_MS * 1_000_000L) return false
            val step = minOf(PACING_POLL_MS * 1_000_000L, wait)
            runCatching { Thread.sleep(step / 1_000_000L) }
            waitedNs = System.nanoTime() - start
        }
    }

    /**
     * Current playback position in content nanos.
     *
     * Anchored on the device's own played-byte counter, not on wall time: the
     * device can drift against the clock and the picture is paced from this
     * number, so anchoring on wall time would turn that drift into judder.
     * Returns [contentStartNanos] when nothing has played yet, which is what makes
     * a freshly opened display report its seek target instead of zero.
     */
    fun positionNanos(): Long {
        val s = sink ?: return contentStartNanos
        if (!active) return contentStartNanos
        val played = s.playedBytes - playedBaseline
        if (!AudioSinkPolicy.isPlayedPlausible(played, written)) {
            // A device that reports more than was ever written has wrapped or
            // been reset; trusting it would jump the picture forward.
            return contentStartNanos
        }
        return AudioSinkPolicy.contentNanos(contentStartNanos, played.coerceAtLeast(0L))
    }

    /** Nanos of audio currently queued but not yet played (the A/V lead). */
    fun bufferedNanos(): Long {
        val s = sink ?: return 0L
        if (!active) return 0L
        val queued = written - (s.playedBytes - playedBaseline)
        return if (queued <= 0L) 0L else AudioSinkPolicy.bytesToNanos(queued)
    }

    /** Applies [volume] (0..2) to subsequent writes. */
    fun setVolume(volume: Double) {
        gain = volume.coerceIn(0.0, 2.0)
        runCatching { sink?.setVolume(gain) }
    }

    /** Current gain, for diagnostics. */
    fun currentVolume(): Double = gain

    /**
     * Discards queued sound and re-anchors the clock, for a seek.
     *
     * Safe to call from the control thread: this is the one device operation a
     * seek cannot defer, because the queued audio is already the wrong content.
     */
    fun flush() {
        val s = sink ?: return
        runCatching { dspStage?.reset() }
        runCatching { s.flush() }
        written = 0L
        playedBaseline = s.playedBytes
        loggedFirstBlock = false
    }

    /** Releases the device. Repeat calls are safe. */
    fun close() {
        active = false
        runCatching { dspStage?.close() }
        runCatching { sink?.close() }
    }

    /** Diagnostic line for the F3 overlay / logs. */
    fun describe(): String =
        if (!hasDevice) "no-device"
        else if (!active) "closed"
        else "pos=${positionNanos() / 1_000_000}ms lead=${bufferedNanos() / 1_000_000}ms vol=$gain"
}