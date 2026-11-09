/**
 * Android MediaCodec 硬解解码器（FFmpeg mediacodec 后端，NDK 路径优先）。
 *
 * ## 为什么这样写
 *
 * 本类只做一件事：把 H.264/H.265 码流喂给 FFmpeg 的 `h264_mediacodec` / `hevc_mediacodec`
 * 解码器，拿回 NV12/YUV420P 帧，转成统一的 I420（YUV420P）紧凑缓冲交给现有渲染管线。
 *
 * 关键设计约束（与任务书一致）：
 *
 * 1. **不引用任何 `android.*` 类**。JavaCPP 的 `org.bytedeco.ffmpeg.*` 是纯 JNI 绑定，NDK
 *    后端（libmediandk.so → `AMediaCodec_*`）在 native 层完成与系统 MediaCodec 服务的交互。
 *    因此本类在 Pojav / Zalith 等「桌面 OpenJDK、没有 android.jar」的游戏 JVM 上也能加载。
 * 2. **桌面端永不进入**。入口由 [FfmpegMediaCodecBridge.isAvailable] 把关：非 Android 直接 false。
 * 3. **失败必须可回退**。任何一步失败都返回 false / 错误枚举并写入 [lastError]，由上层
 *    （[FfmpegCodecScheduler]）决定是否退回 libvlc；绝不抛异常穿透到播放线程。
 *
 * ## 已验证的 FFmpeg 构建能力
 *
 * 使用的 `org.bytedeco:ffmpeg:8.1.2-1.5.14:android-arm64` 预编译包经 `readelf` / `strings` 核实：
 * - configure 行含 `--enable-mediacodec --enable-jni`
 * - `libavcodec.so` 的 `DT_NEEDED` 含 `libmediandk.so`、`libandroid.so`
 * - 含全部 `AMediaCodec_*` NDK 符号，且注册了 `h264_mediacodec` / `hevc_mediacodec` /
 *   `vp8_mediacodec` / `vp9_mediacodec` / `av1_mediacodec` / `mpeg4_mediacodec`
 * - 含选项 `ndk_codec`（官方日志 "Doesn't support aync mode with JNI, please try ndk_codec=1"）
 *
 * 因此 `ndk_codec=1` 是真实存在的选项，不是猜测。
 */
package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.util.log.DreamLog
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单个 MediaCodec 硬解会话。
 *
 * 生命周期：`open()` → 反复 `decode()` → `close()`。非线程安全，调用方需保证串行
 * （播放器里由固定的解码线程持有）。
 *
 * @param width  码流原始宽度（必须与 SPS 一致，否则 MediaCodec 配置会失败）
 * @param height 码流原始高度
 * @param decoderName FFmpeg 解码器名，H.264 用 `h264_mediacodec`，H.265 用 `hevc_mediacodec`
 */
