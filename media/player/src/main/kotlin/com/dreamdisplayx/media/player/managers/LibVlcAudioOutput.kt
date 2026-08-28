package com.dreamdisplayx.media.player.managers

import com.dreamdisplayx.api.media.audio.service.AudioDspStage
import com.dreamdisplayx.media.player.util.MediaBufferEffects
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine

/**
 * Owns the audio output for the libvlc player. libvlc is configured with audio callbacks
 * (`libvlc_audio_set_callbacks` + `libvlc_audio_set_format_callbacks`) so decoded PCM is delivered
 * to us instead of being played through libvlc's default output. We feed it through the per-display
 * [AudioDspStage] (3D spatialisation: direction-aware panning / binaural, occlusion, reverb) and
 * write the result to a Java Sound [SourceDataLine] on our own clock. This restores the directional
 * 3D audio (left/right panning that libvlc's flat stereo output lost), occlusion (blocking the
 * display mutes/muffles the sound) and lets the display control its own audio pacing instead of
 * libvlc stopping the audio a couple of seconds before the video ends.
 *
 * The line is shared across session restarts (the single libvlc player is never rebuilt): [reset]
 * runs at the start of each playback session, [setVolume] updates the gain libvlc would otherwise
 * have applied, [close] releases the line for good.
 */
