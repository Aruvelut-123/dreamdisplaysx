@file:Suppress("Since15")

package com.dreamdisplayx.media.player.pipeline

import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.model.StretchMode
import com.dreamdisplayx.api.media.player.FrameUploaderFactory
import com.dreamdisplayx.api.media.player.GpuTextureRef
import com.dreamdisplayx.api.security.policy.MediaHosts
import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.media.player.process.HwAccelEnumerator
import com.dreamdisplayx.media.player.util.daemon
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.ffmpeg.swscale.SwsFilter
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

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
    /** FFmpeg hwaccel backend names to try for decode, in priority order; empty = software only. */
    private val hwAccelCandidates: List<String> = emptyList(),
) : FramePipe {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/JavaCppVideoPipe")

    companion object {
        private const val PARK_POLL_MS = 2L
        /** Max time to wait for a grabber to stop on kill. */
        private const val STOP_TIMEOUT_MS = 500L
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        init {
            // Suppress FFmpeg's av_log INFO messages ("Option: ... rejected") and full stream dumps,
            // matching the old FFmpeg CLI's `-loglevel error`. These logs are noisy on every open
            // (e.g. "avformat_open_input rejected some options: pixel_format, value: bgr24") and
            // contain no actionable information.
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        }
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

    /** PTS of the most recently decoded frame; captured once when audio first catches up, so the
     *  audio clock can be anchored to the video's real decoded progress instead of the seek offset. */
    @Volatile
    var lastDecodedPtsNanos: Long = Long.MIN_VALUE
        private set

    private val surface = FrameSurface(debugLabel, uploaderFactory, FramePixelFormat.RGB24)

    @Volatile
    private var activePrebuffer: FramePrebuffer? = null

    /** The FFmpegFrameGrabber used for the current session. Replaced on seek / restart. */
    @Volatile
    private var grabber: FFmpegFrameGrabber? = null

    /** URL currently open in [grabber], used to decide whether an in-place seek is possible. */
    @Volatile
    internal var currentUrl: String? = null
        private set

    /**
     * When non-null, the reader loop counts down this latch after the first frame following an
     * in-place seek is presented, so the audio half waits for the video to be ready (same as the
     * full channel-swap path). Set / cleared by [requestInPlaceSeek] and [doInPlaceSeek].
     */
    @Volatile
    private var seekFirstFrameLatch: CountDownLatch? = null

    /** Pending in-place seek target in nanos; [Long.MIN_VALUE] = none. The reader loop consumes and applies it. */
    private val seekRequestedNanos = AtomicLong(Long.MIN_VALUE)

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

    /** sws scaler: source crop/size → 目标格式(根据输出模式) 尺寸. */
    private var swsCtx: SwsContext? = null
    private var swsSrcW = 0; private var swsSrcH = 0; private var swsSrcFmt = -1
    private var swsDstW = 0; private var swsDstH = 0; private var swsDstFmt = -1

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
        getStretchMode: () -> StretchMode = { StretchMode.LETTERBOX },
        onEos: (stderr: String, normalEos: Boolean) -> Unit,
        parkFlag: AtomicBoolean? = null,
        presentPreview: Boolean = true,
        tolerateLateness: Boolean = true,
        /** Called when the prebuffer drops a frame because the audio clock is > 5 s ahead of the video PTS. */
        onDriftResync: (() -> Unit)? = null,
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
                read(w, h, frameNs, seekOffsetNanos, stopFlag, terminated, getAudioClock, onFirstFrame,
                    getBrightness, getStretchMode, onEos, prebuffer, presentPreview)
            },
            "MediaPlayer-video",
        ).also { it.start() }
        readerThread = thread
        return thread
    }

    /**
     * Requests an in-place seek on the existing grabber (no re-open, no re-probe).
     * The reader loop picks this up at the next frame boundary and applies
     * [g.setTimestamp] directly on the same grabber. Returns true when a seek was
     * scheduled; false if the pipe has no active grabber to seek on.
     *
     * @param firstFrameLatch when non-null, counted down after the first frame of the new
     *   timeline is presented (used as the audio start gate).
     */
    fun requestInPlaceSeek(offsetNanos: Long, firstFrameLatch: CountDownLatch? = null): Boolean {
        if (grabber == null) return false
        seekRequestedNanos.set(offsetNanos)
        seekFirstFrameLatch = firstFrameLatch
        return true
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
        // Free sws scaler context
        swsCtx?.let { swscale.sws_freeContext(it) }
        swsCtx = null
        swsSrcW = 0; swsSrcH = 0; swsSrcFmt = -1; swsDstW = 0; swsDstH = 0; swsDstFmt = -1
        i420Scratch = null
        rgbScratch = null
    }

    // ── Reader loop ───────────────────────────────────────────────────────

    private fun read(
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

        while (!terminated.get() && !stopFlag.get()) {
            // In-place seek: when the session manager requests a new position on the same
            // grabber, seek it directly and reset the pipeline state instead of killing the
            // old pipe and creating a new one (which re-opens the network connection and
            // re-probes the stream, both slow and failure-prone).
            val seekTo = seekRequestedNanos.getAndSet(Long.MIN_VALUE)
            if (seekTo != Long.MIN_VALUE) {
                doInPlaceSeek(seekTo, surface, prebuffer, onFirstFrame)
                // Reset the per-seek locals so the loop continues from the new position.
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

            val g = grabber ?: break

            val frame: Frame
            try {
                frame = g.grabImage()
                if (frame == null) {
                    // grabImage() returns null at end of stream (not an error).
                    // This is a clean EOS, not "Unknown error".
                    normalEos = true
                    break
                }
            } catch (e: Exception) {
                if (!terminated.get() && !stopFlag.get()) {
                    // JavaCV's grabImage() declares @NotNull but the native FFmpeg layer can
                    // return null at end of stream — the Kotlin/Java null check then throws
                    // "grabImage() must not be null" (NullPointerException). Treat that as a
                    // clean EOS, not an unrecoverable error.
                    if (e.message?.contains("must not be null") == true || e is NullPointerException) {
                        normalEos = true
                        break
                    }
                    errorMessage = e.message ?: "grabImage failed"
                    logger.warn("$debugLabel grabImage: ${errorMessage}")
                }
                break
            }

            // Re-check stopped condition after blocking call
            if (terminated.get() || stopFlag.get()) break
            // With imageMode = RAW the frame.image field is null; actual pixel data
            // lives in frame.opaque (AVFrame). If the AVFrame is also missing, treat
            // it as end-of-stream.
            val av = frame.opaque as? AVFrame
            if (av == null) {
                normalEos = true
                break
            }

            // Record the first frame's raw PTS for exact A/V bias anchoring. FFmpegFrameGrabber
            // timestamps are in microseconds, while the audio clock (pacingClockNanos) is in
            // nanoseconds, so convert here once.
            if (firstRawPtsNanos == Long.MIN_VALUE && frame.timestamp > 0) {
                firstRawPtsNanos = frame.timestamp * 1000L
            }

            spare.clear()
            val mode = getStretchMode()
            if (planarOutput) {
                val success = frameToI420(frame, spare, w, h, mode)
                if (!success) {
                    logger.warn("$debugLabel Skipped frame: unexpected dimensions ${frame.imageWidth}x${frame.imageHeight}")
                    videoPts += frameNs
                    continue
                }
            } else {
                val success = frameToRgb24(frame, spare, w, h, mode)
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

            // Use the frame's timestamp if available, otherwise synthetic. Convert µs → ns so the
            // prebuffer / pacing (which compare against the nanosecond audio clock) see correct drift.
            val framePts = if (frame.timestamp > 0) frame.timestamp * 1000L else videoPts
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
    private fun frameToI420(frame: Frame, dst: ByteBuffer, expectedW: Int, expectedH: Int, stretchMode: StretchMode): Boolean {
        val src = frame.opaque as? AVFrame ?: return false
        val srcW = src.width()
        val srcH = src.height()
        val srcFmt = src.format()
        if (srcW <= 0 || srcH <= 0 || srcFmt < 0) return false

        val dstFmt = avutil.AV_PIX_FMT_YUV420P
        val plan = ScalingPlan.compute(srcW, srcH, expectedW, expectedH, stretchMode, srcFmt)

        // Lazy-create or update the sws scaler — keyed on source crop size + destination fit size
        val sws = if (swsCtx != null && swsSrcW == plan.srcW && swsSrcH == plan.srcH && swsSrcFmt == srcFmt
            && swsDstW == plan.dstW && swsDstH == plan.dstH && swsDstFmt == dstFmt
        ) {
            swsCtx
        } else {
            swsCtx?.let { swscale.sws_freeContext(it) }
            val ctx = swscale.sws_getCachedContext(
                null as SwsContext?, plan.srcW, plan.srcH, srcFmt,
                plan.dstW, plan.dstH, dstFmt, swscale.SWS_BILINEAR,
                null as SwsFilter?, null as SwsFilter?, null as DoublePointer?
            ) ?: return false
            swsCtx = ctx
            swsSrcW = plan.srcW; swsSrcH = plan.srcH; swsSrcFmt = srcFmt
            swsDstW = plan.dstW; swsDstH = plan.dstH; swsDstFmt = dstFmt
            ctx
        }

        // Build destination plane pointers into the packed I420 [dst] buffer.
        val ySize = expectedW * expectedH
        val uvSize = ((expectedW + 1) / 2) * ((expectedH + 1) / 2)

        // For LETTERBOX: fill the whole buffer with black (limited-range Y=16, U=V=128)
        // before writing the scaled video into the centred sub-rectangle.
        if (plan.pad) {
            fillI420Black(dst, ySize, uvSize)
        }

        // Offset destination pointers into the letterboxed area
        val dstY = BytePointer(dst.sliceView(plan.dstOffY * expectedW + plan.dstOffX, plan.dstW * plan.dstH))
        val dstU = BytePointer(dst.sliceView(
            ySize + (plan.dstOffY / 2) * ((expectedW + 1) / 2) + (plan.dstOffX / 2),
            ((plan.dstW + 1) / 2) * ((plan.dstH + 1) / 2),
        ))
        val dstV = BytePointer(dst.sliceView(
            ySize + uvSize + (plan.dstOffY / 2) * ((expectedW + 1) / 2) + (plan.dstOffX / 2),
            ((plan.dstW + 1) / 2) * ((plan.dstH + 1) / 2),
        ))

        // Source plane pointers — offset horizontally for CROP mode
        val srcPtrs = (0 until 4).mapNotNull { i -> src.data(i)?.takeIf { !it.isNull } }.toTypedArray()
        if (srcPtrs.isEmpty()) return false
        val srcOffBytes = plan.srcOffX * plan.srcPlaneBpp
        val srcOffBytesUV = plan.srcOffX * plan.srcPlaneBpp / 2
        val srcSlices = PointerPointer<BytePointer>(
            *srcPtrs.mapIndexed { idx, ptr ->
                if (plan.srcOffX <= 0) ptr
                else if (idx == 0) BytePointer(ptr).position(srcOffBytes.toLong()) as BytePointer
                else BytePointer(ptr).position(srcOffBytesUV.toLong()) as BytePointer
            }.toTypedArray()
        )
        val srcStrides = IntPointer(*IntArray(srcPtrs.size) { src.linesize(it) })
        val dstSlices = PointerPointer<BytePointer>(dstY, dstU, dstV)
        // Destination strides are the FULL buffer line width, so sws_scale writes only
        // plan.dstW pixels per line into the centred sub-rectangle, leaving the black padding.
        val dstStrides = IntPointer(expectedW, (expectedW + 1) / 2, (expectedW + 1) / 2)

        val linesScaled = swscale.sws_scale(sws, srcSlices, srcStrides, plan.srcOffY, plan.srcH, dstSlices, dstStrides)
        if (linesScaled < 0) return false

        dst.position(ySize + 2 * uvSize)
        return true
    }

    /** Fills an I420 buffer with limited-range black (Y=16, U=128, V=128). */
    private fun fillI420Black(buf: ByteBuffer, ySize: Int, uvSize: Int) {
        buf.position(0)
        repeat(ySize) { buf.put(16.toByte()) }
        repeat(uvSize) { buf.put(128.toByte()) }
        repeat(uvSize) { buf.put(128.toByte()) }
        buf.rewind()
    }

    /** Fills an RGB24 buffer with black (0, 0, 0). */
    private fun fillRgb24Black(buf: ByteBuffer, rgbSize: Int) {
        buf.position(0)
        repeat(rgbSize) { buf.put(0.toByte()) }
        buf.rewind()
    }

    /** Describes how to scale one frame — which source rectangle to use and where to place it in the output. */
    internal data class ScalingPlan(
        /** Source crop width (for CROP) or full width (for STRETCH/LETTERBOX). */
        val srcW: Int, val srcH: Int,
        /** Horizontal source pixel offset (CROP mode only). */
        val srcOffX: Int,
        /** Vertical source row offset (CROP mode only). */
        val srcOffY: Int,
        /** Bytes per pixel in the source plane 0, for pointer offset calculation. */
        val srcPlaneBpp: Int,
        /** Destination video area width (fitted, for LETTERBOX == full width for STRETCH/CROP). */
        val dstW: Int, val dstH: Int,
        /** Horizontal offset of the video area in the destination buffer (LETTERBOX mode only). */
        val dstOffX: Int, val dstOffY: Int,
        /** True when the destination buffer must be pre-filled with black (LETTERBOX mode). */
        val pad: Boolean,
    ) {
        companion object {
            fun compute(srcW: Int, srcH: Int, expectedW: Int, expectedH: Int, mode: StretchMode, srcFmt: Int): ScalingPlan {
                return when (mode) {
                    StretchMode.STRETCH -> ScalingPlan(
                        srcW = srcW, srcH = srcH, srcOffX = 0, srcOffY = 0,
                        srcPlaneBpp = planeBytesPerPixel(srcFmt),
                        dstW = expectedW, dstH = expectedH, dstOffX = 0, dstOffY = 0, pad = false,
                    )
                    StretchMode.LETTERBOX -> {
                        val scale = minOf(expectedW.toDouble() / srcW, expectedH.toDouble() / srcH)
                        val fitW = (srcW * scale).roundToInt().coerceAtLeast(1)
                        val fitH = (srcH * scale).roundToInt().coerceAtLeast(1)
                        // Align offsets to even for chroma subsampling
                        val offX = ((expectedW - fitW) / 2) and 1.inv()
                        val offY = ((expectedH - fitH) / 2) and 1.inv()
                        ScalingPlan(
                            srcW = srcW, srcH = srcH, srcOffX = 0, srcOffY = 0,
                            srcPlaneBpp = planeBytesPerPixel(srcFmt),
                            dstW = fitW, dstH = fitH, dstOffX = offX, dstOffY = offY, pad = true,
                        )
                    }
                    StretchMode.CROP -> {
                        val scale = maxOf(expectedW.toDouble() / srcW, expectedH.toDouble() / srcH)
                        val cropW = (expectedW / scale).roundToInt().coerceIn(1, srcW)
                        val cropH = (expectedH / scale).roundToInt().coerceIn(1, srcH)
                        val offX = ((srcW - cropW) / 2) and 1.inv()
                        val offY = ((srcH - cropH) / 2) and 1.inv()
                        ScalingPlan(
                            srcW = cropW, srcH = cropH, srcOffX = offX, srcOffY = offY,
                            srcPlaneBpp = planeBytesPerPixel(srcFmt),
                            dstW = expectedW, dstH = expectedH, dstOffX = 0, dstOffY = 0, pad = false,
                        )
                    }
                }
            }

            /** Rough bytes per pixel for plane 0 of the given pixel format — used for CROP horizontal offset. */
            private fun planeBytesPerPixel(fmt: Int): Int = when (fmt) {
                avutil.AV_PIX_FMT_YUV420P, avutil.AV_PIX_FMT_YUVJ420P -> 1
                avutil.AV_PIX_FMT_NV12 -> 1
                avutil.AV_PIX_FMT_RGB24, avutil.AV_PIX_FMT_BGR24 -> 3
                avutil.AV_PIX_FMT_RGBA, avutil.AV_PIX_FMT_BGRA,
                avutil.AV_PIX_FMT_ARGB, avutil.AV_PIX_FMT_ABGR -> 4
                else -> 1
            }
        }
    }

    /** Returns a view of [buffer] covering exactly [length] bytes starting at [offset] (position exclusive view). */
    private fun ByteBuffer.sliceView(offset: Int, length: Int): ByteBuffer {
        val view = duplicate()
        view.position(offset).limit(offset + length)
        return view as ByteBuffer
    }

    /**
     * Converts a grabbed frame into an RGB24 buffer packed into [dst] using sws_scale.
     * Uses the same pattern as [frameToI420]: reads from the underlying AVFrame
     * (frame.opaque) and writes directly into the destination buffer.
     *
     * This avoids relying on javacv's imageMode = COLOR, which would set the
     * codec pixel_format option on avformat_open_input (producing the spurious
     * "avformat_open_input rejected some options: pixel_format, value: bgr24"
     * info log). With imageMode = RAW + sws_scale, we handle the conversion
     * ourselves and no pixel_format option is ever set on the format context.
     */
    private fun frameToRgb24(frame: Frame, dst: ByteBuffer, expectedW: Int, expectedH: Int, stretchMode: StretchMode): Boolean {
        val src = frame.opaque as? AVFrame ?: return false
        val srcW = src.width()
        val srcH = src.height()
        val srcFmt = src.format()
        if (srcW <= 0 || srcH <= 0 || srcFmt < 0) return false

        val dstFmt = avutil.AV_PIX_FMT_RGB24
        val plan = ScalingPlan.compute(srcW, srcH, expectedW, expectedH, stretchMode, srcFmt)

        // Lazy-create or update the sws scaler for RGB24 output — keyed on source crop + dest fit size
        val sws = if (swsCtx != null && swsSrcW == plan.srcW && swsSrcH == plan.srcH && swsSrcFmt == srcFmt
            && swsDstW == plan.dstW && swsDstH == plan.dstH && swsDstFmt == dstFmt
        ) {
            swsCtx
        } else {
            swsCtx?.let { swscale.sws_freeContext(it) }
            val ctx = swscale.sws_getCachedContext(
                null as SwsContext?, plan.srcW, plan.srcH, srcFmt,
                plan.dstW, plan.dstH, dstFmt, swscale.SWS_BILINEAR,
                null as SwsFilter?, null as SwsFilter?, null as DoublePointer?
            ) ?: return false
            swsCtx = ctx
            swsSrcW = plan.srcW; swsSrcH = plan.srcH; swsSrcFmt = srcFmt
            swsDstW = plan.dstW; swsDstH = plan.dstH; swsDstFmt = dstFmt
            ctx
        }

        val rgbSize = expectedW * expectedH * 3
        if (plan.pad) fillRgb24Black(dst, rgbSize)
        // Destination plane offset into the letterboxed area
        val dstPlane = BytePointer(
            dst.sliceView((plan.dstOffY * expectedW + plan.dstOffX) * 3, plan.dstW * plan.dstH * 3),
        )

        // Source plane pointers from the decoded AVFrame — horizontally offset for CROP mode
        val srcY = src.data(0) ?: return false
        val srcU = if (srcFmt == avutil.AV_PIX_FMT_YUV420P || srcFmt == avutil.AV_PIX_FMT_YUVJ420P) src.data(1) else null
        val srcV = if (srcU != null) src.data(2) else null
        val offY = plan.srcOffX * plan.srcPlaneBpp
        val offUV = plan.srcOffX * plan.srcPlaneBpp / 2
        val srcSlices = if (srcU != null && srcV != null) {
            val yp = if (offY <= 0) srcY else BytePointer(srcY).position(offY.toLong()) as BytePointer
            val up = if (offUV <= 0) srcU else BytePointer(srcU).position(offUV.toLong()) as BytePointer
            val vp = if (offUV <= 0) srcV else BytePointer(srcV).position(offUV.toLong()) as BytePointer
            PointerPointer<BytePointer>(yp, up, vp)
        } else {
            val yp = if (offY <= 0) srcY else BytePointer(srcY).position(offY.toLong()) as BytePointer
            PointerPointer<BytePointer>(yp)
        }
        val srcStrides = if (srcU != null && srcV != null) {
            IntPointer(src.linesize(0), src.linesize(1), src.linesize(2))
        } else {
            IntPointer(src.linesize(0))
        }

        val dstSlices = PointerPointer<BytePointer>(dstPlane)
        val dstStrides = IntPointer(expectedW * 3)

        val linesScaled = swscale.sws_scale(sws, srcSlices, srcStrides, plan.srcOffY, plan.srcH, dstSlices, dstStrides)
        if (linesScaled < 0) return false

        dst.position(rgbSize)
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

        // NB: ByteBuffer.get(int) is an ABSOLUTE index into the buffer's own storage — a
        // duplicate()'d view starts at 0, not at its current position. Reading through a
        // repositioned view therefore indexes the Y plane for U/V too (green/pink popout).
        // Use absolute offsets into the original buffer instead.
        for (row in 0 until h) {
            for (col in 0 until w) {
                val y = i420.get(row * w + col).toInt() and 0xFF
                val u = i420.get(yLen + (row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF
                val v = i420.get(yLen + uvLen + (row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF

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

    /**
     * Creates and configures a new FFmpegFrameGrabber for [url].
     *
     * Hardware decode: each [hwAccelCandidates] backend is verified against the FFmpeg build
     * (`av_hwdevice_iterate_types`) before use — backends the build doesn't know are skipped.
     * If a candidate opens but fails to decode at probe time, the next candidate is tried.  When
     * all candidates fail (no driver, no device, unsupported format), a plain software grabber is
     * used — the same behavior as an empty candidate list.
     */
    @Throws(Exception::class)
    private fun createGrabber(url: String, w: Int, h: Int, seekOffsetNanos: Long): FFmpegFrameGrabber {
        for (hwaccel in hwAccelCandidates) {
            if (!HwAccelEnumerator.isSupported(hwaccel)) {
                logger.debug("{} Skipping hwaccel '{}': not compiled into this FFmpeg build.", debugLabel, hwaccel)
                continue
            }
            try {
                return createHwAccelGrabber(url, w, h, seekOffsetNanos, hwaccel)
            } catch (e: Exception) {
                logger.warn("{} Hwaccel '{}' unusable: {} — trying next candidate / software.", debugLabel, hwaccel, e.message)
            }
        }
        logger.info("{} Using software video decode (hwaccel: {}).", debugLabel, hwAccelCandidates.ifEmpty { listOf("none") })
        return createSoftwareGrabber(url, w, h, seekOffsetNanos)
    }

    /** Opens a software (no hwaccel options) grabber. */
    @Throws(Exception::class)
    private fun createSoftwareGrabber(url: String, w: Int, h: Int, seekOffsetNanos: Long): FFmpegFrameGrabber {
        val g = FFmpegFrameGrabber(url)
        configureBaseOptions(g, url, seekOffsetNanos)
        g.imageWidth = w
        g.imageHeight = h
        g.imageMode = FrameGrabber.ImageMode.RAW
        g.start()
        seekAfterStart(g, seekOffsetNanos)
        MediaPlayer.currentDecoder.set("software")
        return g
    }

    /**
     * Opens a grabber with FFmpeg's `hwaccel` option set to [hwaccel], requesting yuv420p output
     * so hardware-decoded frames are transferred back to system memory in the planar YUV420P
     * format the existing sws_scale pipeline already understands (avoiding NV12 plane handling).
     * A single probe frame proves the backend actually decodes before the grabber is handed over.
     */
    @Throws(Exception::class)
    private fun createHwAccelGrabber(
        url: String, w: Int, h: Int, seekOffsetNanos: Long, hwaccel: String,
    ): FFmpegFrameGrabber {
        val g = FFmpegFrameGrabber(url)
        configureBaseOptions(g, url, seekOffsetNanos)
        g.setOption("hwaccel", hwaccel)
        g.setOption("hwaccel_output_format", "yuv420p")
        g.imageWidth = w
        g.imageHeight = h
        g.imageMode = FrameGrabber.ImageMode.RAW
        g.start()
        // Probe: opening may succeed even when the driver/device can't decode (no driver, no
        // hardware frames). Grabbing one frame proves usability; the frame is discarded.
        val probe = g.grabImage()
        if (probe == null) {
            throw java.io.IOException("hwaccel '$hwaccel' produced no frame")
        }
        // Re-apply the seek target: the probe consumed the first frame(s), so rewind to the
        // intended position so the reader loop presents the same timeline as the software path.
        seekAfterStart(g, seekOffsetNanos)
        MediaPlayer.currentDecoder.set(hwaccel)
        logger.info("{} Video decode via hwaccel '{}'.", debugLabel, hwaccel)
        return g
    }

    /** Sets the shared format-level options for a new grabber. */
    private fun configureBaseOptions(g: FFmpegFrameGrabber, url: String, seekOffsetNanos: Long) {
        // Smaller probe for faster session startup — the CLI's `-ss before -i` approach only
        // needs enough data to find the stream header; 128K probesize is enough for most
        // network VODs and cuts the initial open time by roughly half vs 256K.
        g.setOption("probesize", "128K")
        g.setOption("analyzeduration", "100000")
        g.setOption("rw_timeout", "5000000")
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
        // For VOD seeks, hint the HTTP protocol that the server answers Range requests so FFmpeg
        // jumps straight to the target offset instead of downloading the stream from the beginning
        // and discarding everything up to it (which is what made seeks take seconds).
        // Set this BEFORE start() so the very first probe phase uses Range requests too.
        if (seekOffsetNanos > 0) {
            g.setOption("seekable", "1")
        }
    }

    /** Applies the post-start seek (nanos → micros) when [seekOffsetNanos] is positive. */
    private fun seekAfterStart(g: FFmpegFrameGrabber, seekOffsetNanos: Long) {
        if (seekOffsetNanos > 0) {
            // Due to FFmpeg seeking behavior, seek then flush
            g.setTimestamp(seekOffsetNanos / 1000L) // convert nanos to micros
        }
    }

    /** Frame rate assumption for sources without a valid FPS. */
    private fun outputFps(sourceFps: Double): Double =
        sourceFps.takeIf { it.isFinite() && it > 1.0 && it <= 240.0 } ?: 30.0

    /**
     * Applies an in-place seek to [offsetNanos] on the existing grabber: seeks the already-open
     * stream (no network re-open, no re-probe) and resets the frame pipeline so playout restarts
     * at the new position. Runs on the reader thread; [prebuffer] / [surface] are reader-owned.
     */
    private fun doInPlaceSeek(
        offsetNanos: Long,
        surface: FrameSurface,
        prebuffer: FramePrebuffer?,
        onFirstFrame: () -> Unit,
    ) {
        val g = grabber ?: return
        try {
            // Seek the same grabber directly (nanos -> micros), exactly like the initial open.
            g.setTimestamp(offsetNanos / 1000L)
            logger.debug("$debugLabel In-place seek to ${offsetNanos / 1_000_000} ms on existing grabber.")
            if (prebuffer != null) {
                // Prebuffer path: the seek's audio latch opens inside the wrapped first-frame
                // callback below, which the prebuffer consumer fires when it presents the first
                // frame of the new timeline.
                val seekLatch = seekFirstFrameLatch
                seekFirstFrameLatch = null
                prebuffer.resetForSeek {
                    onFirstFrame()
                    seekLatch?.countDown()
                }
            }
            // Non-prebuffer path: keep seekFirstFrameLatch set so the reader loop counts it down
            // right after onFirstFrame() fires for the first post-seek frame.
        } catch (e: Exception) {
            logger.warn("$debugLabel In-place seek to ${offsetNanos / 1_000_000} ms failed: ${e.message}")
            // Release the audio gate so the player doesn't deadlock with silence when the in-place
            // seek fails; the caller falls through to a full channel-swap (or error) which rebuilds
            // the audio half with its own latch.
            seekFirstFrameLatch?.countDown()
            seekFirstFrameLatch = null
            // Fall back to a hard teardown; caller goes through the full re-open path.
            try { g.stop() } catch (_: Exception) { }
            return
        }
        surface.clear()
        // PTS anchoring of the new timeline starts over.
    }
}