internal class AndroidMediaCodecHwSolver(
    private val width: Int,
    private val height: Int,
    private val decoderName: String = DEFAULT_DECODER,
) : AutoCloseable {

    private val logger = DreamLog.of("AndroidMediaCodecHwSolver")

    private val opened = AtomicBoolean(false)

    /** FFmpeg 解码上下文，`close()` 时释放。 */
    private var ctx: AVCodecContext? = null
    /** 复用的 packet / frame，避免每个码流包都做一次 native 分配。 */
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null

    /**
     * 拷贝硬件帧（GPU surface）到系统内存时用的中转帧。
     *
     * FFmpeg 的 `h264_mediacodec` 输出 `AV_PIX_FMT_MEDIACODEC`：`frame.data(0)` 是一个
     * **不透明句柄**（surface 指针），不是像素。直接按像素读会崩溃或产生花屏，必须先经
     * [avutil.av_hwframe_transfer_data] 把它映射回 NV12/YUV420P 软件帧。
     */
    private var swFrame: AVFrame? = null


    /**
     * 输出用的连续 I420 缓冲：`Y(w*h) + U(uvW*uvH) + V(uvW*uvH)`。
     * 直接分配（direct），供 GL 上传路径零拷贝取用。
     */
    private var outBuffer: ByteBuffer? = null

    /** 最近一次失败原因。调用方以返回值判断成败，这里只作诊断。 */
    @Volatile
    var lastError: String = ""
        private set

    /** 是否已成功打开。 */
    val isOpen: Boolean get() = opened.get()

    /** 输出帧的实际宽高（解码器可能在首帧后报告真实尺寸）。 */
    @Volatile
    var frameWidth: Int = width
        private set

    @Volatile
    var frameHeight: Int = height
        private set

    /**
     * 打开解码器并按 MediaCodec 要求配置。
     *
     * MediaCodec 硬解与软解不同：它**必须**在 `avcodec_open2` 之前知道分辨率，
     * 且通常只通过 `extradata`（H.264 的 avcC / H.265 的 hvcC）拿参数集。
     * 裸 Annex-B 流若不含内联 SPS/PPS，需调用方传入 [extradata]。
     *
     * @param extradata 可选的 AVCC extradata。传 null 表示码流自带参数集。
     */
    fun open(extradata: ByteArray? = null): Boolean {
        if (opened.get()) return true
        if (width <= 0 || height <= 0) {
            lastError = "invalid dimensions ${width}x$height"
            return false
        }
        // 硬解在隔离 JVM 上的可用性完全由桥接器判定，这里再兜一层防止误用。
        if (!FfmpegMediaCodecBridge.isAvailable()) {
            lastError = "MediaCodec bridge unavailable (not Android, or FFmpeg build lacks mediacodec)"
            return false
        }
        return try {
            val codec: AVCodec = avcodec.avcodec_find_decoder_by_name(decoderName)
                ?: run {
                    lastError = "decoder '$decoderName' not registered in this FFmpeg build"
                    return false
                }
            val c: AVCodecContext = avcodec.avcodec_alloc_context3(codec)
                ?: run {
                    lastError = "avcodec_alloc_context3 returned null for '$decoderName'"
                    return false
                }
            c.width(width)
            c.height(height)
            c.pix_fmt(avutil.AV_PIX_FMT_MEDIACODEC)

            if (extradata != null && extradata.isNotEmpty()) {
                val ptr = BytePointer(extradata.size.toLong())
                ptr.put(extradata, 0, extradata.size)
                c.extradata(ptr)
                c.extradata_size(extradata.size)
            }

            // ndk_codec=1：走原生 AMediaCodec C API，而不是 JNI 反射 android.media.MediaCodec。
            // 这是隔离 JVM（无 android.jar）下唯一可行的路径。
            val opts = AVDictionary()
            avutil.av_dict_set(opts, "ndk_codec", "1", 0)
            // 低延迟：直播/短片段优先出帧，牺牲一点吞吐。
            avutil.av_dict_set(opts, "low_delay", "1", 0)

            val rc = avcodec.avcodec_open2(c, codec, opts)
            // 未被识别的键会留在字典里；无论成败统一释放。
            avutil.av_dict_free(opts)
            if (rc < 0) {
                lastError = "avcodec_open2('$decoderName') failed: ${ffErr(rc)}"
                avcodec.avcodec_free_context(c)
                return false
            }

            ctx = c
            packet = avcodec.av_packet_alloc()
            frame = avutil.av_frame_alloc()
            swFrame = avutil.av_frame_alloc()
            if (packet == null || frame == null || swFrame == null) {
                lastError = "failed to allocate AVPacket/AVFrame"
                close()
                return false
            }
            outBuffer = ByteBuffer.allocateDirect(yuv420Size(width, height))
            opened.set(true)
            logger.info(
                "MediaCodec solver opened: decoder=$decoderName {}x{} ndk_codec=1 outBuf={}B",
                width, height, outBuffer?.capacity(),
            )
            true
        } catch (t: Throwable) {
            lastError = "open threw ${t::class.simpleName}: ${t.message}"
            logger.error("open($decoderName) failed: ${t.message}")
            close()
            false
        }
    }

    /**
     * 送一个压缩包并尝试取一帧。
     *
     * @param data 压缩数据（Annex-B 或 AVCC，与 [open] 的 extradata 约定一致）
     * @param size 有效字节数
     * @param ptsMicros 显示时间戳（微秒）；[AV_NOPTS_VALUE] 表示未知
     */
    fun decode(data: ByteBuffer, size: Int, ptsMicros: Long = AV_NOPTS_VALUE): Result {
        val c = ctx
        val p = packet
        val f = frame
        if (!opened.get() || c == null || p == null || f == null || outBuffer == null) {
            return Result.NOT_OPEN
        }
        if (size <= 0) return Result.NEED_MORE_INPUT
        return try {
            // FFmpeg 的比特流读取器（H.264/H.265 的 NAL 解析、CABAC 等）会**越过数据末尾
            // 最多 AV_INPUT_BUFFER_PADDING_SIZE 字节**做无分支快速读取。输入缓冲必须预留这段
            // 且清零的 padding，否则解码器读到堆尾之外 → SIGSEGV 或随机花屏。
            //
            // av_packet_from_data 要求调用方自己提供这块 padding（它只接管所有权，不补零），
            // 所以这里必须用 av_new_packet 让 FFmpeg 分配带 padding 的缓冲再拷入，
            // 不能直接把调用方的 ByteBuffer 交出去。
            avcodec.av_packet_unref(p)
            val nrc = avcodec.av_new_packet(p, size)
            if (nrc < 0) {
                lastError = "av_new_packet($size) failed: ${ffErr(nrc)}"
                return Result.ERROR
            }
            val dstPtr: BytePointer = p.data()
            dstPtr.put(copySource(data, size), 0, size)

            p.pts(ptsMicros)
            p.dts(ptsMicros)

            val src = avcodec.avcodec_send_packet(c, p)
            if (src < 0 && src != avutil.AVERROR_EAGAIN() && src != avutil.AVERROR_EOF()) {
                lastError = "avcodec_send_packet failed: ${ffErr(src)}"
                return Result.ERROR
            }

            val rrc = avcodec.avcodec_receive_frame(c, f)
            when {
                rrc == 0 -> {
                    // 硬解器输出的是 AV_PIX_FMT_MEDIACODEC 帧：data(0) 是 GPU surface 句柄，
                    // 不能当像素读。必须先拷回系统内存再归一化成 I420。
                    if (copyFrameToI420(f)) {
                        // 硬解器输出的 frame 由 FFmpeg 持有，取完必须 unref 才能复用。
                        avutil.av_frame_unref(f)
                        Result.FRAME_READY
                    } else {
                        avutil.av_frame_unref(f)
                        Result.ERROR
                    }
                }
                rrc == avutil.AVERROR_EAGAIN() -> Result.NEED_MORE_INPUT
                rrc == avutil.AVERROR_EOF() -> Result.EOF
                else -> {
                    lastError = "avcodec_receive_frame failed: ${ffErr(rrc)}"
                    Result.ERROR
                }
            }
        } catch (t: Throwable) {
            lastError = "decode threw ${t::class.simpleName}: ${t.message}"
            logger.error("decode failed: ${t.message}")
            Result.ERROR
        }
    }

    /**
     * 取当前 I420 输出缓冲（调用前须已收到 [Result.FRAME_READY]）。
     *
     * 返回的是**内部复用缓冲**，下一次 [decode] 会覆盖它；需要跨帧保留请自行拷贝。
     * buffer 的 position=0、limit=帧实际字节数，可直接交给 GL 上传。
     */
    fun outputI420(): ByteBuffer? {
        val out = outBuffer ?: return null
        out.position(0)
        out.limit(yuv420Size(frameWidth, frameHeight))
        return out
    }

    /**
     * 把解码出的 [AVFrame] 归一化成紧凑 I420 布局。
     *
     * MediaCodec 常见输出是 NV12（Y 平面 + 交错 UV 平面），也可能是真正的 YUV420P。
     * 两种情况都归一化成 I420，让上层渲染管线只需要一套格式处理。
     */
    private fun copyFrameToI420(f: AVFrame): Boolean {
        // 硬解帧（AV_PIX_FMT_MEDIACODEC）必须经 hwframe transfer 拷回系统内存，
        // 拿到真正的 NV12/YUV420P 像素后再走下面的平面拆分。
        val src: AVFrame
        if (f.format() == avutil.AV_PIX_FMT_MEDIACODEC) {
            val sw = swFrame ?: run {
                lastError = "swFrame not allocated"
                return false
            }
            avutil.av_frame_unref(sw)
            val trc = avutil.av_hwframe_transfer_data(sw, f, 0)
            if (trc < 0) {
                lastError = "av_hwframe_transfer_data failed: ${ffErr(trc)}"
                return false
            }
            src = sw
        } else {
            src = f
        }

        val w = src.width()
        val h = src.height()
        if (w <= 0 || h <= 0) {
            lastError = "decoded frame has invalid size ${w}x$h"
            return false
        }
        frameWidth = w
        frameHeight = h

        val needed = yuv420Size(w, h)
        var out = outBuffer
        if (out == null || out.capacity() < needed) {
            out = ByteBuffer.allocateDirect(needed)
            outBuffer = out
        }
        out.clear()
        out.limit(needed)

        val fmt = src.format()
        val ySize = w * h
        val uvW = (w + 1) / 2
        val uvH = (h + 1) / 2
        val uvSize = uvW * uvH

        // Y 平面：逐行拷贝以尊重 linesize（硬解器通常按 16/32 对齐，行尾含 padding）。
        val yPtr: BytePointer = src.data(0)
        if (yPtr.isNull) {
            lastError = "decoded frame has null luma plane"
            return false
        }
        copyPlaneAt(out, 0, yPtr, src.linesize(0), w, h)

        when (fmt) {
            avutil.AV_PIX_FMT_NV12 -> {
                // NV12：UV 交错单平面。按行拆成 U、V 两个独立平面。
                val uvPtr: BytePointer = src.data(1)
                if (uvPtr.isNull) {
                    lastError = "decoded NV12 frame has null chroma plane"
                    return false
                }
                val uvStride = src.linesize(1)
                val uBase = ySize
                val vBase = ySize + uvSize
                val row = ByteArray(uvW * 2)
                for (r in 0 until uvH) {
                    // 复制成新 Pointer 再定位，避免改动共享的 data(1) 指针状态。
                    val srcRow = BytePointer(uvPtr).position(r.toLong() * uvStride)
                    srcRow.get(row, 0, uvW * 2)
                    for (col in 0 until uvW) {
                        out.put(uBase + r * uvW + col, row[col * 2])
                        out.put(vBase + r * uvW + col, row[col * 2 + 1])
                    }
                }
            }
            avutil.AV_PIX_FMT_YUV420P -> {
                val uPtr: BytePointer = src.data(1)
                val vPtr: BytePointer = src.data(2)
                if (uPtr.isNull || vPtr.isNull) {
                    lastError = "decoded YUV420P frame has null chroma plane"
                    return false
                }
                copyPlaneAt(out, ySize, uPtr, src.linesize(1), uvW, uvH)
                copyPlaneAt(out, ySize + uvSize, vPtr, src.linesize(2), uvW, uvH)
            }
            else -> {
                // 意外格式（例如只拿到 opaque surface 而 transfer 不可用）。
                lastError = "unsupported output pixel format id=$fmt (expected nv12/yuv420p)"
                logger.warn(lastError)
                return false
            }
        }
        out.position(0)
        out.limit(needed)
        return true
    }

    /**
     * 从 [src] 按 [stride] 逐行拷 [copyWidth] 字节 × [rows] 行，写到 [dst] 的绝对偏移 [dstOffset]。
     * 用绝对写避免依赖 dst.position 漂移，且能正确处理大于宽度的 stride（行尾 padding）。
     */
    private fun copyPlaneAt(
        dst: ByteBuffer,
        dstOffset: Int,
        src: BytePointer,
        stride: Int,
        copyWidth: Int,
        rows: Int,
    ) {
        val effectiveStride = if (stride > 0) stride else copyWidth
        val row = ByteArray(copyWidth)
        for (r in 0 until rows) {
            val rowPtr = BytePointer(src).position(r.toLong() * effectiveStride)
            rowPtr.get(row, 0, copyWidth)
            dst.position(dstOffset + r * copyWidth)
            dst.put(row, 0, copyWidth)
        }
    }

    /** 把调用方传入的 [data] 的前 [size] 字节取出为可交给 BytePointer.put 的数组。 */
    private fun copySource(data: ByteBuffer, size: Int): ByteArray {
        val out = ByteArray(size)
        val view = data.duplicate()
        view.position(0)
        view.limit(size.coerceAtMost(view.capacity()))
        view.get(out, 0, view.remaining())
        return out
    }

    /** 释放所有 native 资源。可重复调用。 */
    override fun close() {
        if (!opened.getAndSet(false) && ctx == null) return
        runCatching {
            swFrame?.let { avutil.av_frame_free(it) }
            frame?.let { avutil.av_frame_free(it) }
            packet?.let { avcodec.av_packet_free(it) }
            ctx?.let { avcodec.avcodec_free_context(it) }
        }.onFailure { logger.warn("close() partially failed: ${it.message}") }
        swFrame = null
        frame = null
        packet = null
        ctx = null
        outBuffer = null
    }

    /** 单次 [decode] 的结果。 */
    enum class Result {
        /** 一帧已解码完成，可从 [outputI420] 取数据。 */
        FRAME_READY,

        /** 需要继续送包；本次没有产出帧（硬解流水线的常规状态）。 */
        NEED_MORE_INPUT,

        /** 解码器报告流结束。 */
        EOF,

        /** 发生错误，原因见 [lastError]。 */
        ERROR,

        /** 解码器尚未 [open] 或已关闭。 */
        NOT_OPEN,
    }

    companion object {
        /** H.264 硬解解码器名（已在本项目使用的 FFmpeg 构建中核实存在）。 */
        const val DEFAULT_DECODER = "h264_mediacodec"

        /** H.265 硬解解码器名。 */
        const val HEVC_DECODER = "hevc_mediacodec"

        /** FFmpeg 的 AV_NOPTS_VALUE。 */
        const val AV_NOPTS_VALUE: Long = Long.MIN_VALUE

        /** I420 帧字节数。 */
        fun yuv420Size(w: Int, h: Int): Int = w * h + 2 * (((w + 1) / 2) * ((h + 1) / 2))

        /** 把 FFmpeg 的负错误码渲染成可读字符串。 */
        private fun ffErr(rc: Int): String {
            val name = when (rc) {
                avutil.AVERROR_EAGAIN() -> "EAGAIN"
                avutil.AVERROR_EOF() -> "EOF"
                avutil.AVERROR_EINVAL() -> "EINVAL"
                avutil.AVERROR_ENOMEM() -> "ENOMEM"
                else -> null
            }
            return if (name != null) "$name($rc)" else "rc=$rc"
        }
    }
}