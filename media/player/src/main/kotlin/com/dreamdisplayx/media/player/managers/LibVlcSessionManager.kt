package com.dreamdisplayx.media.player.managers

import com.dreamdisplayx.api.media.model.DreamMediaException
import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.model.StretchMode
import com.dreamdisplayx.api.media.player.FrameUploaderFactory
import com.dreamdisplayx.api.media.player.GpuTextureRef
import com.dreamdisplayx.api.media.player.RenderExecutor
import com.dreamdisplayx.api.security.policy.MediaHosts
import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.media.player.events.PlayerEvents
import com.dreamdisplayx.api.media.audio.service.AudioDspStage
import com.dreamdisplayx.media.player.pipeline.FrameSurface
import com.dreamdisplayx.media.player.pipeline.PlaybackClock
import com.dreamdisplayx.media.player.stream.ActiveStreams
import com.dreamdisplayx.media.player.stream.MediaStreamSelector
import com.dreamdisplayx.media.player.util.daemon
import com.dreamdisplayx.media.player.util.joinSafely
import com.dreamdisplayx.media.runtime.security.MediaHostGuard
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer as VlcjMediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.callback.AudioCallback
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * LibVLC-based session manager — replaces [PlaybackSessionManager].
 *
 * Uses a single libvlc [EmbeddedMediaPlayer] to handle both video and audio,
 * delivering frames through the video callback ([RenderCallback]) into the
 * FrameSurface, and PCM through the audio callback into a PipedOutputStream
 * consumed by AudioSink.
 *
 * No separate reader thread, no manual pacing, no prebuffer — libvlc handles
 * demuxing, decoding, A/V sync, and hardware acceleration internally.
 */