internal class LibVlcAudioOutput(
    private val debugLabel: String,
    private val dspStage: AudioDspStage?,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/LibVlcAudio")

    companion object {
        /** The PCM format negotiated with libvlc and used by every session: 44.1 kHz stereo S16 (native endianness). */
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_FRAME = CHANNELS * BYTES_PER_SAMPLE

        /** Nanoseconds per decoded frame; the blip of one stereo S16 sample pair. */
        private const val FRAME_NANOS = 1_000_000_000L / SAMPLE_RATE

        /**
         * Line buffer bytes (~0.5 s of stereo S16). Moderately generous so the line rarely underruns even
         * across game hitches: when [SourceDataLine.write] blocks, libvlc's audio thread stalls and with
         * it the video (paced by libvlc's delivery clock), so a too-tight ring (0.2 s) showed up as
         * audible stutter and occasional video hitches. ~0.5 s still bounds how far video can run ahead
         * of what you hear while absorbing scheduling jitter.
         */
        private const val LINE_BUFFER_BYTES = SAMPLE_RATE * BYTES_PER_FRAME * 5 / 10
    }

    // ── State ────────────────────────────────────────────────────────────────

    @Volatile
    private var line: SourceDataLine? = null

    /** Effective volume (user volume × distance attenuation), 0..~2. */
    @Volatile
    private var gain = 1.0

    /** Reusable PCM buffer, read on the libvlc audio thread only (play callbacks are serialised). */
    private var pcmBuffer = ByteArray(0)

    // ── A/V sync clock ───────────────────────────────────────────────────────
    //
    // After the audio split, libvlc's own clock advances as samples are *delivered* to our play
    // callback, but the sound only reaches the speakers after the line's ring buffer drains. The
    // authoritative position is therefore computed from the line's REAL playback cursor each time:
    // the media time of the newest sample we've written, minus the frames still sitting in the line
    // buffer (frames written but not yet emitted). This self-heals on every audio block — no
    // long-lived anchor to go stale — so seeks, restarts and live streams can't make the position
    // extrapolate wildly (which a single anchor did, reading the line's cumulative counter as if it
    // were media time and jumping to ~100 hours on a long-running game).

    /** Cumulative frames written to the line since it opened (or since the last flush). */
    @Volatile
    private var totalWrittenFrames = 0L

    /** True once at least one audio block has been written since the last flush. */
    @Volatile
    private var clockLive = false

    // ── Control ──────────────────────────────────────────────────────────────

    /** Resets per-session state and re-primes the DSP chain (called at the start of every playback). */
    fun reset() {
        runCatching { dspStage?.reset() }
        runCatching { line?.flush() }
        totalWrittenFrames = runCatching { line?.getLongFramePosition() ?: 0L }.getOrDefault(0L)
        clockLive = false
    }

    /** Updates the gain applied to the PCM (user volume × distance attenuation). */
    fun setVolume(volume: Double) {
        gain = volume.coerceIn(0.0, 2.0)
    }

    /** Releases the audio line permanently. */
    fun close() {
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
    }

    // ── libvlc format callback (called on the libvlc audio thread) ───────────

    /**
     * Tells libvlc which PCM format we want: S16 (native endianness) at 44.1 kHz stereo, matching
     * the DSP chain and the old JavaCPP AudioSink. Returns 0 on success.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onFormatSetup(data: com.sun.jna.ptr.PointerByReference?, format: Pointer?, rate: Pointer?, channels: Pointer?): Int {
        return try {
            format?.setString(0, "S16N")
            rate?.setInt(0, SAMPLE_RATE)
            channels?.setInt(0, CHANNELS)
            0
        } catch (t: Throwable) {
            logger.warn("$debugLabel audio format setup failed: ${t.message}")
            -1
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onFormatCleanup(data: Pointer?) {
        // Nothing to release; the line is owned separately and closed with [close].
    }

    // ── libvlc audio callbacks (called on the libvlc audio thread) ────────────

    /**
     * Receives [samples] (count per-channel frames of S16 PCM), runs it through the 3D DSP stage
     * and writes it to the line. Blocking on the line write naturally paces libvlc's audio thread,
     * which is the audio clock our video rendering follows. Also re-anchors the A/V clock: the
     * first sample of this block (libvlc audio-callback pts, in µs) corresponds to the line's frame
     * counter as it stands, so [playedPositionNanos] can map line position -> media position.
     */
    fun onPlay(data: Pointer?, samples: Pointer?, count: Int, pts: Long) {
        if (samples == null || count <= 0) return
        val ln = line ?: return
        // Track only how many frames we've written vs. how many the line has emitted; the ring-buffer
        // delta is the A/V lead (see [bufferedNanos]). We deliberately ignore `pts`: on libvlc 3.0.21
        // the custom-audio-callback pts is a system monotonic clock (~uptime) rather than media time,
        // so anchoring a media position on it produced ~100-hour readings.
        val countL = count.toLong()
        totalWrittenFrames += countL
        clockLive = true
        val bytes = count * BYTES_PER_FRAME
        if (pcmBuffer.size < bytes) pcmBuffer = ByteArray(bytes)
        samples.read(0, pcmBuffer, 0, bytes)
        feed(pcmBuffer, bytes, ln)
    }

    /**
     * How far the audio that's still sitting in the line's ring buffer trails the video, in nanos —
     * i.e. video-leading-audible-audio. Computed as `(written - emitted) * FRAME_NANOS`, the number of
     * frames queued but not yet heard. Stable & small (~the buffer size) means healthy sync; growing
     * steadily means real A/V drift. Returns `null` until audio has been delivered at least once.
     */
    fun bufferedNanos(): Long? {
        val ln = line ?: return null
        if (!clockLive) return null
        val emitted = ln.getLongFramePosition()
        val buffered = (totalWrittenFrames - emitted).coerceAtLeast(0L)
        return buffered * FRAME_NANOS
    }

    /**
     * Best-effort audio playback position. libvlc's audio-callback `pts` is *not* trustworthy media time
     * on 3.0.21 (it reads as a monotonic clock), so this reconstructs the position from the delivered
     * playback clock instead: whatever the caller supplies. Returning the line buffer latency via
     * [bufferedNanos] is the preferred, unit-clean signal for tuning; this helper is kept for callers
     * that need an absolute position and can pass a trusted reference (e.g. libvlc `get_time`).
     */
    fun playedPositionNanos(referenceNanos: Long): Long? {
        val buf = bufferedNanos() ?: return null
        return (referenceNanos - buf).coerceAtLeast(0L)
    }

    private fun feed(buf: ByteArray, bytes: Int, ln: SourceDataLine) {
        try {
            val g = gain
            if (dspStage != null) {
                dspStage.process(buf, bytes, g)
            } else {
                MediaBufferEffects.applyVolumeS16LE(buf, bytes, g)
            }
            var written = 0
            while (written < bytes) {
                val n = ln.write(buf, written, bytes - written)
                if (n <= 0) return
                written += n
            }
        } catch (t: Throwable) {
            // Never throw into the JNA callback trampoline; just drop the block.
            logger.warn("$debugLabel audio write dropped: ${t.message}")
        }
    }

    /** Pauses the line (keeps buffered PCM; resumes with [onResume]). */
    @Suppress("UNUSED_PARAMETER")
    fun onPause(data: Pointer?, pts: Long) {
        runCatching { line?.stop() }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onResume(data: Pointer?, pts: Long) {
        runCatching { line?.start() }
    }

    /** Discards buffered PCM (seek / stop): pretend only the already-emitted frames exist. */
    @Suppress("UNUSED_PARAMETER")
    fun onFlush(data: Pointer?, pts: Long) {
        runCatching { line?.flush() }
        totalWrittenFrames = runCatching { line?.getLongFramePosition() ?: 0L }.getOrDefault(0L)
        clockLive = false
    }

    /** Drains buffered PCM (end of stream). */
    @Suppress("UNUSED_PARAMETER")
    fun onDrain(data: Pointer?) {
        runCatching { line?.drain() }
    }

    // ── Line lifecycle ───────────────────────────────────────────────────────

    /**
     * Opens (or reuses) the Java Sound line. Called from the control executor before playback
     * starts; a failure here only silences audio, video keeps working.
     */
    fun openLine() {
        val existing = line
        if (existing != null && existing.isOpen) return
        val fmt = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE.toFloat(), 16, CHANNELS, BYTES_PER_FRAME, SAMPLE_RATE.toFloat(), false,
        )
        val info = DataLine.Info(SourceDataLine::class.java, fmt)
        if (!AudioSystem.isLineSupported(info)) {
            logger.warn("$debugLabel PCM line not supported.")
            return
        }
        try {
            val newLine = AudioSystem.getLine(info) as SourceDataLine
            newLine.open(fmt, LINE_BUFFER_BYTES)
            newLine.start()
            line = newLine
            totalWrittenFrames = 0L
            clockLive = false
            logger.info("$debugLabel audio line opened ({} Hz, {} ch).", SAMPLE_RATE, CHANNELS)
        } catch (e: LineUnavailableException) {
            logger.warn("$debugLabel audio line unavailable: ${e.message}.")
        } catch (t: Throwable) {
            logger.warn("$debugLabel audio line open failed: ${t.message}")
        }
    }
}