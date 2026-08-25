@file:Suppress("Since15")

package com.dreamdisplayx.media.player.pipeline

import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.player.FrameUploaderFactory
import com.dreamdisplayx.api.media.player.GpuTextureRef
import com.dreamdisplayx.api.security.policy.MediaHosts
import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.media.player.util.daemon
import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * LibVLC (vlcj) based video frame pipe — replaces [JavaCppVideoPipe].
 * Uses libvlc's video callbacks to receive decoded frames in I420 format,
 * feeds them into the shared [FrameSurface] / [FramePrebuffer] pipeline.
 *
 * LibVLC handles demuxing, decoding, and hardware acceleration internally;
 * this pipe only receives the native-resolution frames and paces them into
 * the renderer.
 */
internal class LibVlcVideoPipe(
    private val debugLabel: String,
    uploaderFactory: FrameUploaderFactory,
    /** True when frames stay as raw I420 planes; YUV→RGB runs on the GPU. */
    private val planarOutput: Boolean,
) : FramePipe {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/LibVlcVideoPipe")

    companion object {
        private const val PARK_POLL_MS = 2L
        /** libvlc options shared across all instances. */
        private val SHARED_LIBVLC_ARGS = listOf(
            "--no-video-title-show",
            "--no-snapshot-preview",
            "--quiet",
            "--no-keyboard-events",
            "--no-mouse-events",
            "--network-caching=300",
            "--file-caching=300",
            "--live-caching=600",
            "--clock-synchro=0",
            "--no-audio",  // video pipe only, no audio output
        )
    }

    // ── FramePipe state ───────────────────────────────────────────────────

    /** Updated by the control thread on every frame; used by the watchdog to detect stalls. */
    override val lastFrameReceivedNanos = AtomicLong(0)

    @Volatile
    private var rawPopoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    private val lastFrame = LastFrameCache()

    override var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = rawPopoutFrameSink
        set(value) {
            rawPopoutFrameSink = value
            if (value != null) lastFrame.replay(value)
        }

    @Volatile
    var expectedW = 0; private set

    @Volatile
    var expectedH = 0; private set

    /** PTS of the first decoded frame, used for exact A/V bias anchoring. [Long.MIN_VALUE] until a frame is read. */
    @Volatile
    var firstRawPtsNanos: Long = Long.MIN_VALUE
        private set

    /** PTS of the most recently decoded frame. */
    @Volatile
    var lastDecodedPtsNanos: Long = Long.MIN_VALUE
        private set

    private val surface = FrameSurface(debugLabel, uploaderFactory, FramePixelFormat.RGB24)

    @Volatile
    private var activePrebuffer: FramePrebuffer? = null

    // ── LibVLC state ──────────────────────────────────────────────────────

    @Volatile
    private var factory: MediaPlayerFactory? = null

    @Volatile
    private var mediaPlayer: EmbeddedMediaPlayer? = null

    /** URL currently being played, used to decide whether an in-place seek is possible. */
    @Volatile
    internal var currentUrl: String? = null
        private set

    /** The control thread, alive while the session is active. */
    @Volatile
    private var controlThread: Thread? = null

    /** When set, the control thread idles between frames (warm park). */
    @Volatile
    private var parked: AtomicBoolean? = null

    /** Latest frame buffer from libvlc callback, read by the control thread. */
    private val latestFrame = AtomicReference<FrameData?>(null)

    /** Pending in-place seek target in nanos; [Long.MIN_VALUE] = none. */
    private val seekRequestedNanos = AtomicLong(Long.MIN_VALUE)

    /** When non-null, counted down after the first frame of a seek is presented. */
    @Volatile
    private var seekFirstFrameLatch: CountDownLatch? = null

    /** Video dimensions reported by libvlc at start. */
    @Volatile
    private var sourceW = 0
    @Volatile
    private var sourceH = 0

    /** Frame buffer holding the current I420 frame from libvlc. */
    private var i420Buffer: ByteBuffer? = null

    /** Scratch buffer for RGB24 conversion. */
    private var rgbScratch: ByteBuffer? = null

    /** Scratch buffer for popout RGBA. */
    private var popoutRgba: ByteBuffer? = null

    /** Reported by libvlc callback, used to detect resolution changes. */
    @Volatile
    private var frameW = 0
    @Volatile
    private var frameH = 0

    /** Latch that the first frame callback counts down to unblock start(). */
    private val firstFrameLatch = CountDownLatch(1)

    // ── FramePipe interface ───────────────────────────────────────────────

    override fun textureFilled(): Boolean = surface.textureFilled()

    override fun updateFrame(texture: GpuTextureRef, actualW: Int, actualH: Int): Boolean =
        surface.updateFrame(texture, actualW, actualH, expectedW, expectedH)

    override fun updateFramePlanar(
        y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef,
        actualW: Int, actualH: Int,
    ): Boolean = surface.updateFramePlanar(y, u, v, actualW, actualH, expectedW, expectedH)

    override fun clear() = surface.clear()

    override fun trimForPark() {
        activePrebuffer?.trimForPark()
        surface.clear()
        popoutRgba = null
    }

    override fun cleanup() = surface.cleanup()

    // ── Session lifecycle ─────────────────────────────────────────────────

    /**
     * Opens [url] with libvlc and starts the control thread.
     * Returns the running thread, or null when the player could not be opened.
     */
    fun start(
        url: String,
        w: Int,
        h: Int,
        seekOffsetNanos: Long,
        sourceFps: Double,
        stopFlag: AtomicBoolean,
        terminated: AtomicBoolean,
        getAudioClock: () -> Long,
        onFirstFrame: () -> Unit,
        getBrightness: () -> Double,
        getStretchMode: () -> StretchMode = { StretchMode.LETTERBOX },
        onEos: (stderr: String, normalEos: Boolean) -> Unit,
        parkFlag: AtomicBoolean? = null,
        presentPreview: Boolean = true,
        tolerateLateness: Boolean = true,
        onDriftResync: (() -> Unit)? = null,
    ): Thread? {
        release()
        clear()
        expectedW = w
        expectedH = h
        firstRawPtsNanos = Long.MIN_VALUE
        parked = parkFlag
        lastFrameReceivedNanos.set(System.nanoTime())
        latestFrame.set(null)
        sourceW = w
        sourceH = h

        // Build libvlc args with referer
        val args = mutableListOf<String>()
        args.addAll(SHARED_LIBVLC_ARGS)
        MediaHosts.refererFor(url)?.let { referer ->
            args.add("--http-referer=$referer")
        }
        // Use --no-video-decode for software (or let VLC auto-detect hwaccel)
        // For now, let VLC auto-detect hardware acceleration

        val fact = try {
            MediaPlayerFactory(args)
        } catch (e: Exception) {
            logger.error("$debugLabel Failed to create MediaPlayerFactory for $url", e)
            return null
        }
        factory = fact

        val mp = fact.mediaPlayers().newEmbeddedMediaPlayer()
        mediaPlayer = mp

        // Set up video callbacks
        val videoSurface = fact.videoSurfaces().newVideoSurface(
            bufferFormatCallback,
            renderCallback,
            true, // useDirectBuffers
        )
        mp.videoSurface().set(videoSurface)

        // Listen for events
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) {
                logger.debug("$debugLabel libvlc playing.")
            }

            override fun finished(mp: MediaPlayer) {
                logger.debug("$debugLabel libvlc finished.")
                // Will be handled by the control thread
            }

            override fun error(mp: MediaPlayer) {
                logger.error("$debugLabel libvlc error.")
            }

            override fun timeChanged(mp: MediaPlayer, time: Long) {
                // time in ms — not used for frame pacing currently
            }
        })

        // Start playback
        mp.media().play(url)

        // Wait for the first frame to arrive (or timeout)
        try {
            if (!firstFrameLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.error("$debugLabel libvlc first frame timeout for $url")
                mp.controls().stop()
                mp.release()
                fact.release()
                return null
            }
        } catch (_: InterruptedException) {
            mp.controls().stop()
            mp.release()
            fact.release()
            return null
        }

        // Seek to the target offset if needed
        if (seekOffsetNanos > 0) {
            mp.controls().setTime(seekOffsetNanos / 1_000_000L)
        }

        currentUrl = url
        seekRequestedNanos.set(Long.MIN_VALUE)

        val frameNs = (1_000_000_000.0 / outputFps(sourceFps)).toLong()
        val prebuffer = FramePrebuffer.createIfEnabled(
            surface, frameNs, getAudioClock, onFirstFrame, terminated, stopFlag, debugLabel,
            presentPreview, tolerateLateness, parkFlag,
        ).also { activePrebuffer = it }
        prebuffer?.onPresent = { buf -> feedSink(buf, w, h) }
        prebuffer?.onDriftResync = onDriftResync

        val thread = daemon(
            {
                controlLoop(w, h, frameNs, seekOffsetNanos, stopFlag, terminated, getAudioClock, onFirstFrame,
                    getBrightness, getStretchMode, onEos, prebuffer, presentPreview)
            },
            "MediaPlayer-video",
        ).also { it.start() }
        controlThread = thread
        return thread
    }

    /**
     * Requests an in-place seek on the existing libvlc player (no re-open).
     * The control loop picks this up at the next frame boundary.
     */
    fun requestInPlaceSeek(offsetNanos: Long, firstFrameLatch: CountDownLatch? = null): Boolean {
        if (mediaPlayer == null) return false
        seekRequestedNanos.set(offsetNanos)
        seekFirstFrameLatch = firstFrameLatch
        // Apply the seek immediately on the libvlc player
        mediaPlayer?.controls()?.setTime(offsetNanos / 1_000_000L)
        return true
    }

    /** Stops the libvlc player. */
    fun kill() {
        mediaPlayer?.controls()?.stop()
    }

    /** Releases the libvlc player and all resources. */
    fun release() {
        parked = null
        controlThread = null
        latestFrame.set(null)
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            try {
                mp.controls().stop()
                mp.release()
            } catch (_: Exception) {
                // Ignore errors during cleanup
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
        i420Buffer = null
        rgbScratch = null
    }

    // ── Control loop ──────────────────────────────────────────────────────

    private fun controlLoop(
        w: Int, h: Int, frameNs: Long, seekOffsetNanos: Long,
        stopFlag: AtomicBoolean, terminated: AtomicBoolean,
        getAudioClock: () -> Long, onFirstFrame: () -> Unit, getBrightness: () -> Double,
        getStretchMode: () -> StretchMode, onEos: (stderr: String, normalEos: Boolean) -> Unit,
        prebuffer: FramePrebuffer?, presentPreview: Boolean,
    ) {
        val frameSize = if (planarOutput) {
            val c = ((w + 1) / 2) * ((h + 1) / 2)
            w * h + 2 * c
        } else {
            w * h * 3
        }
        var spare = surface.takeOrAllocate(frameSize)
        surface.recycleFrameBuffer(surface.allocateFrameBuffer(frameSize))

        var firstFrame = false
        var videoPts = seekOffsetNanos
        var normalEos = false
        var errorMessage = ""
        var lastPts = 0L

        while (!terminated.get() && !stopFlag.get()) {
            // In-place seek
            val seekTo = seekRequestedNanos.getAndSet(Long.MIN_VALUE)
            if (seekTo != Long.MIN_VALUE) {
                doInPlaceSeek(seekTo, surface, prebuffer, onFirstFrame)
                videoPts = seekTo
                firstFrame = false
                normalEos = false
                errorMessage = ""
                firstRawPtsNanos = Long.MIN_VALUE
                lastFrameReceivedNanos.set(System.nanoTime())
                continue
            }

            // Warm park
            val pk = parked
            if (pk != null && pk.get()) {
                while (pk.get() && !terminated.get() && !stopFlag.get()) {
                    try { Thread.sleep(PARK_POLL_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
                lastFrameReceivedNanos.set(System.nanoTime())
                continue
            }

            // Check for EOS: libvlc state ENDED or stopped
            val mp = mediaPlayer
            if (mp == null) {
                normalEos = true
                break
            }
            val state = mp.status().state()
            if (state == uk.co.caprica.vlcj.player.base.State.ENDED) {
                normalEos = true
                break
            }
            if (state == uk.co.caprica.vlcj.player.base.State.ERROR) {
                errorMessage = "libvlc playback error"
                break
            }
            if (state == uk.co.caprica.vlcj.player.base.State.STOPPED) {
                // Only treat as EOS if we weren't explicitly stopped by kill()
                if (!stopFlag.get()) {
                    normalEos = true
                    break
                }
            }

            // Check for a new frame from the libvlc callback
            val frameData = latestFrame.getAndSet(null)
            if (frameData == null) {
                // No frame yet — sleep briefly and poll again
                try { Thread.sleep(1) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                continue
            }

            // We have a frame from libvlc
            frameW = frameData.width
            frameH = frameData.height
            lastFrameReceivedNanos.set(System.nanoTime())

            // Record the first PTS
            if (firstRawPtsNanos == Long.MIN_VALUE && frameData.pts > 0) {
                firstRawPtsNanos = frameData.pts
            }

            // Copy the frame data from libvlc's buffer into our FrameSurface buffer
            spare.clear()
            if (planarOutput) {
                // Frame from libvlc is already I420 — copy directly
                val ySize = w * h
                val uvSize = ((w + 1) / 2) * ((h + 1) / 2)
                val totalSize = ySize + 2 * uvSize

                // If source dimensions differ from expected, resize
                if (frameData.width == w && frameData.height == h) {
                    for (i in 0 until totalSize) {
                        spare.put(frameData.buffer.get(i))
                    }
                } else {
                    // Simple nearest-neighbor resize for the initial port
                    resizeI420(
                        frameData.buffer, frameData.width, frameData.height,
                        spare, w, h,
                    )
                }
            } else {
                // Convert I420 to RGB24
                val rgbSize = w * h * 3
                if (frameData.width == w && frameData.height == h) {
                    i420ToRgb24(frameData.buffer, w, h, spare)
                } else {
                    // Resize + convert
                    val scratch = rgbScratch ?: surface.allocateFrameBuffer(frameData.width * frameData.height * 3)
                        .also { rgbScratch = it }
                    scratch.clear()
                    i420ToRgb24(frameData.buffer, frameData.width, frameData.height, scratch)
                    resizeRgb24(scratch, frameData.width, frameData.height, spare, w, h)
                }
                applyBrightness(spare, w * h * 3, getBrightness())
            }
            spare.flip()

            val pk2 = parked
            if (pk2 != null && pk2.get()) continue

            // Use the frame PTS or synthetic
            val framePts = if (frameData.pts > 0) frameData.pts else videoPts
            lastDecodedPtsNanos = framePts

            if (prebuffer != null) {
                if (!MediaPlayer.captureSamples) {
                    feedSink(spare, w, h)
                    videoPts += frameNs
                    continue
                }
                spare = prebuffer.submit(spare, framePts, frameSize)
                if (MediaPlayer.DEBUG) MediaPlayer.samplesIn.incrementAndGet()
                videoPts = framePts + frameNs
                continue
            }

            if (FramePacing.pace(framePts, getAudioClock)) {
                MediaPlayer.framesDropped.incrementAndGet()
                videoPts = framePts + frameNs
                continue
            }

            feedSink(spare, w, h)

            if (!MediaPlayer.captureSamples) {
                videoPts = framePts + frameNs
                continue
            }

            spare = surface.publish(spare, frameSize)
            if (MediaPlayer.DEBUG) MediaPlayer.samplesIn.incrementAndGet()
            if (!firstFrame) {
                firstFrame = true
                onFirstFrame()
                seekFirstFrameLatch?.countDown()
                if (MediaPlayer.DEBUG) logger.debug("$debugLabel First frame $w x $h (libvlc).")
            }
            videoPts = framePts + frameNs
        }

        if (!terminated.get() && !stopFlag.get() && normalEos) {
            prebuffer?.finish()
        } else {
            prebuffer?.abort()
        }
        if (activePrebuffer === prebuffer) activePrebuffer = null

        if (!terminated.get() && !stopFlag.get()) {
            onEos(errorMessage.ifEmpty { if (normalEos) "End of stream" else "Unknown error" }, normalEos)
        }
    }

    // ── LibVLC callbacks ──────────────────────────────────────────────────

    private val bufferFormatCallback = object : BufferFormatCallback {
        override fun getBufferFormat(width: Int, height: Int): BufferFormat {
            logger.debug("$debugLabel libvlc buffer format: ${width}x${height}")
            sourceW = width
            sourceH = height
            // Use I420 chroma — libvlc will convert to planar YUV420P
            val chroma = "I420"
            val pitches = intArrayOf(width, (width + 1) / 2, (width + 1) / 2)
            val lines = intArrayOf(height, (height + 1) / 2, (height + 1) / 2)
            return BufferFormat(chroma, width, height, pitches, lines)
        }

        override fun allocatedBuffers(buffers: Array<ByteBuffer>) {
            logger.debug("$debugLabel libvlc allocated ${buffers.size} buffers, total size = ${buffers.sumOf { it.capacity() }}")
        }
    }

    private val renderCallback = RenderCallback { mp, buffers, format ->
        if (buffers.isEmpty()) return@RenderCallback
        // For I420 format, buffers[0] = Y, buffers[1] = U, buffers[2] = V
        // We pack them into a single buffer for the FrameSurface
        val w = format.width
        val h = format.height
        if (w <= 0 || h <= 0) return@RenderCallback

        val ySize = w * h
        val uvSize = ((w + 1) / 2) * ((h + 1) / 2)
        val totalSize = ySize + 2 * uvSize

        val buf = when {
            i420Buffer != null && i420Buffer!!.capacity() >= totalSize -> {
                i420Buffer!!.clear()
                i420Buffer!!
            }
            else -> {
                ByteBuffer.allocateDirect(totalSize).also { i420Buffer = it }
            }
        }

        // Pack Y plane
        if (buffers.size > 0) {
            val yBuf = buffers[0]
            yBuf.rewind()
            val yLimit = minOf(ySize, yBuf.remaining())
            for (i in 0 until yLimit) {
                buf.put(yBuf.get())
            }
            // Pad any remaining rows
            for (i in yLimit until ySize) {
                buf.put(16.toByte()) // black Y
            }
        }
        // Pack U plane
        if (buffers.size > 1) {
            val uBuf = buffers[1]
            uBuf.rewind()
            val uLimit = minOf(uvSize, uBuf.remaining())
            for (i in 0 until uLimit) {
                buf.put(uBuf.get())
            }
            for (i in uLimit until uvSize) {
                buf.put(128.toByte())
            }
        } else {
            for (i in 0 until uvSize) buf.put(128.toByte())
        }
        // Pack V plane
        if (buffers.size > 2) {
            val vBuf = buffers[2]
            vBuf.rewind()
            val vLimit = minOf(uvSize, vBuf.remaining())
            for (i in 0 until vLimit) {
                buf.put(vBuf.get())
            }
            for (i in vLimit until uvSize) {
                buf.put(128.toByte())
            }
        } else {
            for (i in 0 until uvSize) buf.put(128.toByte())
        }

        buf.flip()

        // Get PTS from the media player
        val pts = mp.status().time() * 1_000_000L // ms → ns

        latestFrame.set(FrameData(buf, w, h, pts))
    }

    // ── Frame conversion ──────────────────────────────────────────────────

    /**
     * Converts I420 data to RGB24 packed buffer.
     */
    private fun i420ToRgb24(i420: ByteBuffer, w: Int, h: Int, rgb: ByteBuffer) {
        val ySize = w * h
        val uvSize = ((w + 1) / 2) * ((h + 1) / 2)
        i420.rewind()
        for (row in 0 until h) {
            for (col in 0 until w) {
                val y = i420.get(row * w + col).toInt() and 0xFF
                val u = i420.get(ySize + (row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF
                val v = i420.get(ySize + uvSize + (row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF

                // YUV to RGB (BT.601)
                val r = (y + 1.402 * (v - 128)).toInt().coerceIn(0, 255)
                val g = (y - 0.344 * (u - 128) - 0.714 * (v - 128)).toInt().coerceIn(0, 255)
                val b = (y + 1.772 * (u - 128)).toInt().coerceIn(0, 255)

                rgb.put(r.toByte())
                rgb.put(g.toByte())
                rgb.put(b.toByte())
            }
        }
        i420.rewind()
    }

    /**
     * Simple nearest-neighbor resize for I420.
     */
    private fun resizeI420(src: ByteBuffer, srcW: Int, srcH: Int, dst: ByteBuffer, dstW: Int, dstH: Int) {
        val srcYSize = srcW * srcH
        val srcUVSize = ((srcW + 1) / 2) * ((srcH + 1) / 2)
        val dstYSize = dstW * dstH
        val dstUVSize = ((dstW + 1) / 2) * ((dstH + 1) / 2)
        src.rewind()

        // Resize Y plane
        for (dy in 0 until dstH) {
            val sy = (dy * srcH / dstH).coerceIn(0, srcH - 1)
            for (dx in 0 until dstW) {
                val sx = (dx * srcW / dstW).coerceIn(0, srcW - 1)
                dst.put(src.get(sy * srcW + sx))
            }
        }
        // Resize U plane
        for (dy in 0 until (dstH + 1) / 2) {
            val sy = (dy * ((srcH + 1) / 2) / ((dstH + 1) / 2)).coerceIn(0, ((srcH + 1) / 2) - 1)
            for (dx in 0 until (dstW + 1) / 2) {
                val sx = (dx * ((srcW + 1) / 2) / ((dstW + 1) / 2)).coerceIn(0, ((srcW + 1) / 2) - 1)
                dst.put(src.get(srcYSize + sy * ((srcW + 1) / 2) + sx))
            }
        }
        // Resize V plane
        for (dy in 0 until (dstH + 1) / 2) {
            val sy = (dy * ((srcH + 1) / 2) / ((dstH + 1) / 2)).coerceIn(0, ((srcH + 1) / 2) - 1)
            for (dx in 0 until (dstW + 1) / 2) {
                val sx = (dx * ((srcW + 1) / 2) / ((dstW + 1) / 2)).coerceIn(0, ((srcW + 1) / 2) - 1)
                dst.put(src.get(srcYSize + srcUVSize + sy * ((srcW + 1) / 2) + sx))
            }
        }
        src.rewind()
    }

    /**
     * Simple nearest-neighbor resize for RGB24.
     */
    private fun resizeRgb24(src: ByteBuffer, srcW: Int, srcH: Int, dst: ByteBuffer, dstW: Int, dstH: Int) {
        src.rewind()
        for (dy in 0 until dstH) {
            val sy = (dy * srcH / dstH).coerceIn(0, srcH - 1)
            for (dx in 0 until dstW) {
                val sx = (dx * srcW / dstW).coerceIn(0, srcW - 1)
                val srcPos = (sy * srcW + sx) * 3
                dst.put(src.get(srcPos))
                dst.put(src.get(srcPos + 1))
                dst.put(src.get(srcPos + 2))
            }
        }
        src.rewind()
    }

    /** Applies brightness adjustment in-place to the RGB24 frame in [buf]. */
    private fun applyBrightness(buf: ByteBuffer, size: Int, brightness: Double) {
        val factor = brightness.coerceIn(0.0, 2.0)
        if (factor == 1.0) return
        val savedPosition = buf.position()
        val savedLimit = buf.limit()
        buf.flip()
        for (i in 0 until size) {
            val value = ((buf.get(i).toInt() and 0xFF) * factor).toInt().coerceIn(0, 255)
            buf.put(i, value.toByte())
        }
        buf.limit(savedLimit)
        buf.position(savedPosition)
    }

    /** Feeds the current frame to the popout sink. */
    private fun feedSink(buf: ByteBuffer, w: Int, h: Int) {
        val sink = popoutFrameSink ?: return
        if (planarOutput) {
            // Convert I420 → RGBA for popout
            val rgbaSize = w * h * 4
            val rgba = popoutRgba?.takeIf { it.capacity() >= rgbaSize }
                ?: surface.allocateFrameBuffer(rgbaSize).also { popoutRgba = it }
            rgba.clear()
            i420ToRgba(buf, w, h, rgba)
            rgba.limit(rgbaSize).position(0)
            lastFrame.store(rgba, w, h, rgbaSize, FramePixelFormat.RGBA32)
            sink(rgba, w, h, FramePixelFormat.RGBA32)
            buf.rewind()
        } else {
            lastFrame.store(buf, w, h, w * h * 3, FramePixelFormat.RGB24)
            sink(buf, w, h, FramePixelFormat.RGB24)
            buf.rewind()
        }
    }

    /** Simple I420 → RGBA conversion (software, for the popout). */
    private fun i420ToRgba(i420: ByteBuffer, w: Int, h: Int, rgba: ByteBuffer) {
        val yLen = w * h
        val uvLen = ((w + 1) / 2) * ((h + 1) / 2)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val y = i420.get(row * w + col).toInt() and 0xFF
                val u = i420.get(yLen + (row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF
                val v = i420.get(yLen + uvLen + (row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF
                var r = (y + 1.402 * (v - 128)).toInt().coerceIn(0, 255)
                var g = (y - 0.344 * (u - 128) - 0.714 * (v - 128)).toInt().coerceIn(0, 255)
                var b = (y + 1.772 * (u - 128)).toInt().coerceIn(0, 255)
                rgba.put(r.toByte())
                rgba.put(g.toByte())
                rgba.put(b.toByte())
                rgba.put(0xFF.toByte())
            }
        }
        i420.rewind()
    }

    /** Applies an in-place seek on the existing libvlc player. */
    private fun doInPlaceSeek(
        offsetNanos: Long,
        surface: FrameSurface,
        prebuffer: FramePrebuffer?,
        onFirstFrame: () -> Unit,
    ) {
        val mp = mediaPlayer ?: return
        try {
            mp.controls().setTime(offsetNanos / 1_000_000L)
            logger.debug("$debugLabel In-place seek to ${offsetNanos / 1_000_000} ms on libvlc player.")
            latestFrame.set(null)
            if (prebuffer != null) {
                val seekLatch = seekFirstFrameLatch
                seekFirstFrameLatch = null
                prebuffer.resetForSeek {
                    onFirstFrame()
                    seekLatch?.countDown()
                }
            }
        } catch (e: Exception) {
            logger.warn("$debugLabel In-place seek to ${offsetNanos / 1_000_000} ms failed: ${e.message}")
            seekFirstFrameLatch?.countDown()
            seekFirstFrameLatch = null
            try { mp.controls().stop() } catch (_: Exception) { }
            return
        }
        surface.clear()
    }

    /** Frame rate assumption for sources without a valid FPS. */
    private fun outputFps(sourceFps: Double): Double =
        sourceFps.takeIf { it.isFinite() && it > 1.0 && it <= 240.0 } ?: 30.0

    // ── Internal data class ───────────────────────────────────────────────

    /** Holds one decoded frame from libvlc. */
    private data class FrameData(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val pts: Long, // nanoseconds
    )
}