internal class LibVlcSessionManager(
    private val debugLabel: String,
    private val clock: PlaybackClock,
    private val events: PlayerEvents,
    private val terminated: AtomicBoolean,

    /** Returns the current GPU texture dimensions (width to height). */
    private val getTextureSize: () -> Pair<Int, Int>,
    private val getBrightness: () -> Double,
    private val getStretchMode: () -> StretchMode = { StretchMode.LETTERBOX },

    /** Invoked when the stream ends or errors. */
    private val onStreamEnd: (stderr: String, normalEos: Boolean) -> Unit,

    /** Invoked when quality switch fails before promotion. */
    private val onQualitySwitchAborted: (appliedAnyway: Boolean) -> Unit = {},

    /** Invoked when live audio process ends unexpectedly. */
    private val onAudioFailure: (stderr: String) -> Unit = {},

    /** Invoked once an in-flight audio track switch settles. */
    private val onAudioTrackSwitchSettled: () -> Unit = {},

    /** Runs render-thread (GL) cleanup work. */
    private val renderExecutor: RenderExecutor,

    /** Creates per-channel GPU frame uploaders. */
    private val uploaderFactory: FrameUploaderFactory,

    /** Whether the GPU-side planar (I420) render path is active. */
    private val gpuYuvActive: Boolean,

    /** Per-display acoustics DSP stage. */
    audioStage: AudioDspStage? = null,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/LibVlcSession")

    // ── libvlc lifecycle ──────────────────────────────────────────────────

    @Volatile
    private var factory: MediaPlayerFactory? = null

    @Volatile
    private var mediaPlayer: EmbeddedMediaPlayer? = null

    /** EOS monitor thread. */
    @Volatile
    private var eosThread: Thread? = null

    /** Audio output pipe — AudioSink reads from the InputStream end. */
    @Volatile
    private var audioPipeOut: PipedOutputStream? = null

    @Volatile
    private var audioPipeIn: PipedInputStream? = null

    // ── Frame surface ─────────────────────────────────────────────────────

    private val surface = FrameSurface(debugLabel, uploaderFactory, FramePixelFormat.RGB24)

    @Volatile
    var expectedW = 0; private set

    @Volatile
    var expectedH = 0; private set

    /** EOS/error signals from the libvlc event listener. */
    @Volatile
    private var eosReached = false

    @Volatile
    private var errorMessage = ""

    // ── Audio sink ────────────────────────────────────────────────────────

    private val audio = AudioSink(debugLabel)

    // ── Park state ────────────────────────────────────────────────────────

    private val parkFlag = AtomicBoolean(false)

    /** Position at which the session was parked (nanos). */
    private var parkPositionNanos = 0L

    // ── Popout / preview sinks ────────────────────────────────────────────

    private var popoutSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null
    private var previewSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = popoutSink
        set(value) {
            popoutSink = value
            updateRawFrameSink()
        }

    var previewFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = previewSink
        set(value) {
            previewSink = value
            updateRawFrameSink()
        }

    private fun updateRawFrameSink() {
        val popout = popoutSink
        val preview = previewSink
        val sink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = when {
            popout != null && preview != null -> { buf, w, h, fmt ->
                val pos = buf.position()
                val limit = buf.limit()
                popout(buf, w, h, fmt)
                buf.limit(limit).position(pos)
                preview(buf, w, h, fmt)
            }
            popout != null -> popout
            preview != null -> preview
            else -> null
        }
        // The pipe's popoutFrameSink is not exposed here; we handle sink in the render callback.
    }

    // ── Volume ────────────────────────────────────────────────────────────

    fun setVolume(volume: Double) {
        audio.setVolume(volume)
    }

    // ── Frame pipe proxy ──────────────────────────────────────────────────

    private val noFrames = AtomicLong(0)

    var isPlaying = false; private set

    val lastFrameNanos: AtomicLong get() = noFrames

    fun textureFilled(): Boolean = surface.textureFilled()

    fun updateFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean =
        surface.updateFrame(texture, w, h, expectedW, expectedH)

    fun updateFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        surface.updateFramePlanar(y, u, v, w, h, expectedW, expectedH)

    fun hasIncoming(): Boolean = false // libvlc uses hard quality switch, no parallel channel

    fun updateIncomingFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean = false

    fun updateIncomingFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean = false

    fun clearFrame() = surface.clear()

    // ── Session lifecycle ─────────────────────────────────────────────────

    /**
     * Starts a new playback session with libvlc.
     */
    fun start(
        streamSet: ActiveStreams,
        offsetNanos: Long,
        lastQuality: Int,
    ) {
        val (w, h) = targetDims(streamSet, lastQuality)
        val safeUrl = MediaHostGuard.resolveSafeUrl(streamSet.currentVideo.url)
        val audioUrl = streamSet.currentAudio.url
        val fps = outputFps(streamSet.currentVideo.fps)

        expectedW = w
        expectedH = h
        eosReached = false
        errorMessage = ""
        parkFlag.set(false)

        // Build libvlc args
        val args = mutableListOf(
            "--no-video-title-show",
            "--no-snapshot-preview",
            "--quiet",
            "--no-keyboard-events",
            "--no-mouse-events",
            "--network-caching=300",
            "--file-caching=300",
            "--live-caching=600",
        )
        MediaHosts.refererFor(safeUrl)?.let { args.add("--http-referer=$it") }

        val fact = MediaPlayerFactory(args)
        factory = fact

        val mp = fact.mediaPlayers().newEmbeddedMediaPlayer()
        mediaPlayer = mp

        // Set up video callbacks
        val videoSurface = fact.videoSurfaces().newVideoSurface(
            videoBufferFormatCb,
            videoRenderCallback,
            true,
        )
        // Couldn't set video surface on a non-embedded player... but newEmbeddedMediaPlayer() returns EmbeddedMediaPlayer
        // Actually EmbeddedMediaPlayer HAS videoSurface() method
        mp.videoSurface().set(videoSurface)

        // Set up audio callbacks
        val audioPipeOut = PipedOutputStream()
        val audioPipeIn = PipedInputStream(audioPipeOut, 1024 * 1024)
        this.audioPipeOut = audioPipeOut
        this.audioPipeIn = audioPipeIn

        mp.audio().callback("S16N", AudioSink.SAMPLE_RATE, 2, audioCallback(mediaPlayer!!))

        // Event listener
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mp: VlcjMediaPlayer) {
                logger.debug("$debugLabel libvlc finished.")
                eosReached = true
            }

            override fun error(mp: VlcjMediaPlayer) {
                logger.error("$debugLabel libvlc error.")
                errorMessage = "libvlc error"
                eosReached = true
            }

            override fun playing(mp: VlcjMediaPlayer) {
                logger.debug("$debugLabel libvlc playing.")
            }

            override fun paused(mp: VlcjMediaPlayer) {
                logger.debug("$debugLabel libvlc paused.")
            }
        })

        // Start playback
        isPlaying = true
        mp.media().play(safeUrl)

        // Wait for first frame
        try {
            if (!videoFirstFrameLatch.await(10, TimeUnit.SECONDS)) {
                logger.error("$debugLabel libvlc first frame timeout")
                stop()
                throw IOException("libvlc first frame timeout")
            }
        } catch (_: InterruptedException) {
            stop()
            throw IOException("libvlc start interrupted")
        }

        // Seek if needed
        if (offsetNanos > 0) {
            mp.controls().setTime(offsetNanos / 1_000_000L)
        }

        // Start audio matching
        audio.start(streamSet.currentAudio.url, debugLabel, audioPipeIn, offsetNanos, clock)

        // EOS monitor thread
        val thread = daemon(
            { eosMonitor() },
            "MediaPlayer-eos",
        ).also { it.start() }
        eosThread = thread
    }

    /**
     * Starts video-only replay (no audio, no libvlc — just uses the existing surface).
     * Not supported in the initial libvlc port; returns false.
     */
    fun startReplayVideoOnly(
        @Suppress("UNUSED_PARAMETER") snapshot: ByteArray?,
        @Suppress("UNUSED_PARAMETER") resume: Long,
        @Suppress("UNUSED_PARAMETER") positionNanos: Long,
        @Suppress("UNUSED_PARAMETER") audioPcm: ByteArray?,
    ): Boolean = false

    /**
     * Attaches a live stream after a video-only replay.
     * Not supported in the initial libvlc port; returns false.
     */
    fun attachLiveAfterReplay(
        @Suppress("UNUSED_PARAMETER") streamSet: ActiveStreams,
        @Suppress("UNUSED_PARAMETER") liveOffsetNanos: Long,
        @Suppress("UNUSED_PARAMETER") lastQuality: Int,
    ): Boolean = false

    /**
     * Seeks to a new position.
     */
    fun beginSeek(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean {
        val mp = mediaPlayer ?: return false
        mp.controls().setTime(offsetNanos / 1_000_000L)
        logger.debug("$debugLabel libvlc seek to ${offsetNanos / 1_000_000} ms.")
        // The audio clock resync is handled by AudioSink
        return true
    }

    /**
     * Stops the session.
     */
    fun stop() {
        isPlaying = false
        parkFlag.set(false)
        eosReached = true
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            try {
                mp.controls().stop()
                mp.release()
            } catch (_: Exception) { }
        }
        val fact = factory
        factory = null
        if (fact != null) {
            try {
                fact.release()
            } catch (_: Exception) { }
        }
        audio.stop()
        runCatching { audioPipeOut?.close() }
        audioPipeOut = null
        audioPipeIn = null
        surface.clear()
    }

    /**
     * Cleans up all resources.
     */
    fun cleanup() {
        stop()
        surface.cleanup()
    }

    // ── Quality switch ────────────────────────────────────────────────────

    /**
     * Begins a quality switch. With libvlc, this is a hard switch: stop current,
     * start new. The caller must wait for the first frame before promoting.
     */
    fun beginQualitySwitch(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int) {
        // Stop current libvlc player
        stop()
        // Start new session with the new quality URL
        start(streamSet, offsetNanos, lastQuality)
    }

    fun promoteIncoming(): Boolean = true // No-op: hard switch already promoted

    // ── Audio track switch ────────────────────────────────────────────────

    fun beginAudioTrackSwitch(streamSet: ActiveStreams): Boolean {
        val mp = mediaPlayer ?: return false
        // libvlc track selection: iterate audio tracks and find the matching one
        val tracks = mp.audio().trackDescriptions()
        val targetUrl = streamSet.currentAudio.url
        // Find the audio track that matches the new URL
        // Since libvlc doesn't expose URLs per track, we rely on the track description
        val track = tracks?.firstOrNull { desc ->
            targetUrl.contains(desc.description(), ignoreCase = true)
        }
        if (track != null) {
            mp.audio().setTrack(track.id())
            return true
        }
        // Fall back: the caller will restart the audio half
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    fun setWarmAudioTracks(tracks: List<WarmTrack>) {
        // Not needed with libvlc: audio tracks are managed by the player
    }

    // ── Park / suspend / resume ───────────────────────────────────────────

    fun canPark(): Boolean = true

    private fun canHoldWarm(): Boolean = true

    fun suspend(allowExternalProcess: Boolean = false, retainBuffered: Boolean = false): Boolean {
        val mp = mediaPlayer ?: return false
        parkFlag.set(true)
        parkPositionNanos = clock.currentTime()
        mp.controls().setPause(true)
        audio.pause()
        return true
    }

    fun resume() {
        val mp = mediaPlayer ?: return
        parkFlag.set(false)
        mp.controls().setPause(false)
        audio.resume()
    }

    fun isParked(): Boolean = parkFlag.get()

    fun parkedPositionNanos(): Long? = parkPositionNanos.takeIf { parkFlag.get() && it >= 0 }

    // ── Audio helpers ─────────────────────────────────────────────────────

    fun audioSourceGone(): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun restartAudio(streamSet: ActiveStreams, offsetNanos: Long): Boolean = false

    fun captureAudioPcm(maxNanos: Long): ByteArray? {
        val maxBytes = (maxNanos / 1_000_000_000.0 * AudioSink.SAMPLE_RATE * 2 * 2).toInt()
        // Not supported in the initial libvlc port
        return null
    }

    fun captureVideoCacheSnapshot(): ByteArray? = null

    // ── Pacing / clock ────────────────────────────────────────────────────

    fun currentPacingNanos(): Long = clock.currentTime()

    fun activeBridgeEdgeNanos(): Long? = null

    // ── EOS monitor ───────────────────────────────────────────────────────

    private fun eosMonitor() {
        while (!terminated.get() && !eosReached) {
            // Park: pause libvlc playback
            if (parkFlag.get()) {
                while (parkFlag.get() && !terminated.get() && !eosReached) {
                    try { Thread.sleep(50) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
                }
                continue
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
        if (!terminated.get() && eosReached) {
            isPlaying = false
            onStreamEnd(errorMessage.ifEmpty { "End of stream" }, errorMessage.isEmpty())
        }
    }

    // ── Video callbacks ───────────────────────────────────────────────────

    private val videoFirstFrameLatch = CountDownLatch(1)
    private var videoFirstFrameFired = false
    @Volatile private var sourceW = 0
    @Volatile private var sourceH = 0
    private var i420Scratch: ByteBuffer? = null
    private var rgbScratch: ByteBuffer? = null
    private var popoutRgba: ByteBuffer? = null

    private val videoBufferFormatCb = object : BufferFormatCallback {
        override fun getBufferFormat(width: Int, height: Int): BufferFormat {
            sourceW = width
            sourceH = height
            if (MediaPlayer.DEBUG) logger.debug("$debugLabel libvlc source: ${width}x${height}")
            return BufferFormat("I420", width, height,
                intArrayOf(width, (width + 1) / 2, (width + 1) / 2),
                intArrayOf(height, (height + 1) / 2, (height + 1) / 2),
            )
        }
        override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
    }

    private val videoRenderCallback = RenderCallback { mp, buffers, format ->
        if (eosReached || buffers.isEmpty()) return@RenderCallback
        val w = sourceW
        val h = sourceH
        if (w <= 0 || h <= 0) return@RenderCallback

        val ySize = w * h
        val uvSize = ((w + 1) / 2) * ((h + 1) / 2)
        val totalSize = ySize + 2 * uvSize

        // Pack I420 planes into scratch buffer
        val i420 = i420Scratch?.takeIf { it.capacity() >= totalSize }?.also { it.clear() }
            ?: ByteBuffer.allocateDirect(totalSize).also { i420Scratch = it }
        if (buffers.size > 0) {
            val y = buffers[0].duplicate(); y.rewind()
            val n = minOf(ySize, y.remaining())
            for (i in 0 until n) i420.put(y.get())
            for (i in n until ySize) i420.put(16.toByte())
        } else { for (i in 0 until ySize) i420.put(16.toByte()) }
        if (buffers.size > 1) {
            val u = buffers[1].duplicate(); u.rewind()
            val n = minOf(uvSize, u.remaining())
            for (i in 0 until n) i420.put(u.get())
            for (i in n until uvSize) i420.put(128.toByte())
        } else { for (i in 0 until uvSize) i420.put(128.toByte()) }
        if (buffers.size > 2) {
            val v = buffers[2].duplicate(); v.rewind()
            val n = minOf(uvSize, v.remaining())
            for (i in 0 until n) i420.put(v.get())
            for (i in n until uvSize) i420.put(128.toByte())
        } else { for (i in 0 until uvSize) i420.put(128.toByte()) }
        i420.flip()

        // Allocate frame buffer
        val ew = expectedW
        val eh = expectedH
        if (ew <= 0 || eh <= 0) return@RenderCallback
        val frameSize = if (gpuYuvActive) {
            val c = ((ew + 1) / 2) * ((eh + 1) / 2)
            ew * eh + 2 * c
        } else ew * eh * 3

        var spare = surface.takeOrAllocate(frameSize)
        spare.clear()

        if (w == ew && h == eh) {
            if (gpuYuvActive) {
                i420.rewind()
                for (i in 0 until totalSize) spare.put(i420.get())
            } else {
                i420ToRgb24(i420, w, h, spare)
                applyBrightness(spare, frameSize, getBrightness())
            }
        } else {
            if (gpuYuvActive) {
                resizeI420(i420, w, h, spare, ew, eh)
            } else {
                val scratch = rgbScratch?.takeIf { it.capacity() >= w * h * 3 }?.also { it.clear() }
                    ?: ByteBuffer.allocateDirect(w * h * 3).also { rgbScratch = it }
                i420ToRgb24(i420, w, h, scratch)
                resizeRgb24(scratch, w, h, spare, ew, eh)
                applyBrightness(spare, frameSize, getBrightness())
            }
        }
        spare.flip()

        if (parkFlag.get()) return@RenderCallback

        // Popout sink
        feedSink(spare, ew, eh)

        // Publish to surface
        surface.publish(spare, frameSize)

        if (!videoFirstFrameFired) {
            videoFirstFrameFired = true
            videoFirstFrameLatch.countDown()
            if (MediaPlayer.DEBUG) logger.debug("$debugLabel First frame $ew x $eh (libvlc).")
        }
    }

    // ── Audio callback ────────────────────────────────────────────────────

    private fun audioCallback(player: EmbeddedMediaPlayer): AudioCallback = object : AudioCallback {
        override fun play(mp: VlcjMediaPlayer, samples: com.sun.jna.Pointer, sampleCount: Int, pts: Long) {
            val out = audioPipeOut ?: return
            if (sampleCount <= 0) return
            val byteCount = sampleCount * 4 // S16 stereo = 4 bytes per frame
            try {
                val bytes = samples.getByteArray(0, byteCount)
                out.write(bytes, 0, byteCount)
            } catch (e: Exception) {
                if (MediaPlayer.DEBUG) logger.warn("$debugLabel [audio] pipe write: ${e.message}")
            }
        }
        override fun pause(mp: VlcjMediaPlayer, pts: Long) {}
        override fun resume(mp: VlcjMediaPlayer, pts: Long) {}
        override fun flush(mp: VlcjMediaPlayer, pts: Long) {}
        override fun drain(mp: VlcjMediaPlayer) {}
        override fun setVolume(volume: Float, mute: Boolean) {}
    }

    // ── Frame conversion helpers ──────────────────────────────────────────

    private fun i420ToRgb24(i420: ByteBuffer, w: Int, h: Int, rgb: ByteBuffer) {
        val ySize = w * h
        val uvSize = ((w + 1) / 2) * ((h + 1) / 2)
        i420.rewind()
        for (row in 0 until h) {
            for (col in 0 until w) {
                val y = i420.get(row * w + col).toInt() and 0xFF
                val u = i420.get(ySize + (row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF
                val v = i420.get(ySize + uvSize + (row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF
                val r = (y + 1.402 * (v - 128)).toInt().coerceIn(0, 255)
                val g = (y - 0.344 * (u - 128) - 0.714 * (v - 128)).toInt().coerceIn(0, 255)
                val b = (y + 1.772 * (u - 128)).toInt().coerceIn(0, 255)
                rgb.put(r.toByte()); rgb.put(g.toByte()); rgb.put(b.toByte())
            }
        }
        i420.rewind()
    }

    private fun resizeI420(src: ByteBuffer, srcW: Int, srcH: Int, dst: ByteBuffer, dstW: Int, dstH: Int) {
        val srcYSize = srcW * srcH
        val srcUVSize = ((srcW + 1) / 2) * ((srcH + 1) / 2)
        src.rewind()
        for (dy in 0 until dstH) {
            val sy = (dy * srcH / dstH).coerceIn(0, srcH - 1)
            for (dx in 0 until dstW) { dst.put(src.get(sy * srcW + (dx * srcW / dstW).coerceIn(0, srcW - 1))) }
        }
        for (dy in 0 until (dstH + 1) / 2) {
            val sy = (dy * ((srcH + 1) / 2) / ((dstH + 1) / 2)).coerceIn(0, ((srcH + 1) / 2) - 1)
            for (dx in 0 until (dstW + 1) / 2) { dst.put(src.get(srcYSize + sy * ((srcW + 1) / 2) + (dx * ((srcW + 1) / 2) / ((dstW + 1) / 2)).coerceIn(0, ((srcW + 1) / 2) - 1))) }
        }
        for (dy in 0 until (dstH + 1) / 2) {
            val sy = (dy * ((srcH + 1) / 2) / ((dstH + 1) / 2)).coerceIn(0, ((srcH + 1) / 2) - 1)
            for (dx in 0 until (dstW + 1) / 2) { dst.put(src.get(srcYSize + srcUVSize + sy * ((srcW + 1) / 2) + (dx * ((srcW + 1) / 2) / ((dstW + 1) / 2)).coerceIn(0, ((srcW + 1) / 2) - 1))) }
        }
        src.rewind()
    }

    private fun resizeRgb24(src: ByteBuffer, srcW: Int, srcH: Int, dst: ByteBuffer, dstW: Int, dstH: Int) {
        src.rewind()
        for (dy in 0 until dstH) {
            val sy = (dy * srcH / dstH).coerceIn(0, srcH - 1)
            for (dx in 0 until dstW) {
                val sx = (dx * srcW / dstW).coerceIn(0, srcW - 1)
                val p = (sy * srcW + sx) * 3
                dst.put(src.get(p)); dst.put(src.get(p + 1)); dst.put(src.get(p + 2))
            }
        }
        src.rewind()
    }

    private fun applyBrightness(buf: ByteBuffer, size: Int, brightness: Double) {
        val factor = brightness.coerceIn(0.0, 2.0)
        if (factor == 1.0) return
        val savedPos = buf.position()
        val savedLim = buf.limit()
        buf.flip()
        for (i in 0 until size) {
            val v = ((buf.get(i).toInt() and 0xFF) * factor).toInt().coerceIn(0, 255)
            buf.put(i, v.toByte())
        }
        buf.limit(savedLim)
        buf.position(savedPos)
    }

    private fun feedSink(buf: ByteBuffer, w: Int, h: Int) {
        val sink = popoutSink ?: previewSink ?: return
        if (gpuYuvActive) {
            val rgbaSize = w * h * 4
            val rgba = popoutRgba?.takeIf { it.capacity() >= rgbaSize }?.also { it.clear() }
                ?: ByteBuffer.allocateDirect(rgbaSize).also { popoutRgba = it }
            // I420 → RGBA
            val ySize = w * h
            val uvSize = ((w + 1) / 2) * ((h + 1) / 2)
            buf.rewind()
            for (row in 0 until h) {
                for (col in 0 until w) {
                    val y = buf.get(row * w + col).toInt() and 0xFF
                    val u = buf.get(ySize + (row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF
                    val v = buf.get(ySize + uvSize + (row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF
                    rgba.put(((y + 1.402 * (v - 128)).toInt().coerceIn(0, 255)).toByte())
                    rgba.put(((y - 0.344 * (u - 128) - 0.714 * (v - 128)).toInt().coerceIn(0, 255)).toByte())
                    rgba.put(((y + 1.772 * (u - 128)).toInt().coerceIn(0, 255)).toByte())
                    rgba.put(0xFF.toByte())
                }
            }
            buf.rewind()
            rgba.flip()
            sink(rgba, w, h, FramePixelFormat.RGBA32)
        } else {
            sink(buf, w, h, FramePixelFormat.RGB24)
        }
    }

    // ── Target dimensions ─────────────────────────────────────────────────

    private fun targetDims(streamSet: ActiveStreams?, lastQuality: Int = 0): Pair<Int, Int> {
        val (tw, th) = getTextureSize()
        if (tw > 0 && th > 0) return tw to th
        val q = when {
            lastQuality > 0 -> lastQuality
            streamSet != null -> MediaStreamSelector.parseQuality(streamSet.currentVideo)
            else -> 0
        }
        if (q <= 0) return 854 to 480
        return MediaStreamSelector.qualityToDims(q).let { it[0] to it[1] }
    }

    /** Frame rate assumption for sources without a valid FPS. */
    companion object {
        private const val REPLAY_FPS = 30.0
        private fun outputFps(sourceFps: Double?): Double =
            sourceFps?.takeIf { it.isFinite() && it > 1.0 && it <= 240.0 } ?: REPLAY_FPS
    }
}

/**
 * Minimal AudioSink for the libvlc PCM pipe — reads S16 stereo PCM from a
 * [PipedInputStream] and writes it to a [SourceDataLine] for actual audio output.
 * libvlc handles A/V sync internally; this sink just plays the PCM.
 */
internal class AudioSink(private val debugLabel: String) {
    companion object {
        const val SAMPLE_RATE = 44100
        const val BYTES_PER_FRAME = 4 // S16 stereo
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 * 2 / 20 // ~0.05s
    }

    private val logger = LoggerFactory.getLogger("DreamDisplaysX/AudioSink")
    private var stream: java.io.InputStream? = null
    private var thread: Thread? = null
    private var line: javax.sound.sampled.SourceDataLine? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    private var readBuffer = ByteArray(CHUNK_BYTES)

    fun start(url: String, debugLabel: String, pipeIn: java.io.InputStream, offsetNanos: Long, clock: PlaybackClock) {
        stop()
        stream = pipeIn
        running = true

        // Open the audio line
        val audioFormat = javax.sound.sampled.AudioFormat(
            SAMPLE_RATE.toFloat(), 16, 2, true, false // S16LE stereo
        )
        val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, audioFormat)
        try {
            val audioLine = javax.sound.sampled.AudioSystem.getLine(info) as javax.sound.sampled.SourceDataLine
            audioLine.open(audioFormat, CHUNK_BYTES * 8)
            audioLine.start()
            line = audioLine
        } catch (e: Exception) {
            logger.warn("$debugLabel Audio line unavailable: ${e.message}")
        }

        val st = pipeIn
        thread = daemon({
            try {
                while (running && !Thread.currentThread().isInterrupted) {
                    if (paused) {
                        line?.stop()
                        try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                        continue
                    }
                    if (line != null && !line!!.isRunning) line!!.start()
                    val n = st.read(readBuffer)
                    if (n < 0) break
                    if (n > 0) {
                        line?.write(readBuffer, 0, n)
                    }
                }
            } catch (e: Exception) {
                if (running) logger.warn("$debugLabel AudioSink: ${e.message}")
            } finally {
                runCatching { line?.drain(); line?.stop(); line?.close() }
                line = null
            }
        }, "MediaPlayer-audio").also { it.start() }
    }

    fun stop() {
        running = false
        paused = false
        thread?.interrupt()
        thread = null
        stream = null
        runCatching { line?.drain(); line?.stop(); line?.close() }
        line = null
    }

    fun pause() {
        paused = true
        thread?.interrupt()
    }

    fun resume() {
        paused = false
        thread?.interrupt()
    }

    fun setVolume(volume: Double) {
        val gain = (volume * 6.0 - 80.0).coerceIn(-80.0, 6.0) // dB
        try {
            line?.let {
                if (it.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) {
                    (it.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN) as javax.sound.sampled.FloatControl).value = gain.toFloat()
                }
            }
        } catch (_: Exception) { }
    }

    fun requestResync() {
        // No-op: libvlc handles A/V sync
    }
}