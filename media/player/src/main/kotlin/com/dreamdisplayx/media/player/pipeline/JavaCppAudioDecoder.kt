@file:Suppress("Since15")

package com.dreamdisplayx.media.player.pipeline

import com.dreamdisplayx.api.security.policy.MediaHosts
import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.media.player.util.daemon
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * JavaCPP (FFmpegFrameGrabber) based audio decoder — replaces the FFmpeg CLI subprocess
 * for audio decoding. Opens a media URL in-process, decodes audio via [FFmpegFrameGrabber.grabSamples],
 * converts float samples to S16LE PCM, and writes them into a [PipedInputStream] that [AudioSink]
 * reads from (same interface as the old `Process.inputStream`).
 *
 * ## Bridge / warm park
 * Supports warm park via [setParkFlag] (pauses the decoder thread, keeping the grabber alive).
 * For the reappearance audio bridge, the decoder can be started ahead of time and the PCM
 * prelude is supplied separately (see [AudioSink.startBridge]).
 */
internal class JavaCppAudioDecoder(
    private val debugLabel: String,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/JavaCppAudioDecoder")

    companion object {
        /** Target sample rate: 44.1 kHz, stereo, S16LE — matches [AudioSink.SAMPLE_RATE]. */
        const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
        private const val BYTES_PER_FRAME = 2 * CHANNELS // S16LE stereo = 4 bytes/frame
        private const val PIPE_BUFFER_SIZE = 1024 * 1024 // 1 MB ring buffer
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        init {
            // Suppress FFmpeg's av_log INFO messages (e.g. "avformat_open_input rejected some options:
            // channels, value: 2" and full stream dumps), matching the old FFmpeg CLI's `-loglevel error`.
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        }

        /** Converts a float sample in [-1.0, 1.0] to a signed 16-bit integer, clamped. */
        private fun floatToS16(sample: Float): Short {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            return (clamped * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** The FFmpegFrameGrabber used for the current session. */
    @Volatile
    private var grabber: FFmpegFrameGrabber? = null

    /** The decoder thread, alive while the session is active. */
    @Volatile
    private var decoderThread: Thread? = null

    /** When set, the decoder idles (warm park). */
    @Volatile
    private var parked: AtomicBoolean? = null

    /** When set, the decoder stops. */
    @Volatile
    private var stopFlag: AtomicBoolean? = null

    /** Total bytes of PCM written to the output stream (for tracking). */
    private val totalBytes = AtomicLong(0)

    /** PipedOutputStream feeding the [PipedInputStream] that AudioSink reads from. */
    @Volatile
    private var outputStream: PipedOutputStream? = null

    /** The PipedInputStream exposed to AudioSink. */
    @Volatile
    private var inputStream: PipedInputStream? = null

    /** Error message from the decoder thread, if any. */
    @Volatile
    private var errorMessage: String? = null

    /** True when the decoder reached EOS normally. */
    @Volatile
    private var normalEos: Boolean = false

    /** True once the decoder has started and is running. */
    @Volatile
    private var started: Boolean = false

    /**
     * Returns the InputStream that AudioSink reads from. Must be called after [start] returns.
     */
    fun getInputStream(): InputStream? = inputStream

    /**
     * Returns the total bytes of PCM written so far (for diagnostics).
     */
    fun getTotalBytes(): Long = totalBytes.get()

    /**
     * Returns any error message from the decoder thread.
     */
    fun getErrorMessage(): String? = errorMessage

    /**
     * Returns true when the decoder reached EOS normally.
     */
    fun isNormalEos(): Boolean = normalEos

    /**
     * Returns true when the decoder has started.
     */
    fun isStarted(): Boolean = started

    /**
     * Starts the decoder thread. Opens the URL with FFmpegFrameGrabber and begins decoding.
     * Returns an InputStream that AudioSink reads from, or null if the grabber could not be opened.
     */
    fun start(
        url: String,
        seekOffsetNanos: Long,
        stopFlag: AtomicBoolean,
        terminated: AtomicBoolean,
        parkFlag: AtomicBoolean? = null,
    ): InputStream? {
        release()

        this.stopFlag = stopFlag
        this.parked = parkFlag
        this.errorMessage = null
        this.normalEos = false
        this.started = false
        this.totalBytes.set(0)

        // Create the pipe
        val pipeOut = PipedOutputStream()
        val pipeIn = try {
            PipedInputStream(pipeOut, PIPE_BUFFER_SIZE)
        } catch (e: Exception) {
            logger.error("$debugLabel Failed to create audio pipe", e)
            return null
        }
        outputStream = pipeOut
        inputStream = pipeIn

        // Open the grabber
        val g = try {
            createGrabber(url, seekOffsetNanos)
        } catch (e: Exception) {
            logger.error("$debugLabel Failed to open audio grabber for $url", e)
            runCatching { pipeOut.close() }
            runCatching { pipeIn.close() }
            outputStream = null
            inputStream = null
            return null
        }
        grabber = g

        // Start the decoder thread
        val thread = daemon(
            {
                decode(terminated)
            },
            "MediaPlayer-audio-decoder",
        ).also { it.start() }
        decoderThread = thread
        started = true

        return pipeIn
    }

    /**
     * Kills the current grabber, unblocking a decoder stuck in `grabSamples`.
     */
    fun kill() {
        val g = grabber
        if (g != null) {
            try {
                g.stop()
            } catch (_: Exception) {
                // Ignore errors during forced stop
            }
        }
    }

    /**
     * Frees the current grabber and resets state. Must only be called after the decoder thread
     * has been joined.
     */
    fun release() {
        val g = grabber
        grabber = null
        if (g != null) {
            try {
                g.stop()
                g.release()
            } catch (_: Exception) {
                // Ignore errors during cleanup
            }
        }
        runCatching { outputStream?.close() }
        outputStream = null
        inputStream = null
        decoderThread = null
        started = false
    }

    // ── Decoder loop ───────────────────────────────────────────────────────

    private fun decode(terminated: AtomicBoolean) {
        val st = stopFlag ?: return
        val out = outputStream ?: return
        val sampleBuffer = FloatArray(4096 * CHANNELS) // Up to 4096 frames per call
        val pcmBuffer = ByteArray(sampleBuffer.size * 2) // float -> S16LE doubles size? No, each float -> 2 bytes short

        try {
            while (!terminated.get() && !st.get()) {
                // Warm park
                val pk = parked
                if (pk != null && pk.get()) {
                    while (pk.get() && !terminated.get() && !st.get()) {
                        try { Thread.sleep(20) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
                    }
                    continue
                }

                val g = grabber ?: break

                val frame: Frame
                try {
                    frame = g.grabSamples() ?: break
                } catch (e: Exception) {
                    if (!terminated.get() && !st.get()) {
                        errorMessage = e.message ?: "grabSamples failed"
                        logger.warn("$debugLabel [audio] grabSamples: ${errorMessage}")
                    }
                    break
                }

                // Re-check stopped condition
                if (terminated.get() || st.get()) break
                if (frame.samples == null || frame.samples.isEmpty()) {
                    normalEos = true
                    break
                }

                // Convert the samples to S16LE PCM
                // Support both FloatBuffer (FLTP/FLT) and ShortBuffer (S16/S16P) source formats
                val srcLen: Int
                val pcmLen: Int
                val pcmTmp: ByteArray
                when (val src = frame.samples[0]) {
                    is FloatBuffer -> {
                        val remaining = src.remaining()
                        if (remaining <= 0) continue
                        val frameFrames = remaining / CHANNELS
                        pcmLen = frameFrames * BYTES_PER_FRAME
                        pcmTmp = if (pcmLen > pcmBuffer.size) ByteArray(pcmLen) else pcmBuffer
                        src.duplicate().let { s ->
                            var i = 0
                            while (i < frameFrames) {
                                val l = floatToS16(s.get())
                                pcmTmp[i * 4] = (l.toInt() and 0xFF).toByte()
                                pcmTmp[i * 4 + 1] = ((l.toInt() shr 8) and 0xFF).toByte()
                                val r = floatToS16(s.get())
                                pcmTmp[i * 4 + 2] = (r.toInt() and 0xFF).toByte()
                                pcmTmp[i * 4 + 3] = ((r.toInt() shr 8) and 0xFF).toByte()
                                i++
                            }
                        }
                        srcLen = remaining
                    }
                    is ShortBuffer -> {
                        val remaining = src.remaining()
                        if (remaining <= 0) continue
                        val frameFrames = remaining / CHANNELS
                        pcmLen = frameFrames * BYTES_PER_FRAME
                        pcmTmp = if (pcmLen > pcmBuffer.size) ByteArray(pcmLen) else pcmBuffer
                        src.duplicate().let { s ->
                            var i = 0
                            while (i < frameFrames) {
                                val l = s.get()
                                pcmTmp[i * 4] = (l.toInt() and 0xFF).toByte()
                                pcmTmp[i * 4 + 1] = ((l.toInt() shr 8) and 0xFF).toByte()
                                val r = s.get()
                                pcmTmp[i * 4 + 2] = (r.toInt() and 0xFF).toByte()
                                pcmTmp[i * 4 + 3] = ((r.toInt() shr 8) and 0xFF).toByte()
                                i++
                            }
                        }
                        srcLen = remaining
                    }
                    else -> {
                        if (MediaPlayer.DEBUG) {
                            logger.warn("$debugLabel [audio] Unsupported sample buffer type: ${src?.javaClass?.name}")
                        }
                        continue
                    }
                }

                // Write to the pipe
                try {
                    out.write(pcmTmp, 0, pcmLen)
                    out.flush()
                    totalBytes.addAndGet(pcmLen.toLong())
                } catch (e: Exception) {
                    if (!terminated.get() && !st.get()) {
                        errorMessage = "Pipe write failed: ${e.message}"
                        logger.warn("$debugLabel [audio] ${errorMessage}")
                    }
                    break
                }
            }
        } catch (e: Exception) {
            if (!terminated.get() && !st.get()) {
                errorMessage = e.message ?: "Decoder error"
                logger.warn("$debugLabel [audio] Decoder: ${errorMessage}")
            }
        } finally {
            if (!terminated.get() && !st.get() && normalEos) {
                // Normal EOS: close the pipe so AudioSink reads EOF
                runCatching { out.close() }
            }
            // If terminated/stopped, the pipe close is handled by release()
        }
    }

    // ── Grabber helpers ───────────────────────────────────────────────────

    /**
     * Creates and configures a new FFmpegFrameGrabber for audio decoding from [url].
     * Sets the output format to S16 stereo at 44.1 kHz.
     */
    @Throws(Exception::class)
    private fun createGrabber(url: String, seekOffsetNanos: Long): FFmpegFrameGrabber {
        val g = FFmpegFrameGrabber(url)
        g.setOption("probesize", "256K")
        g.setOption("analyzeduration", "200000")
        g.setOption("rw_timeout", "15000000")
        g.setOption("user_agent", USER_AGENT)
        // Platform CDNs (e.g. Bilibili's bilivideo.com) answer 403 without the right Referer.
        MediaHosts.refererFor(url)?.let { g.setOption("headers", "Referer: $it\r\n") }
        g.setOption("multiple_requests", "1")
        g.setOption("reconnect", "1")
        g.setOption("reconnect_streamed", "1")
        g.setOption("reconnect_delay_max", "10")
        g.setOption("reconnect_on_network_error", "1")
        g.setOption("reconnect_on_http_error", "5xx")
        // Audio format: 44.1 kHz, stereo
        g.sampleRate = SAMPLE_RATE
        g.audioChannels = CHANNELS
        // RAW image mode prevents javacv from setting a pixel_format codec option on the
        // format context even for audio-only streams, which avformat otherwise rejects
        // with the noisy "pixel_format, value: bgr24" info log.
        g.imageMode = FrameGrabber.ImageMode.RAW
        // Hint the HTTP protocol that the server answers Range requests so a VOD seek jumps to the
        // target offset instead of downloading from the start (the source of slow seeks).
        if (seekOffsetNanos > 0) {
            g.setOption("seekable", "1")
        }
        g.start()
        // Seek if needed
        if (seekOffsetNanos > 0) {
            g.setTimestamp(seekOffsetNanos / 1000L) // nanos to micros
        }
        return g
    }
}