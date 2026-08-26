package com.dreamdisplayx.media.player.managers

import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.model.StretchMode
import com.dreamdisplayx.api.media.player.FrameUploaderFactory
import com.dreamdisplayx.api.media.player.GpuTextureRef
import com.dreamdisplayx.api.media.player.RenderExecutor
import com.dreamdisplayx.api.media.audio.service.AudioDspStage
import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.media.player.events.PlayerEvents
import com.dreamdisplayx.media.player.pipeline.FrameSurface
import com.dreamdisplayx.media.player.pipeline.PlaybackClock
import com.dreamdisplayx.media.player.stream.ActiveStreams
import com.dreamdisplayx.media.player.stream.MediaStreamSelector
import com.dreamdisplayx.media.player.util.LibVlcMediaOptions
import com.dreamdisplayx.media.runtime.security.MediaHostGuard
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * LibVLC session manager rebuilt to mirror the VideoPlayer mod's low-level libvlc model:
 *
 *  - A single libvlc instance and a single media player are created ONCE for the whole
 *    session-manager lifetime and never rebuilt. Switching videos only replaces the media
 *    on the existing player (`set_media` + `play`), so no JNA callback trampoline is ever
 *    dropped while libvlc's async teardown could still touch it ("callback object has been
 *    garbage collected" spam is gone by construction).
 *  - Video is delivered through low-level lock/unlock/display/setup/cleanup callbacks into
 *    a triple-buffered pool (the VideoPlayer `TextureRenderCallback` model). Every callback
 *    is held by a strong field reference for the life of the manager.
 *  - All libvlc control operations run on a single control executor, serialised.
 *  - Playback events (playing/end-reached/error) are delivered through a low-level event
 *    listener, exactly like VideoPlayer.
 *  - Audio is left to libvlc's own default output (the `:input-slave` audio stream is merged
 *    into the same player), which removes the fragile Java PCM pipe that caused "audio fades
 *    after a few seconds". Volume is still controlled via libvlc.
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

    /** Invoked once an in-flight audio track switch settles. */
    private val onAudioTrackSwitchSettled: () -> Unit = {},

    /** Runs render-thread (GL) cleanup work. */
    private val renderExecutor: RenderExecutor,

    /** Creates per-channel GPU frame uploaders. */
    private val uploaderFactory: FrameUploaderFactory,

    /** Whether the GPU-side planar (I420) render path is active. */
    private val gpuYuvActive: Boolean,

    /** Whether hardware-accelerated decoding is enabled by config. */
    private val useHwAccel: Boolean,

    /** Per-display acoustics DSP stage (unused with libvlc default audio output). */
    audioStage: AudioDspStage? = null,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/LibVlcSession")

    init {
        // Mirror the config's hw-accel preference onto the shared libvlc instance before it is
        // created (the singleton instance is built on first use, so this must be set up front).
        LibVlc.useHwAccel = useHwAccel
    }

    /** Media-player events we attach to (VideoPlayer's set). */
    private val MEDIA_PLAYER_EVENTS = intArrayOf(
        LibVlc.LIBVLC_MEDIA_PLAYER_PLAYING,
        LibVlc.LIBVLC_MEDIA_PLAYER_PAUSED,
        LibVlc.LIBVLC_MEDIA_PLAYER_STOPPED,
        LibVlc.LIBVLC_MEDIA_PLAYER_END_REACHED,
        LibVlc.LIBVLC_MEDIA_PLAYER_ENCOUNTERED_ERROR,
        LibVlc.LIBVLC_MEDIA_PLAYER_TIME_CHANGED,
        LibVlc.LIBVLC_MEDIA_PLAYER_LENGTH_CHANGED,
    )

    // ── libvlc singleton state (VideoPlayer model) ──────────────────────────

    @Volatile
    private var mediaPlayer: Pointer? = null

    private val released = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    /** Serialises every libvlc control call, mirroring VideoPlayer's control executor. */
    private val controlExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> daemonThread(r, "MediaPlayer-vlc-ctrl") }

    // ── Frame surface ───────────────────────────────────────────────────────

    private val surface = FrameSurface(debugLabel, uploaderFactory, FramePixelFormat.RGB24)

    @Volatile
    var expectedW = 0; private set

    @Volatile
    var expectedH = 0; private set

    private val noFrames = AtomicLong(0)

    var isPlaying = false; private set

    val lastFrameNanos: AtomicLong get() = noFrames

    // ── EOS / error signals ─────────────────────────────────────────────────

    @Volatile
    private var eosReached = false

    @Volatile
    private var errorMessage = ""

    /** Guards [onStreamEnd] so it fires exactly once per session. */
    private val eosFired = AtomicBoolean(false)

    // ── Park state ──────────────────────────────────────────────────────────

    private val parkFlag = AtomicBoolean(false)
    private var parkPositionNanos = 0L

    // ── Popout / preview sinks ──────────────────────────────────────────────

    private var popoutSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null
    private var previewSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = popoutSink
        set(value) { popoutSink = value }

    var previewFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = previewSink
        set(value) { previewSink = value }

    // ── Low-level callbacks (held strongly for life) ───────────────────────

    private val eventCallback = LibVlc.EventCallback { event, _ -> handleEvent(event) }
    /** Triple-buffered video frame pool + callbacks (VideoPlayer TextureRenderCallback model). */
    private val videoFrames = TextureRenderCallback()

    private val videoFormatCallback = LibVlc.VideoFormatCallback { opaque, chroma, width, height, pitches, lines ->
        videoFrames.setup(opaque, chroma, width, height, pitches, lines)
    }

    private val videoCleanupCallback = LibVlc.VideoCleanupCallback { opaque -> videoFrames.cleanup(opaque) }

    private val videoLockCallback = LibVlc.VideoLockCallback { opaque, planes -> videoFrames.lock(opaque, planes) }

    private val videoUnlockCallback = LibVlc.VideoUnlockCallback { opaque, picture, planes ->
        videoFrames.unlock(opaque, picture, planes)
    }

    private val videoDisplayCallback = LibVlc.VideoDisplayCallback { opaque, picture ->
        videoFrames.display(opaque, picture)
    }

    // ── Event handling ──────────────────────────────────────────────────────

    private fun handleEvent(event: Pointer?) {
        if (event == null || released.get() || stopped.get()) return
        val type = event.getInt(0)
        when (type) {
            LibVlc.LIBVLC_MEDIA_PLAYER_PLAYING -> {
                stopped.set(false)
                isPlaying = true
                if (MediaPlayer.DEBUG) logger.debug("$debugLabel libvlc playing.")
            }
            LibVlc.LIBVLC_MEDIA_PLAYER_PAUSED -> {
                if (MediaPlayer.DEBUG) logger.debug("$debugLabel libvlc paused.")
            }
            LibVlc.LIBVLC_MEDIA_PLAYER_END_REACHED -> {
                logger.debug("$debugLabel libvlc end reached.")
                eosReached = true
                fireStreamEnd()
            }
            LibVlc.LIBVLC_MEDIA_PLAYER_ENCOUNTERED_ERROR -> {
                logger.error("$debugLabel libvlc error: ${LibVlc.errmsg()}")
                errorMessage = "libvlc error"
                eosReached = true
                fireStreamEnd()
            }
            else -> {}
        }
    }

    /** Fires [onStreamEnd] exactly once per session. */
    private fun fireStreamEnd() {
        if (eosFired.compareAndSet(false, true) && !terminated.get()) {
            isPlaying = false
            onStreamEnd(errorMessage.ifEmpty { "End of stream" }, errorMessage.isEmpty())
        }
    }

    // ── Volume ──────────────────────────────────────────────────────────────

    fun setVolume(volume: Double) {
        val v = volume.coerceIn(0.0, 1.0)
        submit {
            val mp = mediaPlayer ?: return@submit
            runCatching { LibVlc.lib.libvlc_audio_set_volume(mp, (v * 100).toInt()) }
        }
    }

    // ── Frame pipe proxy ────────────────────────────────────────────────────

    fun textureFilled(): Boolean = surface.textureFilled()

    fun updateFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean =
        surface.updateFrame(texture, w, h, expectedW, expectedH)

    fun updateFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        surface.updateFramePlanar(y, u, v, w, h, expectedW, expectedH)

    fun hasIncoming(): Boolean = false // hard quality switch, no parallel channel

    fun updateIncomingFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean = false

    fun updateIncomingFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean = false

    fun clearFrame() = surface.clear()

    // ── Session lifecycle ───────────────────────────────────────────────────

    /**
     * Starts (or restarts) playback of [streamSet] on the single, never-rebuilt player.
     * Video is delivered via the low-level callbacks; audio is left to libvlc's default output.
     */
    fun start(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int) {
        val (w, h) = targetDims(streamSet, lastQuality)
        val safeUrl = MediaHostGuard.resolveSafeUrl(streamSet.currentVideo.url)

        // A restart replaces the media on the SAME player; never recreate the player.
        stopped.set(false)
        eosReached = false
        errorMessage = ""
        eosFired.set(false)
        parkFlag.set(false)
        expectedW = w
        expectedH = h
        firstFrameFired = false
        firstFrameLatch = CountDownLatch(1)

        // Build the media options (UA + referer + input-slave for DASH audio + hw accel).
        val mediaOptions = mutableListOf(*LibVlcMediaOptions.forUrl(safeUrl))
        val audioUrl = streamSet.currentAudio.url
        if (audioUrl.isNotBlank() && !audioUrl.equals(safeUrl, ignoreCase = true)) {
            mediaOptions.add(":input-slave=$audioUrl")
        }
        if (useHwAccel) mediaOptions.add(":avcodec-hw=any")

        submit {
            val mp = player()
            if (mp == null) {
                errorMessage = "libvlc player unavailable"
                eosReached = true
                return@submit
            }
            try {
                val media = LibVlc.createMedia(safeUrl, mediaOptions.toTypedArray())
                LibVlc.lib.libvlc_media_player_set_media(mp, media)
                LibVlc.lib.libvlc_media_release(media) // the player holds its own reference
                LibVlc.lib.libvlc_media_player_play(mp)
                isPlaying = true
            } catch (t: Throwable) {
                logger.error("$debugLabel failed to start libvlc media: ${t.message}")
                errorMessage = t.message ?: "libvlc start failed"
                eosReached = true
            }
        }

        // Seek if needed (after media is set; performed on the control executor).
        if (offsetNanos > 0) {
            submit {
                val mp = mediaPlayer ?: return@submit
                runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, offsetNanos / 1_000L) } // microseconds
            }
        }

        // Wait for the first frame so the caller can rely on frames being delivered (with timeout).
        // The control-executor submit is asynchronous, so unconditionally await the latch.
        try {
            if (!firstFrameLatch.await(10, TimeUnit.SECONDS)) {
                logger.error("$debugLabel libvlc first frame timeout")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Returns the single libvlc media player, creating it on first use and never rebuilding it.
     */
    private fun player(): Pointer? {
        val existing = mediaPlayer
        if (existing != null) return existing
        return try {
            LibVlc.ensureLoaded()
            val lib = LibVlc.lib
            val mp = lib.libvlc_media_player_new(LibVlc.libvlcInstance)
            if (mp == null) {
                logger.error("$debugLabel libvlc_media_player_new returned null: ${LibVlc.errmsg()}")
                return null
            }
            // Video callbacks (once; held strongly).
            lib.libvlc_video_set_format_callbacks(mp, videoFormatCallback, videoCleanupCallback)
            lib.libvlc_video_set_callbacks(mp, videoLockCallback, videoUnlockCallback, videoDisplayCallback, null)
            // Events (once).
            val em = lib.libvlc_media_player_event_manager(mp)
            if (em != null) {
                for (e in MEDIA_PLAYER_EVENTS) {
                    lib.libvlc_event_attach(em, e, eventCallback, null)
                }
            }
            mediaPlayer = mp
            if (MediaPlayer.DEBUG) logger.debug("$debugLabel created single libvlc media player.")
            mp
        } catch (t: Throwable) {
            logger.error("$debugLabel failed to create libvlc media player: ${t.message}")
            null
        }
    }

    /**
     * Starts video-only replay (not supported by libvlc port; returns false).
     */
    fun startReplayVideoOnly(
        @Suppress("UNUSED_PARAMETER") snapshot: ByteArray?,
        @Suppress("UNUSED_PARAMETER") resume: Long,
        @Suppress("UNUSED_PARAMETER") positionNanos: Long,
        @Suppress("UNUSED_PARAMETER") audioPcm: ByteArray?,
    ): Boolean = false

    /**
     * Attaches a live stream after a video-only replay (not supported; returns false).
     */
    fun attachLiveAfterReplay(
        @Suppress("UNUSED_PARAMETER") streamSet: ActiveStreams,
        @Suppress("UNUSED_PARAMETER") liveOffsetNanos: Long,
        @Suppress("UNUSED_PARAMETER") lastQuality: Int,
    ): Boolean = false

    /** Seeks the single player to [offsetNanos]. */
    fun beginSeek(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean {
        submit {
            val mp = mediaPlayer ?: return@submit
            runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, offsetNanos / 1_000L) } // microseconds
        }
        logger.debug("$debugLabel libvlc seek to ${offsetNanos / 1_000_000} ms.")
        return true
    }

    /**
     * Stops the session. The single player is kept alive (never released) so its JNA callbacks
     * stay valid across restarts. Call [cleanup] to release the player and all resources.
     */
    fun stop() {
        isPlaying = false
        parkFlag.set(false)
        eosReached = true
        stopped.set(true)
        submit {
            val mp = mediaPlayer
            if (mp != null) {
                runCatching { LibVlc.lib.libvlc_media_player_stop(mp) }
            }
        }
        surface.clear()
    }

    /**
     * Releases the single player and all resources. GPU teardown is deferred to the render thread.
     */
    fun cleanup() {
        stop()
        released.set(true)
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            try {
                LibVlc.lib.libvlc_media_player_release(mp)
            } catch (_: Exception) { }
        }
        runCatching { controlExecutor.shutdownNow() }
        renderExecutor.execute { surface.cleanup() }
    }

    // ── Quality switch ──────────────────────────────────────────────────────

    /** Hard quality switch: stop current media, start new one on the same player. */
    fun beginQualitySwitch(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int) {
        stop()
        start(streamSet, offsetNanos, lastQuality)
    }

    fun promoteIncoming(): Boolean = true // hard switch already promoted

    // ── Audio track switch ──────────────────────────────────────────────────

    fun beginAudioTrackSwitch(streamSet: ActiveStreams): Boolean {
        // libvlc manages audio tracks internally; a restart of the same player re-reads the
        // stream and picks up the new audio URL. No-op here.
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    fun setWarmAudioTracks(tracks: List<WarmTrack>) {
        // Not needed with libvlc.
    }

    // ── Park / suspend / resume ─────────────────────────────────────────────

    fun canPark(): Boolean = true

    private fun canHoldWarm(): Boolean = true

    fun suspend(allowExternalProcess: Boolean = false, retainBuffered: Boolean = false): Boolean {
        val mp = mediaPlayer ?: return false
        parkFlag.set(true)
        parkPositionNanos = clock.currentTime()
        submit { runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 1) } }
        return true
    }

    fun resume() {
        val mp = mediaPlayer ?: return
        parkFlag.set(false)
        submit { runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 0) } }
    }

    fun isParked(): Boolean = parkFlag.get()

    fun parkedPositionNanos(): Long? = parkPositionNanos.takeIf { parkFlag.get() && it >= 0 }

    // ── Audio helpers (unused with libvlc default output) ───────────────────

    @Suppress("UNUSED_PARAMETER")
    fun restartAudio(streamSet: ActiveStreams, offsetNanos: Long): Boolean = false

    fun captureAudioPcm(maxNanos: Long): ByteArray? = null

    fun captureVideoCacheSnapshot(): ByteArray? = null

    // ── Pacing / clock ──────────────────────────────────────────────────────

    fun currentPacingNanos(): Long = clock.currentTime()

    fun activeBridgeEdgeNanos(): Long? = null

    // ── Control executor ─────────────────────────────────────────────────────

    private fun submit(r: () -> Unit) {
        if (released.get()) return
        try {
            controlExecutor.execute { if (!released.get()) r() }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun daemonThread(r: Runnable, name: String): Thread =
        Thread(r, name).also { it.isDaemon = true }

    // ── Target dimensions ────────────────────────────────────────────────────

    private fun targetDims(streamSet: ActiveStreams?, lastQuality: Int = 0): Pair<Int, Int> {
        val q = when {
            lastQuality > 0 -> lastQuality
            streamSet != null -> MediaStreamSelector.parseQuality(streamSet.currentVideo)
            else -> 0
        }
        if (q <= 0) return 854 to 480
        return MediaStreamSelector.qualityToDims(q).let { it[0] to it[1] }
    }

    /**
     * Triple-buffered video frame pool driven by the low-level libvlc video callbacks
     * (VideoPlayer's TextureRenderCallback model). libvlc writes into one of three direct
     * buffers; `display` marks the newest; the render thread copies it into the FrameSurface
     * and returns the buffer to the pool.
     */
    private inner class TextureRenderCallback {
        private val BUFFER_COUNT = 3
        private val buffers = arrayOfNulls<ByteBuffer>(BUFFER_COUNT)
        private val pointers = arrayOfNulls<Pointer>(BUFFER_COUNT)
        private val inUse = BooleanArray(BUFFER_COUNT)

        private var dropBuffer: ByteBuffer? = null
        private var dropPointer: Pointer? = null
        private var frameWidth = 1
        private var frameHeight = 1
        private var bufferSize = 4
        private var nextWrite = 0
        private var writing = -1
        private var latest = -1

        @Synchronized
        fun setup(opaque: com.sun.jna.ptr.PointerByReference?, chroma: Pointer?, width: Pointer?, height: Pointer?,
                  pitches: Pointer?, lines: Pointer?): Int {
            if (width == null || height == null || chroma == null || pitches == null || lines == null) return 0
            val w = width.getInt(0)
            val h = height.getInt(0)
            if (w <= 0 || h <= 0 || w > 16384 || h > 16384) {
                logger.warn("$debugLabel rejected libvlc frame dimensions ${w}x$h")
                return 0
            }
            // I420 chroma: Y, then U (w/2 x h/2), then V.
            chroma.write(0, I420, 0, I420.size)
            pitches.setInt(0, w)
            pitches.setInt(4, (w + 1) / 2)
            pitches.setInt(8, (w + 1) / 2)
            lines.setInt(0, h)
            lines.setInt(4, (h + 1) / 2)
            lines.setInt(8, (h + 1) / 2)
            if (frameWidth != w || frameHeight != h) resize(w, h)
            return 1
        }

        @Synchronized
        fun cleanup(opaque: Pointer?) {
            clear()
        }

        @Synchronized
        fun lock(opaque: Pointer?, planes: Pointer?): Pointer? {
            if (planes == null) return DROP_TOKEN
            if (buffers[0] == null || bufferSize <= 0) {
                ensureDropBuffer()
                planes.setPointer(0, dropPointer!!)
                return DROP_TOKEN
            }
            val index = acquireWriteBuffer()
            if (index < 0) {
                ensureDropBuffer()
                planes.setPointer(0, dropPointer!!)
                return DROP_TOKEN
            }
            writing = index
            // I420: three planes.
            val y = pointers[index]!!
            val total = frameWidth * frameHeight
            val uv = (frameWidth + 1) / 2 * ((frameHeight + 1) / 2)
            planes.setPointer(0, y)
            planes.setPointer(Native.POINTER_SIZE.toLong(), y.share(total.toLong()))
            planes.setPointer((2 * Native.POINTER_SIZE).toLong(), y.share((total + uv).toLong()))
            return Pointer.createConstant((index + 1).toLong())
        }

        @Synchronized
        fun unlock(opaque: Pointer?, picture: Pointer?, planes: Pointer?) {
        }

        @Synchronized
        fun display(opaque: Pointer?, picture: Pointer?) {
            if (picture == null) return
            val token = Pointer.nativeValue(picture)
            if (token == DROP_TOKEN_VALUE) return
            val index = (token - 1).toInt()
            if (index < 0 || index >= BUFFER_COUNT) return
            if (writing == index) writing = -1
            if (released.get() || stopped.get() || buffers[index] == null) return
            latest = index
            // Publish the newest frame into the surface for the render thread.
            publishFrame(index)
        }

        private fun publishFrame(index: Int) {
            val buf = buffers[index] ?: return
            val ew = expectedW
            val eh = expectedH
            if (ew <= 0 || eh <= 0) return
            try {
                val i420 = buf.duplicate().order(ByteOrder.nativeOrder()); i420.rewind()
                val total = frameWidth * frameHeight + 2 * ((frameWidth + 1) / 2) * ((frameHeight + 1) / 2)
                val frameSize = if (gpuYuvActive) {
                    val c = ((ew + 1) / 2) * ((eh + 1) / 2)
                    ew * eh + 2 * c
                } else ew * eh * 3

                var spare = surface.takeOrAllocate(frameSize)
                spare.clear()

                if (frameWidth == ew && frameHeight == eh) {
                    if (gpuYuvActive) {
                        for (i in 0 until total) spare.put(i420.get())
                    } else {
                        i420ToRgb24(i420, frameWidth, frameHeight, spare)
                        applyBrightness(spare, frameSize, getBrightness())
                    }
                } else {
                    if (gpuYuvActive) {
                        resizeI420(i420, frameWidth, frameHeight, spare, ew, eh)
                    } else {
                        val scratch = rgbScratch?.takeIf { it.capacity() >= frameWidth * frameHeight * 3 }?.also { it.clear() }
                            ?: ByteBuffer.allocateDirect(frameWidth * frameHeight * 3).also { rgbScratch = it }
                        i420ToRgb24(i420, frameWidth, frameHeight, scratch)
                        resizeRgb24(scratch, frameWidth, frameHeight, spare, ew, eh)
                        applyBrightness(spare, frameSize, getBrightness())
                    }
                }
                spare.flip()

                if (parkFlag.get()) return

                // Popout / preview sinks (RGB frame always converted above for the non-planar path).
                val sink = popoutSink ?: previewSink
                if (sink != null) sink(spare, ew, eh, FramePixelFormat.RGB24)

                // Publish to the GPU surface for the render thread.
                surface.publish(spare, frameSize)
                noFrames.set(System.nanoTime())

                if (!firstFrameFired) {
                    firstFrameFired = true
                    firstFrameLatch.countDown()
                    if (MediaPlayer.DEBUG) logger.debug("$debugLabel first frame $ew x $eh (libvlc).")
                }
            } catch (t: Throwable) {
                if (MediaPlayer.DEBUG) logger.warn("$debugLabel frame publish: ${t.message}")
            }
        }

        private fun matches(w: Int, h: Int) = buffers[0] != null && frameWidth == w && frameHeight == h

        private fun resize(w: Int, h: Int) {
            frameWidth = w
            frameHeight = h
            bufferSize = w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2)
            for (i in 0 until BUFFER_COUNT) {
                buffers[i] = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder())
                pointers[i] = com.sun.jna.Native.getDirectBufferPointer(buffers[i]!!)
                inUse[i] = false
            }
            dropBuffer = ByteBuffer.allocateDirect(bufferSize.coerceAtLeast(4)).order(ByteOrder.nativeOrder())
            dropPointer = com.sun.jna.Native.getDirectBufferPointer(dropBuffer!!)
            nextWrite = 0
            writing = -1
            latest = -1
        }

        @Synchronized
        private fun clear() {
            for (i in 0 until BUFFER_COUNT) {
                buffers[i] = null
                pointers[i] = null
                inUse[i] = false
            }
            dropBuffer = null
            dropPointer = null
            bufferSize = 0
            nextWrite = 0
            writing = -1
            latest = -1
        }

        private fun acquireWriteBuffer(): Int {
            for (i in 0 until BUFFER_COUNT) {
                val index = (nextWrite + i) % BUFFER_COUNT
                if (!inUse[index] && index != writing) {
                    nextWrite = (index + 1) % BUFFER_COUNT
                    return index
                }
            }
            return -1
        }

        private fun ensureDropBuffer() {
            if (dropBuffer != null && dropPointer != null && dropBuffer!!.capacity() == bufferSize.coerceAtLeast(4)) return
            val size = bufferSize.coerceAtLeast(4)
            dropBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            dropPointer = com.sun.jna.Native.getDirectBufferPointer(dropBuffer!!)
        }
    }

    // ── First-frame latch ───────────────────────────────────────────────────

    private var firstFrameLatch = CountDownLatch(1)
    private var firstFrameFired = false

    // ── Frame conversion helpers ────────────────────────────────────────────

    private var rgbScratch: ByteBuffer? = null

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
        buf.flip()
        for (i in 0 until size) {
            val v = ((buf.get(i).toInt() and 0xFF) * factor).toInt().coerceIn(0, 255)
            buf.put(i, v.toByte())
        }
    }

    companion object {
        private const val LIBVLC_MEDIA_PLAYER_LENGTH_CHANGED = 0x111
        private const val REPLAY_FPS = 30.0
        private val I420 = byteArrayOf('I'.code.toByte(), '4'.code.toByte(), '2'.code.toByte(), '0'.code.toByte())
        private val DROP_TOKEN = Pointer.createConstant(0x7FFFFFFFL)
        private val DROP_TOKEN_VALUE = 0x7FFFFFFFL
        private fun outputFps(sourceFps: Double?): Double =
            sourceFps?.takeIf { it.isFinite() && it > 1.0 && it <= 240.0 } ?: REPLAY_FPS
    }
}
