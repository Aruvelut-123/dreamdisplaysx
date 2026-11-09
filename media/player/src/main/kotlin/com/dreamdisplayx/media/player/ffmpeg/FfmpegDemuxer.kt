/**
 * FFmpeg 解封装器（demuxer）：把 URL 里的压缩码流读出来交给 [AndroidMediaCodecHwSolver]。
 *
 * ## 为什么需要这个类
 *
 * libvlc 会自己完成「解封装 + 解码 + 渲染」，所以现有播放路径不需要解封装器。
 * 但 MediaCodec 硬解走的是另一条路：FFmpeg 的 `h264_mediacodec` / `hevc_mediacodec`
 * 只负责**解码**，它需要调用方把 annex-B/AVCC 码流包喂进来。
 * 本类就是补上「URL → 压缩包」这一段：`avformat_open_input` 打开输入，
 * `av_find_best_stream` 选视频流，`av_read_frame` 循环读包，只把视频流的包交给解码器。
 *
 * ## 设计约束
 *
 * 1. **不引用任何 `android.*` 类**。全程走 JavaCPP 的 FFmpeg JNI 绑定，
 *    在 Pojav / Zalith 这类「桌面 OpenJDK、无 android.jar」的游戏 JVM 上同样可用。
 * 2. **桌面端永不进入**。入口由 [FfmpegMediaCodecBridge.isAvailable] 把关。
 * 3. **失败可回退、不抛异常穿透**。所有 FFmpeg 调用都检查返回码，失败写入 [lastError]，
 *    由上层决定是否退回 libvlc 路径。
 * 4. **网络选项**：Bilibili 等 CDN 需要 Referer/User-Agent，通过 `av_dict_set` 在
 *    `avformat_open_input` 时传入（FFmpeg 的 `headers` / `user_agent` 选项）。
 *
 * 生命周期：`open()` → 反复 `readVideoPacket()` → `close()`。非线程安全，
 * 调用方（解码线程）需保证串行。
 */
package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.util.log.DreamLog
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单个输入源的解封装会话。
 *
 * @param url 视频源 URL（可为直链或 HLS/DASH 清单）
 * @param referer 可选 Referer，CDN 防盗链需要
 * @param userAgent 可选 User-Agent
 */
