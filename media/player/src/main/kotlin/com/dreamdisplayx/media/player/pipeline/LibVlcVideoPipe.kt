@file:Suppress("Since15")

package com.dreamdisplayx.media.player.pipeline

import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.model.StretchMode
import com.dreamdisplayx.api.media.player.FrameUploaderFactory
import com.dreamdisplayx.api.media.player.GpuTextureRef
import com.dreamdisplayx.api.security.policy.MediaHosts
import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.media.player.util.daemon
import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer as VlcjMediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * LibVLC (vlcj) based video frame pipe — replaces [JavaCppVideoPipe].
 *
 * Uses libvlc's built-in playback (play/pause/seek) and delivers frames
 * through the video callback ([RenderCallback]) directly into the shared
 * [FrameSurface] pipeline. No separate reader thread, no manual pacing,
 * no prebuffer — libvlc handles all demuxing, decoding, A/V sync, and
 * hardware acceleration internally.
 */
internal class LibVlcVideoPipe(
    private val debugLabel: String,
    uploaderFactory: FrameUploaderFactory,
    /** True when frames stay as raw I420 planes; YUV→RGB runs on the GPU. */
    private val planarOutput: Boolean,
) : FramePipe {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/LibVlcVideoPipe")

    companion object {
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
            "--no-audio",  // video pipe only
        )
    }

    // ── FramePipe state ───────────────────────────────────────────────────

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

    /** PTS of the first decoded frame, used for exact A/V bias anchoring. */
    @Volatile
    var firstRawPtsNanos: Long = Long.MIN_VALUE; private set

    @Volatile
    var lastDecodedPtsNanos: Long = Long.MIN_VALUE; private set

    private val surface = FrameSurface(debugLabel, uploaderFactory, FramePixelFormat.RGB24)

    // ── LibVLC state ──────────────────────────────────────────────────────

    @Volatile
    private var factory: MediaPlayerFactory? = null

    @Volatile
    private var mediaPlayer: EmbeddedMediaPlayer? = null

    @Volatile
    internal var currentUrl: String? = null; private set

    /** EOS monitor thread (poll-only, no pacing). */
    @Volatile
    private var eosThread: Thread? = null

    /** Park flag — when set, libvlc is paused. */
    @Volatile
    private var parkFlag: AtomicBoolean? = null

    // ── Callback-driven state ─────────────────────────────────────────────

    @Volatile
    private var sourceW = 0

    @Volatile
    private var sourceH = 0

    /** Scratch buffer for the callback to pack I420 planes into. */
    private var i420Scratch: ByteBuffer? = null

    /** Scratch buffer for RGB24 conversion. */
    private var rgbScratch: ByteBuffer? = null

    /** Scratch buffer for popout RGBA. */
    private var popoutRgba: ByteBuffer? = null

    /** Signalled by the callback when the first frame arrives. */
    private val firstFrameLatch = CountDownLatch(1)

    @Volatile
    private var firstFrameFired = false

    // ── EOS / error signals ───────────────────────────────────────────────

    @Volatile
    private var eosReached = false

    @Volatile
    private var errorMessage = ""

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
        surface.clear()
        popoutRgba = null
    }

    override fun cleanup() = surface.cleanup()

    // ── Session lifecycle ─────────────────────────────────────────────────

    /**
     * Opens [url] with libvlc and starts frame delivery via callbacks.
     * Returns an EOS-monitor thread (never null on success), or null on failure.
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
        lastDecodedPtsNanos = Long.MIN_VALUE
        this.parkFlag = parkFlag
        lastFrameReceivedNanos.set(System.nanoTime())
        eosReached = false
        errorMessage = ""
        firstFrameFired = false

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
            return null
        }
        factory = fact

        val mp = fact.mediaPlayers().newEmbeddedMediaPlayer()
        mediaPlayer = mp

        // Video callbacks: libvlc delivers I420 planes
        val videoSurface = fact.videoSurfaces().newVideoSurface(
            bufferFormatCallback,
            renderCallback(onFirstFrame, getBrightness, getStretchMode),
            true, // useDirectBuffers
        )
        mp.videoSurface().set(videoSurface)

        // Event listener: track EOS / errors
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mp: VlcjMediaPlayer) {
                logger.debug("$debugLabel libvlc finished.")
                eosReached = true
            }

            override fun error(mp: VlcjMediaPlayer) {
                logger.error("$debugLabel libvlc error.")
                errorMessage = "libvlc decode error"
                eosReached = true
            }

            override fun playing(mp: VlcjMediaPlayer) {
                if (MediaPlayer.DEBUG) logger.debug("$debugLabel libvlc playing.")
            }

            override fun paused(mp: VlcjMediaPlayer) {
                if (MediaPlayer.DEBUG) logger.debug("$debugLabel libvlc paused.")
            }

            override fun stopped(mp: VlcjMediaPlayer) {
                if (MediaPlayer.DEBUG) logger.debug("$debugLabel libvlc stopped.")
            }
        })

        // Start playback
        mp.media().play(url)

        // Wait for the first frame (or timeout) so the caller knows it's alive
        try {
            if (!firstFrameLatch.await(10, TimeUnit.SECONDS)) {
                logger.error("$debugLabel libvlc first frame timeout for $url")
                mp.controls().stop()
                mp.release()
                fact.release()
                factory = null
                mediaPlayer = null
                return null
            }
        } catch (_: InterruptedException) {
            mp.controls().stop()
            mp.release()
            fact.release()
            factory = null
            mediaPlayer = null
            return null
        }

        // Seek to the target offset
        if (seekOffsetNanos > 0) {
            mp.controls().setTime(seekOffsetNanos / 1_000_000L)
        }

        currentUrl = url

        // EOS monitor thread (replaces the old reader thread for join compatibility)
        val thread = daemon(
            {
                eosMonitor(terminated, stopFlag, onEos)
            },
            "MediaPlayer-video-eos",
        ).also { it.start() }
        eosThread = thread
        return thread
    }

    /** Seeks the libvlc player to [offsetNanos] (millisecond precision). */
    fun requestInPlaceSeek(offsetNanos: Long, firstFrameLatch: CountDownLatch? = null): Boolean {
        val mp = mediaPlayer ?: return false
        mp.controls().setTime(offsetNanos / 1_000_000L)
        if (MediaPlayer.DEBUG) logger.debug("$debugLabel libvlc seek to ${offsetNanos / 1_000_000} ms.")
        return true
    }

    /** Stops libvlc playback. */
    fun kill() {
        mediaPlayer?.controls()?.stop()
    }

    /** Releases all libvlc resources. */
    fun release() {
        eosThread = null
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
        i420Scratch = null
        rgbScratch = null
    }

    // ── EOS monitor ───────────────────────────────────────────────────────

    /**
     * Polls for EOS / error and calls [onEos]. Runs on a separate thread so the
     * callback is not invoked from libvlc's internal thread pool.
     */
    private fun eosMonitor(
        terminated: AtomicBoolean,
        stopFlag: AtomicBoolean,
        onEos: (String, Boolean) -> Unit,
    ) {
        while (!terminated.get() && !stopFlag.get() && !eosReached) {
            // Park: pause libvlc playback
            val pk = parkFlag
            if (pk != null && pk.get()) {
                try { mediaPlayer?.controls()?.setPause(true) } catch (_: Exception) { }
                while (pk.get() && !terminated.get() && !stopFlag.get() && !eosReached) {
                    try { Thread.sleep(50) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
                }
                try { mediaPlayer?.controls()?.setPause(false) } catch (_: Exception) { }
                continue
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
        if (!terminated.get() && !stopFlag.get() && eosReached) {
            onEos(errorMessage.ifEmpty { "End of stream" }, errorMessage.isEmpty())
        }
    }

    // ── LibVLC callbacks ──────────────────────────────────────────────────

    private val bufferFormatCallback = object : BufferFormatCallback {
        override fun getBufferFormat(width: Int, height: Int): BufferFormat {
            sourceW = width
            sourceH = height
            if (MediaPlayer.DEBUG) logger.debug("$debugLabel libvlc source: ${width}x${height}")
            // I420 chroma: libvlc converts to planar YUV420P
            return BufferFormat("I420", width, height,
                intArrayOf(width, (width + 1) / 2, (width + 1) / 2),
                intArrayOf(height, (height + 1) / 2, (height + 1) / 2),
            )
        }

        override fun allocatedBuffers(buffers: Array<ByteBuffer>) {
            if (MediaPlayer.DEBUG) logger.debug("$debugLabel libvlc allocated ${buffers.size} buffers, total ${buffers.sumOf { it.capacity() }} bytes.")
        }
    }

    private fun renderCallback(
        onFirstFrame: () -> Unit,
        getBrightness: () -> Double,
        getStretchMode: () -> StretchMode,
    ): RenderCallback = RenderCallback { mp, buffers, format ->
        if (eosReached || buffers.isEmpty()) return@RenderCallback

        val w = sourceW
        val h = sourceH
        if (w <= 0 || h <= 0) return@RenderCallback

        val ySize = w * h
        val uvSize = ((w + 1) / 2) * ((h + 1) / 2)
        val totalSize = ySize + 2 * uvSize

        // Pack I420 planes into a contiguous buffer
        val i420 = bufferOf(totalSize) { buf ->
            // Y plane
            if (buffers.size > 0) {
                val y = buffers[0].duplicate(); y.rewind()
                copyOrPad(y, buf, ySize, 16.toByte())
            } else {
                for (i in 0 until ySize) buf.put(16.toByte())
            }
            // U plane
            if (buffers.size > 1) {
                val u = buffers[1].duplicate(); u.rewind()
                copyOrPad(u, buf, uvSize, 128.toByte())
            } else {
                for (i in 0 until uvSize) buf.put(128.toByte())
            }
            // V plane
            if (buffers.size > 2) {
                val v = buffers[2].duplicate(); v.rewind()
                copyOrPad(v, buf, uvSize, 128.toByte())
            } else {
                for (i in 0 until uvSize) buf.put(128.toByte())
            }
        }

        // Allocate frame buffer from the surface pool
        val frameSize = if (planarOutput) {
            val c = ((expectedW + 1) / 2) * ((expectedH + 1) / 2)
            expectedW * expectedH + 2 * c
        } else {
            expectedW * expectedH * 3
        }
        var spare = surface.takeOrAllocate(frameSize)
        spare.clear()

        // Convert / resize into the output buffer
        if (w == expectedW && h == expectedH) {
            if (planarOutput) {
                i420.rewind()
                for (i in 0 until totalSize) spare.put(i420.get())
            } else {
                i420ToRgb24(i420, w, h, spare)
                applyBrightness(spare, frameSize, getBrightness())
            }
        } else {
            if (planarOutput) {
                resizeI420(i420, w, h, spare, expectedW, expectedH)
            } else {
                val scratch = rgbScratch ?: surface.allocateFrameBuffer(w * h * 3).also { rgbScratch = it }
                scratch.clear()
                i420ToRgb24(i420, w, h, scratch)
                resizeRgb24(scratch, w, h, spare, expectedW, expectedH)
                applyBrightness(spare, frameSize, getBrightness())
            }
        }
        spare.flip()

        // Park check: if parked, skip frame delivery
        if (parkFlag?.get() == true) return@RenderCallback

        // Popout sink
        feedSink(spare, expectedW, expectedH)

        // Publish to the surface (render thread picks it up in updateFrame)
        surface.publish(spare, frameSize)
        lastFrameReceivedNanos.set(System.nanoTime())

        // Capture PTS
        if (firstRawPtsNanos == Long.MIN_VALUE) {
            firstRawPtsNanos = mp.status().time() * 1_000_000L
        }
        lastDecodedPtsNanos = mp.status().time() * 1_000_000L

        // First frame callback
        if (!firstFrameFired) {
            firstFrameFired = true
            onFirstFrame()
            firstFrameLatch.countDown()
        }
    }

    /** Returns a direct ByteBuffer of [size] filled by [body]. */
    private fun bufferOf(size: Int, body: (ByteBuffer) -> Unit): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(size)
        body(buf)
        buf.flip()
        return buf
    }

    /** Copies min([limit], [src.remaining]) bytes from [src] to [dst], padding with [pad] for the rest. */
    private fun copyOrPad(src: ByteBuffer, dst: ByteBuffer, limit: Int, pad: Byte) {
        val n = minOf(limit, src.remaining())
        for (i in 0 until n) dst.put(src.get())
        for (i in n until limit) dst.put(pad)
    }

    // ── Frame conversion ──────────────────────────────────────────────────

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
            for (dx in 0 until dstW) {
                val sx = (dx * srcW / dstW).coerceIn(0, srcW - 1)
                dst.put(src.get(sy * srcW + sx))
            }
        }
        for (dy in 0 until (dstH + 1) / 2) {
            val sy = (dy * ((srcH + 1) / 2) / ((dstH + 1) / 2)).coerceIn(0, ((srcH + 1) / 2) - 1)
            for (dx in 0 until (dstW + 1) / 2) {
                val sx = (dx * ((srcW + 1) / 2) / ((dstW + 1) / 2)).coerceIn(0, ((srcW + 1) / 2) - 1)
                dst.put(src.get(srcYSize + sy * ((srcW + 1) / 2) + sx))
            }
        }
        for (dy in 0 until (dstH + 1) / 2) {
            val sy = (dy * ((srcH + 1) / 2) / ((dstH + 1) / 2)).coerceIn(0, ((srcH + 1) / 2) - 1)
            for (dx in 0 until (dstW + 1) / 2) {
                val sx = (dx * ((srcW + 1) / 2) / ((dstW + 1) / 2)).coerceIn(0, ((srcW + 1) / 2) - 1)
                dst.put(src.get(srcYSize + srcUVSize + sy * ((srcW + 1) / 2) + sx))
            }
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
        val sink = popoutFrameSink ?: return
        if (planarOutput) {
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
                rgba.put(r.toByte()); rgba.put(g.toByte()); rgba.put(b.toByte()); rgba.put(0xFF.toByte())
            }
        }
        i420.rewind()
    }
}