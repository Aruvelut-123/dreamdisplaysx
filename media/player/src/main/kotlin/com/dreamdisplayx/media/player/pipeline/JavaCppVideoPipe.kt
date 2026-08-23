@file:Suppress("Since15")

package com.dreamdisplayx.media.player.pipeline

import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.player.FrameUploaderFactory
import com.dreamdisplayx.api.media.player.GpuTextureRef
import com.dreamdisplayx.api.security.policy.MediaHosts
import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.media.player.util.daemon
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.ffmpeg.swscale.SwsFilter
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * JavaCPP (FFmpegFrameGrabber) based video frame pipe — replaces both
 * [JavaCppVideoPipe] replaces the earlier "FFmpeg process" pipe and the Rust native pipe.
 * Opens a media URL in-process, decodes frames via FFmpeg bindings, and
 * feeds them into the shared [FrameSurface] / [FramePrebuffer] pipeline.
 */
internal class JavaCppVideoPipe(
    private val debugLabel: String,
    uploaderFactory: FrameUploaderFactory,
    /** True when frames stay as raw I420 planes; YUV→RGB runs on the GPU. */
    private val planarOutput: Boolean,
) : FramePipe {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/JavaCppVideoPipe")

    companion object {
        private const val PARK_POLL_MS = 2L
        /** Max time to wait for a grabber to stop on kill. */
        private const val STOP_TIMEOUT_MS = 500L
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    /** Updated by the reader thread on every frame; used by the watchdog to detect stalls. */
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

    private val surface = FrameSurface(debugLabel, uploaderFactory, FramePixelFormat.RGB24)

    @Volatile
    private var activePrebuffer: FramePrebuffer? = null

    /** The FFmpegFrameGrabber used for the current session. Replaced on seek / restart. */
    @Volatile
    private var grabber: FFmpegFrameGrabber? = null

    /** The reader thread, alive while the session is active. */
    @Volatile
    private var readerThread: Thread? = null

    /** When set, the reader idles between frames (warm park). */
    @Volatile
    private var parked: AtomicBoolean? = null

    /** Scratch buffer for planar I420 frame assembly. */
    private var i420Scratch: ByteBuffer? = null

    /** Scratch buffer for RGB24 frame conversion. */
    private var rgbScratch: ByteBuffer? = null

    /** Scratch buffer for the popout RGBA conversion. */
    private var popoutRgba: ByteBuffer? = null

    // ── Software YUV conversion (planar GPU path) ─────────────────────────
    //
    // javacv's RAW imageMode only fills the first plane (Y) of Frame.image,
    // leaving U/V as null. Instead of relying on that broken Frame.image,
    // we use the standard FFmpeg sws_scale API directly on the underlying
    // AVFrame (frame.opaque), which works for any pixel format and handles
    // scaling + conversion in one call — exactly like `ffmpeg -vf scale`.

    /** sws scaler: source format/尺寸 → 目标 YUV420P 尺寸. */
    private var swsCtx: SwsContext? = null
    private var swsSrcW = 0; private var swsSrcH = 0; private var swsSrcFmt = -1

    /** 目标 I420 AVFrame，sws_scale_frame 写入这里，然后我们复制平面到 spare。 */
    private var dstAvFrame: AVFrame? = null
    private var dstAvW = 0; private var dstAvH = 0

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
     * Opens [url] with FFmpegFrameGrabber and starts the reader thread.
     * Returns the running thread, or null when the grabber could not be opened.
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
        onEos: (stderr: String, normalEos: Boolean) -> Unit,
        parkFlag: AtomicBoolean? = null,
        presentPreview: Boolean = true,
        tolerateLateness: Boolean = true,
    ): Thread? {
        release()
        clear()
        expectedW = w
        expectedH = h
        firstRawPtsNanos = Long.MIN_VALUE
        parked = parkFlag
        lastFrameReceivedNanos.set(System.nanoTime())

        val g = try {
            createGrabber(url, w, h, seekOffsetNanos)
        } catch (e: Exception) {
            logger.error("$debugLabel Failed to open FFmpegFrameGrabber for $url", e)
            return null
        }
        grabber = g

        val frameNs = (1_000_000_000.0 / outputFps(sourceFps)).toLong()
        val prebuffer = FramePrebuffer.createIfEnabled(
            surface, frameNs, getAudioClock, onFirstFrame, terminated, stopFlag, debugLabel,
            presentPreview, tolerateLateness, parkFlag,
        ).also { activePrebuffer = it }
        prebuffer?.onPresent = { buf -> feedSink(buf, w, h) }

        val thread = daemon(
            {
                read(w, h, frameNs, seekOffsetNanos, stopFlag, terminated, getAudioClock, onFirstFrame,
                    getBrightness, onEos, prebuffer, presentPreview)
            },
            "MediaPlayer-video",
        ).also { it.start() }
        readerThread = thread
        return thread
    }

    /**
     * Kills the current grabber, unblocking a reader stuck in `grabImage`.
     * The grabber is released afterward; the caller must open a new session.
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
     * Frees the current grabber and resets the pipe state.
     * Must only be called after the reader thread has been joined.
     */
    fun release() {
        parked = null
        readerThread = null
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
        // Free sws scaler context and target AVFrame
        swsCtx?.let { swscale.sws_freeContext(it) }
        swsCtx = null
        dstAvFrame?.let { avutil.av_frame_free(it) }
        dstAvFrame = null
        dstAvW = 0; dstAvH = 0
        swsSrcW = 0; swsSrcH = 0; swsSrcFmt = -1
        i420Scratch = null
        rgbScratch = null
    }

    // ── Reader loop ───────────────────────────────────────────────────────

    private fun read(
        w: Int, h: Int, frameNs: Long, seekOffsetNanos: Long,
        stopFlag: AtomicBoolean, terminated: AtomicBoolean,
        getAudioClock: () -> Long, onFirstFrame: () -> Unit, getBrightness: () -> Double,
        onEos: (stderr: String, normalEos: Boolean) -> Unit,
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

        while (!terminated.get() && !stopFlag.get()) {
            // Warm park
            val pk = parked
            if (pk != null && pk.get()) {
                while (pk.get() && !terminated.get() && !stopFlag.get()) {
                    try { Thread.sleep(PARK_POLL_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
                lastFrameReceivedNanos.set(System.nanoTime())
                continue
            }

            val g = grabber ?: break

            val frame: Frame
            try {
                frame = g.grabImage() ?: break
            } catch (e: Exception) {
                if (!terminated.get() && !stopFlag.get()) {
                    errorMessage = e.message ?: "grabImage failed"
                    logger.warn("$debugLabel grabImage: ${errorMessage}")
                }
                break
            }

            // Re-check stopped condition after blocking call
            if (terminated.get() || stopFlag.get()) break
            if (frame.image == null || frame.image.isEmpty()) {
                normalEos = true
                break
            }

            // Record the first frame's raw PTS for exact A/V bias anchoring
            if (firstRawPtsNanos == Long.MIN_VALUE && frame.timestamp > 0) {
                firstRawPtsNanos = frame.timestamp
            }

            spare.clear()
            if (planarOutput) {
                val success = frameToI420(frame, spare, w, h)
                if (!success) {
                    logger.warn("$debugLabel Skipped frame: unexpected dimensions ${frame.imageWidth}x${frame.imageHeight}")
                    videoPts += frameNs
                    continue
                }
            } else {
                val success = frameToRgb24(frame, spare, w, h)
                if (!success) {
                    logger.warn("$debugLabel Skipped frame: unexpected dimensions ${frame.imageWidth}x${frame.imageHeight}")
                    videoPts += frameNs
                    continue
                }
                applyBrightness(spare, frameSize, getBrightness())
            }
            spare.flip()

            lastFrameReceivedNanos.set(System.nanoTime())

            val pk2 = parked
            if (pk2 != null && pk2.get()) continue

            // Use the frame's timestamp if available, otherwise synthetic
            val framePts = if (frame.timestamp > 0) frame.timestamp else videoPts

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
                if (MediaPlayer.DEBUG) MediaPlayer.framesDropped.incrementAndGet()
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
                if (MediaPlayer.DEBUG) logger.debug("$debugLabel First frame $w x $h (javaCPP).")
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

    // ── Frame conversion ──────────────────────────────────────────────────

    /**
     * Converts a grabbed frame into I420 planes packed into [dst] (Y, then U, then V).
     *
     * **Why this bypasses [Frame.image]:** javacv's `imageMode = RAW` only fills
     * `frame.image[0]` (Y plane), leaving U/V as null. To get a complete 3-plane
     * YUV frame we use the standard FFmpeg sws_scale API on the underlying AVFrame
     * stored in `frame.opaque` — exactly how `ffmpeg -vf scale` works. This also
     * handles any pixel format conversion and resolution scaling automatically.
     */
    private fun frameToI420(frame: Frame, dst: ByteBuffer, expectedW: Int, expectedH: Int): Boolean {
        val src = frame.opaque as? AVFrame ?: return false
        val srcW = src.width()
        val srcH = src.height()
        val srcFmt = src.format()
        if (srcW <= 0 || srcH <= 0 || srcFmt < 0) return false

        // Lazy-allocate or re-size the target I420 AVFrame
        if (dstAvFrame == null || dstAvW != expectedW || dstAvH != expectedH) {
            dstAvFrame?.let { avutil.av_frame_free(it) }
            val f = avutil.av_frame_alloc() ?: return false
            f.format(avutil.AV_PIX_FMT_YUV420P)
            f.width(expectedW)
            f.height(expectedH)
            if (avutil.av_frame_get_buffer(f, 32) < 0) {
                avutil.av_frame_free(f)
                return false
            }
            dstAvFrame = f
            dstAvW = expectedW; dstAvH = expectedH
        }

        // Lazy-create or update the sws scaler
        val sws = if (swsCtx != null && swsSrcW == srcW && swsSrcH == srcH && swsSrcFmt == srcFmt) {
            swsCtx
        } else {
            swsCtx?.let { swscale.sws_freeContext(it) }
            val ctx = swscale.sws_getCachedContext(
                null as SwsContext?, srcW, srcH, srcFmt, expectedW, expectedH,
                avutil.AV_PIX_FMT_YUV420P, swscale.SWS_BILINEAR,
                null as SwsFilter?, null as SwsFilter?, null as DoublePointer?
            ) ?: return false
            swsCtx = ctx
            swsSrcW = srcW; swsSrcH = srcH; swsSrcFmt = srcFmt
            ctx
        }

        // sws_scale_frame: standard FFmpeg approach — one call handles pixel format
        // conversion + resolution scaling, same as `ffmpeg -vf scale=WxH,format=yuv420p`
        val out = dstAvFrame ?: return false
        if (swscale.sws_scale_frame(sws, out, src) < 0) return false

        // Copy the 3 planes from the output AVFrame into the packed I420 [dst] buffer
        val yDst = out.data(0)?.asBuffer() ?: return false
        val uDst = out.data(1)?.asBuffer() ?: return false
        val vDst = out.data(2)?.asBuffer() ?: return false

        copyPlane(yDst, out.linesize(0), expectedW, expectedH, dst)
        copyPlane(uDst, out.linesize(1), (expectedW + 1) / 2, (expectedH + 1) / 2, dst)
        copyPlane(vDst, out.linesize(2), (expectedW + 1) / 2, (expectedH + 1) / 2, dst)
        return true
    }

    /**
     * Copies one plane from [src] ByteBuffer (with stride [srcStride]) into [dst],
     * trimming each row to [rowBytes] for [rows] rows. Handles padding / alignment
     * bytes that ffmpeg adds at the end of each row.
     */
    private fun copyPlane(src: ByteBuffer, srcStride: Int, rowBytes: Int, rows: Int, dst: ByteBuffer) {
        if (srcStride <= 0 || rowBytes <= 0 || rows <= 0) return
        if (srcStride == rowBytes) {
            val total = rowBytes * rows
            val limit = minOf(total, src.remaining())
            for (i in 0 until limit) {
                dst.put(src.get(i))
            }
        } else {
            for (row in 0 until rows) {
                val rowStart = row * srcStride
                val limit = minOf(rowStart + rowBytes, src.remaining())
                for (col in 0 until rowBytes) {
                    if (rowStart + col >= limit) break
                    dst.put(src.get(rowStart + col))
                }
            }
        }
    }

    /**
     * Copies a frame's BGR data into an RGB24 [dst] buffer.
     * Returns false when dimensions don't match.
     */
    private fun frameToRgb24(frame: Frame, dst: ByteBuffer, expectedW: Int, expectedH: Int): Boolean {
        val img = frame.image ?: return false
        if (img.isEmpty()) return false
        val src = (img[0] as? ByteBuffer) ?: return false
        val fw = frame.imageWidth
        val fh = frame.imageHeight
        if (fw != expectedW || fh != expectedH) return false

        val stride = frame.imageStride
        if (stride == fw * 3 || stride <= 0) {
            // BGR to RGB24: swap R and B for each pixel
            val limit = src.remaining()
            var i = 0
            while (i + 2 < limit) {
                val b = src.get(i).toInt() and 0xFF
                val g = src.get(i + 1).toInt() and 0xFF
                val r = src.get(i + 2).toInt() and 0xFF
                dst.put(r.toByte())
                dst.put(g.toByte())
                dst.put(b.toByte())
                i += 3
            }
        } else {
            // Strided: copy row by row with BGR→RGB per pixel
            for (row in 0 until fh) {
                val rowStart = row * stride
                for (col in 0 until fw) {
                    val idx = rowStart + col * 3
                    if (idx + 2 >= src.limit()) break
                    val b = src.get(idx).toInt() and 0xFF
                    val g = src.get(idx + 1).toInt() and 0xFF
                    val r = src.get(idx + 2).toInt() and 0xFF
                    dst.put(r.toByte())
                    dst.put(g.toByte())
                    dst.put(b.toByte())
                }
            }
        }
        return true
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
        val uPlane = i420.duplicate().position(yLen).limit(yLen + uvLen) as ByteBuffer
        val vPlane = i420.duplicate().position(yLen + uvLen).limit(yLen + 2 * uvLen) as ByteBuffer

        for (row in 0 until h) {
            for (col in 0 until w) {
                val y = i420.get(row * w + col).toInt() and 0xFF
                val u = uPlane.get((row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF
                val v = vPlane.get((row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF

                // YUV to RGB (BT.601)
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

    // ── Grabber helpers ───────────────────────────────────────────────────

    /** Creates and configures a new FFmpegFrameGrabber for [url]. */
    @Throws(Exception::class)
    private fun createGrabber(url: String, w: Int, h: Int, seekOffsetNanos: Long): FFmpegFrameGrabber {
        val g = FFmpegFrameGrabber(url)
        g.setOption("probesize", "1M")
        g.setOption("analyzeduration", "1000000")
        g.setOption("rw_timeout", "15000000")
        g.setOption("user_agent", USER_AGENT)
        // Platform CDNs (e.g. Bilibili's bilivideo.com) answer 403 without the right Referer;
        // a host a player pasted gets none and stays anonymous, matching the old CLI pipeline.
        MediaHosts.refererFor(url)?.let { g.setOption("headers", "Referer: $it\r\n") }
        g.setOption("multiple_requests", "1")
        g.setOption("reconnect", "1")
        g.setOption("reconnect_streamed", "1")
        g.setOption("reconnect_delay_max", "10")
        g.setOption("reconnect_on_network_error", "1")
        g.setOption("reconnect_on_http_error", "5xx")
        // Set output dimensions
        g.imageWidth = w
        g.imageHeight = h
        if (planarOutput) {
            // RAW mode: javacv fills only frame.image[0] (Y plane) and leaves U/V null,
            // so we cannot use frame.image -> frameToI420. Instead we read the underlying
            // AVFrame from frame.opaque and use standard FFmpeg sws_scale for conversion.
            // Keep imageWidth/imageHeight so the Frame metadata is accurate for the EOS
            // check and diagnostic logging; the actual YUV conversion bypasses Frame.image.
            g.imageMode = FrameGrabber.ImageMode.RAW
        } else {
            // Request BGR24 for RGB output (javacv's default)
        }
        g.start()
        // Seek after start
        if (seekOffsetNanos > 0) {
            // Due to FFmpeg seeking behavior, seek then flush
            g.setTimestamp(seekOffsetNanos / 1000L) // convert nanos to micros
        }
        return g
    }

    /** Frame rate assumption for sources without a valid FPS. */
    private fun outputFps(sourceFps: Double): Double =
        sourceFps.takeIf { it.isFinite() && it > 1.0 && it <= 240.0 } ?: 30.0
}