/**
 * FFmpeg 视频解码器会话：硬件优先，可降级到同一编码的软件解码器。
 *
 * ## 为什么这样写
 *
 * 本类做一件事：把码流喂给 FFmpeg 解码器，取回帧，再用 `sws_scale` 转成**打包 BGRA**
 * 交给现有渲染管线。解码器由 [FfmpegHwBackendPolicy] 选：通常是
 * `h264_mediacodec` / `hevc_mediacodec`，失败后经 [openWithFallback] 降到软解。
 *
 * 关键设计约束：
 *
 * 1. **不引用任何 `android.*` 类**。JavaCPP 的 `org.bytedeco.ffmpeg.*` 是纯 JNI 绑定，NDK
 *    后端（libmediandk.so → `AMediaCodec_*`）在 native 层完成与系统 MediaCodec 服务的交互。
 *    因此本类在 Pojav / Zalith 等「桌面 OpenJDK、没有 android.jar」的游戏 JVM 上也能加载。
 * 2. **失败必须可回退**。任何一步失败都返回 false / 错误枚举并写入 [lastError]，由上层
 *    （[FfmpegCodecScheduler]）决定是否退回 libvlc；绝不抛异常穿透到播放线程。
 * 3. **像素格式跟随 [hardware]**，否则软解降级档永远打不开（见 [open] 的说明）。
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
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单个 MediaCodec 硬解会话。
 *
 * 生命周期：`open()` → 反复 `decode()` → `close()`。非线程安全，调用方需保证串行
 * （播放器里由固定的解码线程持有）。
 *
 * 解码器名由 [FfmpegHwBackendPolicy] 决定而不是写死：硬解失败时同一个实例可以
 * 用 [openWithFallback] 换到链上的下一档（最终是同一编码的软件解码器），
 * 不必重建播放器。
 *
 * @param width  码流原始宽度（必须与 SPS 一致，否则 MediaCodec 配置会失败）
 * @param height 码流原始高度
 * @param decoderName FFmpeg 解码器名；由策略给出（`h264_mediacodec` / `h264_cuvid` / `h264` / …）
 * @param kind 这一档的解码器类型。**必须与 [decoderName] 一致**，因为它决定 open 前
 *   要配什么，而配错就直接打不开：
 *   - [FfmpegHwBackendKind.MEDIACODEC]：`AV_PIX_FMT_MEDIACODEC` + `ndk_codec=1`；
 *   - [FfmpegHwBackendKind.DESKTOP]：**不设** `pix_fmt`（cuvid/qsv/d3d11va/videotoolbox
 *     各自在 `avcodec_open2` 内部协商 hwdevice）；设成 mediacodec 必然失败；
 *   - [FfmpegHwBackendKind.SOFTWARE]：不设 `pix_fmt`，让解码器自选。
 *
 *   注意**不要**再用「`hardware == true` 就套 mediacodec 参数」这种写法：桌面硬解
 *   同样是 hardware，但它是完全不同的另一套约定。
 */
