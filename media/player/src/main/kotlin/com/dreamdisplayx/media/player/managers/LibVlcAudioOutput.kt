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

        /** Line buffer bytes (~0.4 s of stereo S16; matches the old AudioSink's pacing ceiling). */
        private const val LINE_BUFFER_BYTES = SAMPLE_RATE * BYTES_PER_FRAME / 20 * 8
    }

    // ── State ────────────────────────────────────────────────────────────────

    @Volatile
    private var line: SourceDataLine? = null

    /** Effective volume (user volume × distance attenuation), 0..~2. */
    @Volatile
    private var gain = 1.0

    /** Reusable PCM buffer, read on the libvlc audio thread only (play callbacks are serialised). */
    private var pcmBuffer = ByteArray(0)

    // ── Control ──────────────────────────────────────────────────────────────

    /** Resets per-session state and re-primes the DSP chain (called at the start of every playback). */
    fun reset() {
        runCatching { dspStage?.reset() }
        runCatching { line?.flush() }
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
     * which is the audio clock our video rendering follows.
     */
    fun onPlay(data: Pointer?, samples: Pointer?, count: Int, pts: Long) {
        if (samples == null || count <= 0) return
        val ln = line ?: return
        val bytes = count * BYTES_PER_FRAME
        if (pcmBuffer.size < bytes) pcmBuffer = ByteArray(bytes)
        samples.read(0, pcmBuffer, 0, bytes)
        feed(pcmBuffer, bytes, ln)
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

    /** Discards buffered PCM (seek / stop). */
    @Suppress("UNUSED_PARAMETER")
    fun onFlush(data: Pointer?, pts: Long) {
        runCatching { line?.flush() }
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
            logger.info("$debugLabel audio line opened ({} Hz, {} ch).", SAMPLE_RATE, CHANNELS)
        } catch (e: LineUnavailableException) {
            logger.warn("$debugLabel audio line unavailable: ${e.message}.")
        } catch (t: Throwable) {
            logger.warn("$debugLabel audio line open failed: ${t.message}")
        }
    }
}