internal class FfmpegDemuxer(
    private val url: String,
    private val referer: String? = null,
    private val userAgent: String? = null,
) : AutoCloseable {

    private val logger = DreamLog.of("FfmpegDemuxer")

    private val opened = AtomicBoolean(false)

    /** FFmpeg 输入上下文；`close()` 时用 `avformat_close_input` 释放。 */
    private var fmtCtx: AVFormatContext? = null

    /** 视频流下标（`av_find_best_stream` 的结果）。 */
    private var videoStreamIndex: Int = -1

    /** 音频流下标；-1 表示该源没有音频（或调用方没要音频）。 */
    @Volatile
    var audioStreamIndex: Int = -1
        private set

    /** 音频编码 ID；仅在 [audioStreamIndex] >= 0 时有效。 */
    @Volatile
    var audioCodecId: Int = avcodec.AV_CODEC_ID_NONE
        private set

    /** 音频流的 codecpar，供 [FfmpegAudioDecoder] 打开解码器。 */
    @Volatile
    var audioCodecParameters: AVCodecParameters? = null
        private set

    /** 音频流时间基分子/分母，用于把音频 PTS 换算成微秒。 */
    private var audioTbNum: Int = 1
    private var audioTbDen: Int = 1

    /** 复用的读包缓冲。 */
    private var packet: AVPacket? = null

    /** 最近一次失败原因，供诊断。 */
    @Volatile
    var lastError: String = ""
        private set

    /** 视频编码 ID（`AV_CODEC_ID_H264` 等）；用于决定用哪个 mediacodec 解码器。 */
    @Volatile
    var videoCodecId: Int = avcodec.AV_CODEC_ID_NONE
        private set

    @Volatile
    var videoWidth: Int = 0
        private set

    @Volatile
    var videoHeight: Int = 0
        private set

    /** 时长（微秒）；<= 0 表示未知/直播。 */
    @Volatile
    var durationMicros: Long = 0L
        private set

    /** 视频流时间基分子/分母，用于把 PTS 换算成微秒。 */
    private var tbNum: Int = 1
    private var tbDen: Int = 1

    val isOpen: Boolean get() = opened.get()

    /**
     * 打开输入并定位视频流。
     *
     * @return true 表示已就绪，可以调用 [readVideoPacket]
     */
    fun open(): Boolean {
        if (opened.get()) return true
        if (url.isBlank()) {
            lastError = "blank url"
            return false
        }
        if (!FfmpegMediaCodecBridge.isAvailable()) {
            lastError = "MediaCodec bridge unavailable (not Android, or FFmpeg build lacks mediacodec)"
            return false
        }
        return try {
            val ctx = avformat.avformat_alloc_context()
                ?: run {
                    lastError = "avformat_alloc_context returned null"
                    return false
                }

            // CDN 防盗链：Bilibili 的 DASH 分片没有 Referer 会返回 403。
            // FFmpeg 的 http 协议读取这两个字典项。
            val opts = AVDictionary()
            referer?.takeIf { it.isNotBlank() }?.let {
                avutil.av_dict_set(opts, "headers", "Referer: $it\r\n", 0)
            }
            userAgent?.takeIf { it.isNotBlank() }?.let {
                avutil.av_dict_set(opts, "user_agent", it, 0)
            }
            // 降低起播延迟：只探测有限字节数做流信息分析。
            avutil.av_dict_set(opts, "analyzeduration", "3000000", 0)
            avutil.av_dict_set(opts, "probesize", "1000000", 0)

            val orc = avformat.avformat_open_input(ctx, url, null, opts)
            avutil.av_dict_free(opts)
            if (orc < 0) {
                lastError = "avformat_open_input failed: ${ffErr(orc)}"
                avformat.avformat_close_input(ctx)
                return false
            }

            val irc = avformat.avformat_find_stream_info(ctx, null as AVDictionary?)
            if (irc < 0) {
                lastError = "avformat_find_stream_info failed: ${ffErr(irc)}"
                avformat.avformat_close_input(ctx)
                return false
            }

            val vsIdx = avformat.av_find_best_stream(
                ctx, avutil.AVMEDIA_TYPE_VIDEO, -1, -1, null as org.bytedeco.ffmpeg.avcodec.AVCodec?, 0,
            )
            if (vsIdx < 0) {
                lastError = "no video stream (av_find_best_stream=${ffErr(vsIdx)})"
                avformat.avformat_close_input(ctx)
                return false
            }

            val stream: AVStream = ctx.streams(vsIdx)
                ?: run {
                    lastError = "null AVStream at index $vsIdx"
                    avformat.avformat_close_input(ctx)
                    return false
                }
            val par: AVCodecParameters = stream.codecpar()
                ?: run {
                    lastError = "null codecpar for video stream"
                    avformat.avformat_close_input(ctx)
                    return false
                }

            videoStreamIndex = vsIdx
            videoCodecId = par.codec_id()
            videoWidth = par.width()
            videoHeight = par.height()
            // 时间基：PTS × num / den = 秒；× 1e6 得微秒。
            tbNum = stream.time_base().num()
            tbDen = stream.time_base().den()
            durationMicros = if (ctx.duration() != avutil.AV_NOPTS_VALUE) ctx.duration() else 0L

            // 音频流：找不到不是错误。Bilibili DASH 会把视频和音频拆成两条流，
            // 纯视频源也合法；调用方按 audioStreamIndex < 0 跳过音频即可。
            locateAudioStream(ctx)

            packet = avcodec.av_packet_alloc()
                ?: run {
                    lastError = "av_packet_alloc returned null"
                    avformat.avformat_close_input(ctx)
                    return false
                }
            fmtCtx = ctx
            opened.set(true)
            logger.info(
                "demuxer opened: codec={} {}x{} tb={}/{} duration={}us streams={}",
                codecName(videoCodecId), videoWidth, videoHeight, tbNum, tbDen, durationMicros, ctx.nb_streams(),
            )
            true
        } catch (t: Throwable) {
            lastError = "open threw ${t::class.simpleName}: ${t.message}"
            logger.error("open($url) failed: ${t.message}")
            close()
            false
        }
    }

    /**
     * 读下一个视频包。
     *
     * 非视频流（音频/字幕）的包会被内部丢弃并 `unref`，调用方只会看到视频包。
     * 注意：返回 [Result.PACKET_READY] 时，包数据位于 [packetBytes] / [packetSize]，
     * 调用方需在下次调用前取走（内部缓冲会在下次读取时被 `unref` 覆盖）。
     */
    fun readVideoPacket(): Result {
        val ctx = fmtCtx
        val p = packet
        if (!opened.get() || ctx == null || p == null) return Result.NOT_OPEN
        return try {
            // 循环读到视频包为止；跳过的音频/字幕包必须 unref，否则泄漏 packet 缓冲。
            while (true) {
                avcodec.av_packet_unref(p)
                val rrc = avformat.av_read_frame(ctx, p)
                if (rrc < 0) {
                    return when {
                        // AVERROR_EOF 是正常流结束（宏，不是函数）。
                        rrc == AVERROR_EOF_VALUE -> Result.EOF
                        else -> {
                            lastError = "av_read_frame failed: ${ffErr(rrc)}"
                            Result.ERROR
                        }
                    }
                }
                if (p.stream_index() != videoStreamIndex) {
                    avcodec.av_packet_unref(p)
                    continue
                }
                return Result.PACKET_READY
            }
            @Suppress("UNREACHABLE_CODE")
            Result.ERROR
        } catch (t: Throwable) {
            lastError = "readVideoPacket threw ${t::class.simpleName}: ${t.message}"
            logger.error("readVideoPacket failed: ${t.message}")
            Result.ERROR
        }
    }

    /**
     * 读下一个包，视频和音频都会交给调用方。
     *
     * 这是音频接入后的读取入口：[Result.PACKET_READY] 表示当前包属于视频流，
     * [Result.AUDIO_PACKET_READY] 表示属于音频流，两者都用同一个 [packet] 缓冲，
     * 因此**必须在下一次读取前处理完**。字幕/数据流仍然被内部丢弃。
     *
     * 只调用 [readVideoPacket] 的旧路径行为不变（音频包依旧被跳过）。
     */
    fun readPacket(): Result {
        val ctx = fmtCtx
        val p = packet
        if (!opened.get() || ctx == null || p == null) return Result.NOT_OPEN
        return try {
            // 循环而不是递归：连续遇到字幕/数据包时递归会堆栈增长，而直播源里
            // 这类包可能很密集。
            while (true) {
                avcodec.av_packet_unref(p)
                val rrc = avformat.av_read_frame(ctx, p)
                if (rrc < 0) {
                    return when {
                        rrc == AVERROR_EOF_VALUE -> Result.EOF
                        else -> {
                            lastError = "av_read_frame failed: ${ffErr(rrc)}"
                            Result.ERROR
                        }
                    }
                }
                val idx = p.stream_index()
                val aIdx = audioStreamIndex
                when (PacketRoutePolicy.classify(idx, videoStreamIndex, aIdx)) {
                    PacketRoutePolicy.Route.VIDEO -> return Result.PACKET_READY
                    PacketRoutePolicy.Route.AUDIO -> return Result.AUDIO_PACKET_READY
                    PacketRoutePolicy.Route.SKIP -> continue
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Result.ERROR
        } catch (t: Throwable) {
            lastError = "readPacket threw ${t::class.simpleName}: ${t.message}"
            logger.error("readPacket failed: ${t.message}")
            Result.ERROR
        }
    }

    /** 当前包的压缩数据视图（position=0, limit=size）。仅在 [Result.PACKET_READY] 后有效。 */
    fun packetView(): ByteBuffer? {
        val p = packet ?: return null
        val size = p.size()
        if (size <= 0) return null
        val ptr: BytePointer = p.data()
        if (ptr.isNull) return null
        // 从 native 内存读进一个临时数组，再交给解码器；ByteBuffer 必须是 direct 的
        // (allocateDirect)，因为 uploader / BytePointer.put 路径都按 direct 缓冲处理。
        val tmp = ByteArray(size)
        ptr.get(tmp, 0, size)
        val out = ByteBuffer.allocateDirect(size)
        out.put(tmp, 0, size)
        out.flip()
        return out
    }

    /** 当前包的大小（字节）。 */
    fun packetSize(): Int = packet?.size() ?: 0

    /** 当前包的 PTS 换算成微秒；未知返回 [AV_NOPTS_VALUE]。 */
    fun packetPtsMicros(): Long {
        val p = packet ?: return AV_NOPTS_VALUE
        val pts = p.pts()
        if (pts == AV_NOPTS_VALUE_LONG) return AV_NOPTS_VALUE
        if (tbDen <= 0) return AV_NOPTS_VALUE
        return pts * tbNum * 1_000_000L / tbDen
    }

    /**
     * 把读取位置移动到 [timeMicros]。
     *
     * 用 `av_seek_frame` 定位到目标时刻之前的最近关键帧（[avformat.AVSEEK_FLAG_BACKWARD]），
     * 因此调用方应预期实际起点略早于请求位置，需要靠 PTS 判断「到哪儿才该开始显示」。
     *
     * 必须在 [open] 之后、解码开始之前调用。**调用方负责 flush 解码器**：seek 之后
     * 解码器里残留的是旧位置的帧，不 flush 会先吐出几帧上一个位置的画面。
     *
     * @param timeMicros 目标时刻（微秒），按内容时间轴
     * @return true 表示已定位
     */
    fun seekTo(timeMicros: Long): Boolean {
        val ctx = fmtCtx
        if (!opened.get() || ctx == null) {
            lastError = "seek before open"
            return false
        }
        if (timeMicros < 0L) return true // 0 之前无意义，当作从头开始
        if (tbNum <= 0 || tbDen <= 0) {
            lastError = "seek: invalid time base $tbNum/$tbDen"
            return false
        }
        if (videoStreamIndex < 0) {
            lastError = "seek: no video stream"
            return false
        }
        return try {
            // av_seek_frame 的时间戳单位是「该流的时间基」，不是微秒。
            val target = timeMicros * tbDen / (tbNum * 1_000_000L)
            val rc = avformat.av_seek_frame(
                ctx, videoStreamIndex, target, avformat.AVSEEK_FLAG_BACKWARD,
            )
            if (rc < 0) {
                lastError = "av_seek_frame failed: ${ffErr(rc)}"
                logger.warn("seekTo($timeMicros) failed: $lastError")
                return false
            }
            // 丢掉 seek 之后流内部的遗留状态（未读完的包等）。
            packet?.let { avcodec.av_packet_unref(it) }
            true
        } catch (t: Throwable) {
            lastError = "seekTo threw ${t::class.simpleName}: ${t.message}"
            logger.error("seekTo($timeMicros) failed: ${t.message}")
            false
        }
    }

    /** 视频流的 AVCC extradata（H.264 的 SPS/PPS、H.265 的 VPS/SPS/PPS），MediaCodec 需要。 */
    fun videoExtradata(): ByteArray? {
        val ctx = fmtCtx ?: return null
        val idx = videoStreamIndex
        if (idx < 0) return null
        val stream = ctx.streams(idx) ?: return null
        val par = stream.codecpar() ?: return null
        val size = par.extradata_size()
        if (size <= 0) return null
        val ptr: BytePointer = par.extradata() ?: return null
        if (ptr.isNull) return null
        val out = ByteArray(size)
        ptr.get(out, 0, size)
        return out
    }

    /** 释放全部 native 资源。可重复调用。 */
    override fun close() {
        if (!opened.getAndSet(false) && fmtCtx == null) return
        runCatching {
            packet?.let { avcodec.av_packet_free(it) }
            fmtCtx?.let { avformat.avformat_close_input(it) }
        }.onFailure { logger.warn("close() partially failed: ${it.message}") }
        packet = null
        fmtCtx = null
        videoStreamIndex = -1
        audioStreamIndex = -1
        audioCodecId = avcodec.AV_CODEC_ID_NONE
        audioCodecParameters = null
    }

    /**
     * 定位音频流并记住其 codecpar / 时间基。
     *
     * 找不到不算失败：DASH 音视频分离的源由调用方分别打开，纯视频源也合法。
     * 每次 `open()` 都必须先清空上一次的结果，否则换源后会拿旧 codecpar 去开解码器。
     */
    private fun locateAudioStream(ctx: AVFormatContext) {
        audioStreamIndex = -1
        audioCodecId = avcodec.AV_CODEC_ID_NONE
        audioCodecParameters = null
        audioTbNum = 1
        audioTbDen = 1
        val idx = avformat.av_find_best_stream(
            ctx, avutil.AVMEDIA_TYPE_AUDIO, -1, -1, null as org.bytedeco.ffmpeg.avcodec.AVCodec?, 0,
        )
        if (idx < 0) return
        val stream: AVStream = ctx.streams(idx) ?: return
        val par: AVCodecParameters = stream.codecpar() ?: return
        audioStreamIndex = idx
        audioCodecId = par.codec_id()
        audioCodecParameters = par
        audioTbNum = stream.time_base().num()
        audioTbDen = stream.time_base().den()
        logger.info(
            "demuxer audio stream: index={} codec={} {}Hz/{}ch tb={}/{}",
            idx, codecName(audioCodecId), par.sample_rate(), par.ch_layout().nb_channels(),
            audioTbNum, audioTbDen,
        )
    }

    /** 音频流的 PTS 换算成微秒；未知返回 [AV_NOPTS_VALUE]。 */
    fun packetAudioPtsMicros(): Long {
        val p = packet ?: return AV_NOPTS_VALUE
        val pts = p.pts()
        if (pts == AV_NOPTS_VALUE_LONG) return AV_NOPTS_VALUE
        if (audioTbDen <= 0) return AV_NOPTS_VALUE
        return pts * audioTbNum * 1_000_000L / audioTbDen
    }

    /** 单次 [readVideoPacket] / [readPacket] 的结果。 */
    enum class Result {
        /** 一个视频包已就绪（用 [packetView] / [packetSize] / [packetPtsMicros] 取值）。 */
        PACKET_READY,

        /** 一个音频包已就绪（音频消费方用 [packetAudioPtsMicros] 取时间）。 */
        AUDIO_PACKET_READY,

        /** 流结束。 */
        EOF,

        /** 读取失败，原因见 [lastError]。 */
        ERROR,

        /** 尚未 [open] 或已关闭。 */
        NOT_OPEN,
    }

    companion object {
        /** FFmpeg 的 AV_NOPTS_VALUE（int64 版）。 */
        const val AV_NOPTS_VALUE_LONG: Long = Long.MIN_VALUE

        /** 微秒 PTS 的「未知」哨兵。 */
        const val AV_NOPTS_VALUE: Long = Long.MIN_VALUE

        /**
         * `AVERROR_EOF` 的值。
         *
         * JavaCPP 把 avutil 里的 `AVERROR_*` 生成为**函数**而非常量（如 `AVERROR_EOF()`），
         * 但 `av_read_frame` 的返回值需要与它比较；每次调用函数比较即可，这里缓存其值
         * 以避免循环中反复跨界调用。
         */
        private val AVERROR_EOF_VALUE: Int = avutil.AVERROR_EOF()

        /** 把 FFmpeg 负错误码渲染为可读字符串。 */
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

        /** 编码 ID → 可读名。 */
        fun codecName(id: Int): String = when (id) {
            avcodec.AV_CODEC_ID_H264 -> "h264"
            avcodec.AV_CODEC_ID_HEVC -> "hevc"
            avcodec.AV_CODEC_ID_VP8 -> "vp8"
            avcodec.AV_CODEC_ID_VP9 -> "vp9"
            avcodec.AV_CODEC_ID_AV1 -> "av1"
            avcodec.AV_CODEC_ID_MPEG4 -> "mpeg4"
            avcodec.AV_CODEC_ID_NONE -> "none"
            else -> "id=$id"
        }
    }
}