internal class AndroidMediaCodecHwSolver(
    private val width: Int,
    private val height: Int,
    decoderName: String = FfmpegCodec.H264.mediacodecDecoder ?: FfmpegCodec.H264.softwareDecoder,
    kind: FfmpegHwBackendKind = FfmpegHwBackendKind.fromDecoderName(decoderName),
) : AutoCloseable {

    /** 当前使用的解码器名；[openWithFallback] 降级时会替换。 */
    var decoderName: String = decoderName
        private set

    /** 当前这一档的解码器类型；与 [decoderName] 同步变化。 */
    var kind: FfmpegHwBackendKind = kind
        private set

    /** 当前这一档是否走硬件。派生自 [kind]，不单独存储以免两者不一致。 */
    val hardware: Boolean get() = kind != FfmpegHwBackendKind.SOFTWARE

    /** 这一档是否是 Android 的 mediacodec 包装器（唯一需要 mediacodec 像素格式的类型）。 */
    private val isMediacodec: Boolean get() = kind == FfmpegHwBackendKind.MEDIACODEC

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
     * 输出用的连续 BGRA 缓冲（`w*h*4`）。
     * 直接分配（direct），供 GL 上传路径零拷贝取用。
     */
    private var outBuffer: ByteBuffer? = null

    /** 最近一次失败原因。调用方以返回值判断成败，这里只作诊断。 */
    @Volatile
    var lastError: String = ""
        private set

    /**
     * 最近一帧**自己**的 PTS（微秒），来自 `AVFrame.pts`。
     *
     * 不能用「刚送入那个包的 PTS」代替：有 B 帧时解码器会重排，输出帧与输入包并不同序，
     * 拿输入包的时间戳当显示时刻会让画面抖动。调用方在 [Result.FRAME_READY] 后读这个值。
     *
     * 值为 [AV_NOPTS_VALUE] 表示这一帧没有时间戳（部分裸流如此），调用方应按「立即显示」处理。
     */
    @Volatile
    var lastFramePtsMicros: Long = AV_NOPTS_VALUE
        private set

    /**
     * I420 → BGRA 的转换上下文（`SwsContext`）。
     *
     * 解码器产出的是平面 I420，但渲染管线只接受打包 BGRA：GPU-YUV 平面路径
     * （`DisplayYuvRenderTypes.active`）在 Sodium 环境下会黑屏，作者已将其永久关闭，
     * 因此这里必须自己完成色彩空间转换，接入既有的 `BGRA32` 上传路径。
     * 分辨率变化时重建。
     */
    private var sws: SwsContext? = null
    private var swsW = 0
    private var swsH = 0

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
     * 打开解码器。
     *
     * MediaCodec 硬解与软解不同：它**必须**在 `avcodec_open2` 之前知道分辨率，
     * 且通常只通过 `extradata`（H.264 的 avcC / H.265 的 hvcC）拿参数集。
     * 裸 Annex-B 流若不含内联 SPS/PPS，需调用方传入 [extradata]。
     *
     * 像素格式按 [hardware] 分派，这是「降级到软解」能真正生效的前提：
     *  - 硬件：`AV_PIX_FMT_MEDIACODEC`，`ndk_codec=1` 走原生 `AMediaCodec`；
     *  - 软件：**不设** `pix_fmt`，让解码器自己选（通常是 YUV420P），
     *    也不传 `ndk_codec` —— 软件解码器不认识这个选项。
     *
     * @param extradata 可选的 AVCC extradata。传 null 表示码流自带参数集。
     */
    fun open(extradata: ByteArray? = null): Boolean {
        if (opened.get()) return true
        if (width <= 0 || height <= 0) {
            lastError = "invalid dimensions ${width}x$height"
            return false
        }
        // mediacodec 包装器在隔离 JVM 上的可用性完全由桥接器判定，这里再兜一层防止误用。
        // 只对 MEDIACODEC 生效：桌面硬解（cuvid/qsv/d3d11va/videotoolbox）与桥接器无关，
        // 拿它当门槛会让桌面上每一档硬解在 avcodec_open2 之前就被拒掉。
        // 软解档同样不受限制——桥接器不可用恰恰是降级的常见原因。
        if (isMediacodec && !FfmpegMediaCodecBridge.isAvailable()) {
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
            // 只有 mediacodec 包装器要预设像素格式。桌面硬解（cuvid/qsv/d3d11va/
            // videotoolbox/mf/v4l2m2m）在 avcodec_open2 内部创建并协商自己的
            // hwdevice/hwframes，从外面设成 MEDIACODEC 会让它当场失败。
            if (isMediacodec) {
                c.pix_fmt(avutil.AV_PIX_FMT_MEDIACODEC)
            }

            if (extradata != null && extradata.isNotEmpty()) {
                val ptr = BytePointer(extradata.size.toLong())
                ptr.put(extradata, 0, extradata.size)
                c.extradata(ptr)
                c.extradata_size(extradata.size)
            }

            val opts = AVDictionary()
            if (isMediacodec) {
                // ndk_codec=1：走原生 AMediaCodec C API，而不是 JNI 反射 android.media.MediaCodec。
                // 这是隔离 JVM（无 android.jar）下唯一可行的路径。
                // 桌面硬解不认这个选项，传了只会被忽略或触发初始化失败，故限定在 mediacodec 档。
                avutil.av_dict_set(opts, "ndk_codec", "1", 0)
            }
            // 低延迟：直播/短片段优先出帧，牺牲一点吞吐。软硬解都适用。
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
            outBuffer = ByteBuffer.allocateDirect(width * height * 4)
            opened.set(true)
            logger.info(
                "FFmpeg decoder opened: decoder={} {}x{} hardware={} outBuf={}B",
                decoderName, width, height, hardware, outBuffer?.capacity(),
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
     * 降级到链上的下一个可用后端（硬件 → 同一编码的软件解码器）。
     *
     * 这是「选择器」能真正救场的那一步：`avcodec_open2` 对 mediacodec 失败时，
     * 调用方拿去问 [FfmpegCodecScheduler.reportFailure] 拿到下一档，再回到这里重开。
     *
     * @param codec 本次解码的编码，用来查软件解码器名
     * @param step 当前所在链上的档位。**-1 表示被 `-Ddreamdisplayx.hwDecode` 强制指定**，
     *   这种选择不允许降级——用户明确要求了某个后端，失败就该报错，而不是偷偷换掉。
     * @return true 表示已成功用新后端打开；false 表示无可降级或新后端也打不开
     *   （此时 [lastError] 记录原因，调用方应当整条管线回退 libvlc）
     */
    fun openWithFallback(codec: FfmpegCodec, step: Int): Boolean {
        if (step == -1) {
            lastError = "backend was forced by -Ddreamdisplayx.hwDecode; refusing to downgrade"
            return false
        }
        if (!hardware) {
            // 已经在软件那一档了，没有更后面的档。
            lastError = "already on software decode; no further fallback"
            return false
        }
        val target = codec.softwareDecoder
        if (target.isBlank()) {
            lastError = "codec $codec has no software decoder to fall back to"
            return false
        }
        // 先彻底释放硬件解码器再换。硬解上下文持有 GPU surface，
        // 复用一个半开的状态去 open2 第二次会拿到上次的残留配置。
        close()
        logger.warn("Falling back from hardware to software decoder '$target' for {}", codec)
        decoderName = target
        kind = FfmpegHwBackendKind.SOFTWARE
        return open()
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
                    // 抓这一帧自己的 PTS 必须在 av_frame_unref 之前：解码器可能因 B 帧重排
                    // 而晚于送入的包输出，用「刚才送进去那个包」的 PTS 会算错显示时刻。
                    lastFramePtsMicros = f.pts()
                    // 硬解帧是 AV_PIX_FMT_MEDIACODEC：data(0) 是 GPU surface 句柄，不是像素，
                    // 必须先拷回系统内存再转色彩空间。
                    if (convertFrameToBgra(f)) {
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
     * 取当前 BGRA 输出缓冲（调用前须已收到 [Result.FRAME_READY]）。
     *
     * 返回的是**内部复用缓冲**，下一次 [decode] 会覆盖它；需要跨帧保留请自行拷贝。
     * buffer 的 position=0、limit=帧实际字节数，可直接交给 GL 上传。
     */
    fun outputBgra(): ByteBuffer? {
        val out = outBuffer ?: return null
        out.position(0)
        out.limit(frameWidth * frameHeight * 4)
        return out
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

    /**
     * 把解码出的 [AVFrame] 转成打包 BGRA 输出。
     *
     * 先按上一节把硬解帧（AV_PIX_FMT_MEDIACODEC）拷回系统内存得到 NV12/YUV420P，
     * 再经 `sws_scale` 一步完成色彩空间转换（YUV→RGB）与平面打包，
     * 直接对齐渲染管线的 `FramePixelFormat.BGRA32` 上传路径。
     *
     * 之所以不走 GPU-YUV 三平面路径：`DisplayYuvRenderTypes.active` 在 Sodium 环境下
     * 会渲染黑屏，已被作者永久关闭，打包 BGRA 是当前唯一可靠的通路。
     */
    private fun convertFrameToBgra(f: AVFrame): Boolean {
        // 硬解帧必须经 hwframe transfer 拷回系统内存，拿到真正的像素后再转色彩空间。
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

        val needed = w * h * 4
        var out = outBuffer
        if (out == null || out.capacity() < needed) {
            out = ByteBuffer.allocateDirect(needed)
            outBuffer = out
        }
        out.clear()
        out.limit(needed)

        // 分辨率变化时重建 sws 上下文（硬解器可能在首帧后报告真实尺寸）。
        var scaler = sws
        if (scaler == null || swsW != w || swsH != h) {
            scaler?.let { swscale.sws_freeContext(it) }
            scaler = swscale.sws_getContext(
                w, h, src.format(),
                w, h, avutil.AV_PIX_FMT_BGRA,
                swscale.SWS_BILINEAR, null, null, null as DoubleArray?,
            )
            if (scaler == null) {
                lastError = "sws_getContext failed for ${w}x$h fmt=${src.format()}"
                return false
            }
            sws = scaler
            swsW = w
            swsH = h
        }

        // sws_scale 需要源/目的平面指针数组与行距数组。AVFrame 的 data/linesize 自带源侧信息，
        // 目的侧是单个紧密打包的 BGRA 平面。
        val srcData = PointerPointer<BytePointer>(4)
        val srcStride = IntPointer(4)
        for (i in 0 until 4) {
            srcData.put(i.toLong(), src.data(i))
            srcStride.put(i.toLong(), src.linesize(i))
        }
        val dstData = PointerPointer<BytePointer>(4)
        val dstStride = IntPointer(4)
        val dstPtr = BytePointer(out)
        dstData.put(0L, dstPtr)
        dstStride.put(0L, w * 4)

        val scaled = swscale.sws_scale(scaler, srcData, srcStride, 0, h, dstData, dstStride)
        if (scaled <= 0) {
            lastError = "sws_scale returned $scaled"
            return false
        }
        out.position(0)
        out.limit(needed)
        return true
    }

    /**
     * 清空解码器内部缓冲。
     *
     * seek 之后必须调用：解码器里还压着旧位置的包和已重排待输出的帧，不清就会先吐出
     * 几帧上一个位置的画面（表现为「跳到新位置后闪一下旧画面」）。
     *
     * @return true 表示已清空；未打开时返回 false
     */
    fun flush(): Boolean {
        val c = ctx ?: return false
        if (!opened.get()) return false
        return try {
            avcodec.avcodec_flush_buffers(c)
            // 上一位点的帧 PTS 已经无意义，重置掉，免得调用方误读。
            lastFramePtsMicros = AV_NOPTS_VALUE
            true
        } catch (t: Throwable) {
            lastError = "flush threw ${t::class.simpleName}: ${t.message}"
            logger.warn("flush failed: ${t.message}")
            false
        }
    }

    /**
     * 释放所有 native 资源。可重复调用。
     */
    override fun close() {
        if (!opened.getAndSet(false) && ctx == null) return
        runCatching {
            sws?.let { swscale.sws_freeContext(it) }
            swFrame?.let { avutil.av_frame_free(it) }
            frame?.let { avutil.av_frame_free(it) }
            packet?.let { avcodec.av_packet_free(it) }
            ctx?.let { avcodec.avcodec_free_context(it) }
        }.onFailure { logger.warn("close() partially failed: ${it.message}") }
        sws = null
        swsW = 0
        swsH = 0
        swFrame = null
        frame = null
        packet = null
        ctx = null
        outBuffer = null
    }

    /** 单次 [decode] 的结果。 */
    enum class Result {
        /** 一帧已解码完成，可从 [outputBgra] 取数据。 */
        FRAME_READY,

        /** 需要继续送包；本次没有产出帧（解码流水线的常规状态）。 */
        NEED_MORE_INPUT,

        /** 解码器报告流结束。 */
        EOF,

        /** 发生错误，原因见 [lastError]。 */
        ERROR,

        /** 解码器尚未 [open] 或已关闭。 */
        NOT_OPEN,
    }

    companion object {
        /** FFmpeg 的 AV_NOPTS_VALUE。 */
        const val AV_NOPTS_VALUE: Long = Long.MIN_VALUE

        /**
         * 打包 BGRA 帧字节数。
         *
         * 渲染管线走 `FramePixelFormat.BGRA32`，一行 `w` 个像素、每像素 4 字节，
         * 且已由 `sws_scale` 打包成紧密排列，所以直接是 `w*h*4`。
         */
        fun bgraSize(w: Int, h: Int): Int = w * h * 4

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