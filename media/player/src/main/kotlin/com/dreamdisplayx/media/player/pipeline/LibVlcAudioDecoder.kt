@file:Suppress("Since15")

package com.dreamdisplayx.media.player.pipeline

import com.dreamdisplayx.api.security.policy.MediaHosts
import com.dreamdisplayx.media.player.util.daemon
import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.callback.AudioCallback
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * LibVLC (vlcj) based audio decoder — replaces [JavaCppAudioDecoder].
 * Opens a media URL via libvlc, decodes audio with libvlc's audio
 * callbacks (S16N at 44.1 kHz stereo), and writes the PCM into a
 * [PipedInputStream] that [AudioSink] reads from (same interface as the
 * old JavaCPP decoder / FFmpeg CLI subprocess).
 *
 * ## Bridge / warm park
 * Supports warm park via [setParkFlag] (pauses libvlc playback, keeping the
 * instance alive). For the reappearance audio bridge, the decoder can be
 * started ahead of time and the PCM prelude is supplied separately (see
 * [AudioSink.startBridge]).
 */
internal class LibVlcAudioDecoder(
    private val debugLabel: String,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/LibVlcAudioDecoder")

    companion object {
        /** Target sample rate: 44.1 kHz, stereo, S16 — matches [AudioSink.SAMPLE_RATE]. */
        const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
        private const val BYTES_PER_FRAME = 2 * CHANNELS // S16LE stereo = 4 bytes/frame
        private const val PIPE_BUFFER_SIZE = 1024 * 1024 // 1 MB ring buffer

        /** libvlc options: audio-only, no video output. */
        private val SHARED_LIBVLC_ARGS = listOf(
            "--no-video-title-show",
            "--quiet",
            "--no-keyboard-events",
            "--no-mouse-events",
            "--network-caching=300",
            "--file-caching=300",
            "--live-caching=600",
            "--no-video",  // audio decoder only, disable video output
            "--no-spu",    // no subtitles
        )
    }

    /** The libvlc media player factory for the current session. */
    @Volatile
    private var factory: MediaPlayerFactory? = null

    /** The libvlc media player for the current session. */
    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    /** The decoder control thread, alive while the session is active. */
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

    /** The seek offset in nanos, applied after the player opens. */
    @Volatile
    private var pendingSeekNanos: Long = 0L

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
     * Starts the decoder. Opens the URL with libvlc and begins decoding via audio callbacks.
     * Returns an InputStream that AudioSink reads from, or null if libvlc could not open.
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
        this.pendingSeekNanos = seekOffsetNanos

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

        // Build libvlc args
        val args = mutableListOf<String>()
        args.addAll(SHARED_LIBVLC_ARGS)
        MediaHosts.refererFor(url)?.let { referer ->
            args.add("--http-referer=$referer")
        }

        val fact = try {
            MediaPlayerFactory(args)
        } catch (e: Exception) {
            logger.error("$debugLabel Failed to create MediaPlayerFactory for $url", e)
            runCatching { pipeOut.close() }
            runCatching { pipeIn.close() }
            outputStream = null
            inputStream = null
            return null
        }
        factory = fact

        val mp = fact.mediaPlayers().newMediaPlayer()
        mediaPlayer = mp

        // Audio callbacks: S16N (native-endian signed 16-bit) at 44.1 kHz stereo
        val audioCallback = object : AudioCallback {
            override fun play(mp: MediaPlayer, samples: com.sun.jna.Pointer, sampleCount: Int, pts: Long) {
                if (sampleCount <= 0) return
                val byteCount = sampleCount * 4 // S16 stereo = 4 bytes per frame... but this is per-channel samples
                // Note: libvlc passes the number of samples PER CHANNEL in `samples` (frames).
                // S16 stereo: each frame is 4 bytes (L + R). So total bytes = frames * 4.
                val bytes = byteCount
                writePcm(samples, bytes)
            }

            override fun pause(mp: MediaPlayer, pts: Long) {
                // No-op for pipe-based sink: the AudioSink handles its own pause.
            }

            override fun resume(mp: MediaPlayer, pts: Long) {
                // No-op
            }

            override fun flush(mp: MediaPlayer, pts: Long) {
                // No-op
            }

            override fun drain(mp: MediaPlayer) {
                // No-op
            }

            override fun setVolume(volume: Float, mute: Boolean) {
                // No-op: volume is handled by the AudioSink
            }
        }
        mp.audio().callback("S16N", SAMPLE_RATE, CHANNELS, audioCallback)

        // Logger events
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) {
                logger.debug("$debugLabel [audio] libvlc playing.")
            }

            override fun finished(mp: MediaPlayer) {
                logger.debug("$debugLabel [audio] libvlc finished.")
            }

            override fun error(mp: MediaPlayer) {
                logger.error("$debugLabel [audio] libvlc error.")
                errorMessage = "libvlc audio error"
            }
        })

        // Start the decoder control thread BEFORE playback begins so the pipe is drained.
        val thread = daemon(
            { controlLoop(terminated) },
            "MediaPlayer-audio-decoder",
        ).also { it.start() }
        decoderThread = thread

        // Begin playback (this starts libvlc's internal decoding + audio callback threads)
        val startedOk = try {
            mp.media().play(url)
        } catch (e: Exception) {
            logger.error("$debugLabel [audio] Failed to start libvlc play for $url", e)
            errorMessage = e.message
            release()
            return null
        }
        if (!startedOk) {
            logger.error("$debugLabel [audio] libvlc play() returned false for $url")
            errorMessage = "libvlc play() failed"
            release()
            return null
        }
        started = true

        // Apply the seek after the player is up
        if (seekOffsetNanos > 0) {
            try {
                mp.controls().setTime(seekOffsetNanos / 1_000_000L)
            } catch (e: Exception) {
                logger.warn("$debugLabel [audio] seek failed: ${e.message}")
            }
        }

        return pipeIn
    }

    /** Writes PCM bytes from a libvlc callback pointer into the pipe. */
    private fun writePcm(pointer: com.sun.jna.Pointer, byteCount: Int) {
        val out = outputStream ?: return
        if (byteCount <= 0) return
        try {
            // Copy the native audio buffer into a Java byte array in one shot
            val bytes = pointer.getByteArray(0, byteCount)
            out.write(bytes, 0, byteCount)
            totalBytes.addAndGet(byteCount.toLong())
        } catch (e: Exception) {
            if (errorMessage == null) {
                errorMessage = "Audio pipe write failed: ${e.message}"
                logger.warn("$debugLabel [audio] ${errorMessage}")
            }
        }
    }

    /**
     * Kills the current player, unblocking the decoder.
     */
    fun kill() {
        mediaPlayer?.controls()?.stop()
    }

    /**
     * Frees the current player and resets state.
     */
    fun release() {
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            try {
                mp.controls().stop()
                mp.release()
            } catch (_: Exception) {
                // Ignore errors during forced cleanup
            }
        }
        val fact = factory
        factory = null
        if (fact != null) {
            try {
                fact.release()
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

    // ── Control loop ───────────────────────────────────────────────────────

    /**
     * Monitors the libvlc player state and shuts the pipe down at EOS.
     * PCM delivery is driven entirely by the audio callback; this loop only
     * watches for the end of stream and warm-park idle.
     */
    private fun controlLoop(terminated: AtomicBoolean) {
        val st = stopFlag ?: return
        val out = outputStream ?: return
        try {
            while (!terminated.get() && !st.get()) {
                // Warm park: pause libvlc playback (audio callbacks stop, pipe stalls)
                val pk = parked
                if (pk != null && pk.get()) {
                    val isPaused = mediaPlayer?.status()?.isPlaying() == false
                    if (!isPaused) {
                        try { mediaPlayer?.controls()?.setPause(true) } catch (_: Exception) { }
                    }
                    while (pk.get() && !terminated.get() && !st.get()) {
                        try { Thread.sleep(20) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
                    }
                    try { mediaPlayer?.controls()?.setPause(false) } catch (_: Exception) { }
                    continue
                }

                val mp = mediaPlayer ?: break
                val state = mp.status().state()
                val stopped = state == uk.co.caprica.vlcj.player.base.State.ENDED
                val errored = state == uk.co.caprica.vlcj.player.base.State.ERROR
                if (stopped || errored) {
                    if (errored && errorMessage == null) errorMessage = "libvlc audio state = ERROR"
                    if (stopped) normalEos = true
                    break
                }
                try { Thread.sleep(50) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
            }
        } finally {
            if (!terminated.get() && !st.get() && (normalEos || errorMessage != null)) {
                // End the pipe so AudioSink reads EOF
                runCatching { out.close() }
            }
        }
    }
}