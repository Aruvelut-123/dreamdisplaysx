package com.dreamdisplayx.media.player.process

import com.dreamdisplayx.api.security.policy.MediaHosts
import com.dreamdisplayx.media.player.ffmpeg.AndroidMediaCodecHwSolver
import com.dreamdisplayx.media.player.ffmpeg.FfmpegCodec
import com.dreamdisplayx.media.player.ffmpeg.FfmpegDemuxer
import com.dreamdisplayx.media.player.ffmpeg.FfmpegHwBackendKind
import com.dreamdisplayx.media.player.ffmpeg.FfmpegNatives
import com.dreamdisplayx.media.runtime.security.MediaHostGuard
import com.dreamdisplayx.util.log.DreamLog
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 用 FFmpeg 解封装 + 解码器取一帧，替代 libvlc 的缩略图提取。
 *
 * ## 为什么可以替换 `LibVlcFrameExtractor`
 *
 * libvlc 那个实现之所以有 600 多行、四个对齐缓冲、代际令牌、时间门控、两阶段相位，
 * 是因为它被迫用 vmem 回调**边播放边抓帧**：libvlc 是「推」模型，帧什么时候到不由
 * 我们决定，所以要靠「等到播放头越过目标 + 丢弃 seek 前的陈旧帧」来保证拿到的是
 * 正确位置的帧。FFmpeg 是「拉」模型——`av_seek_frame` 之后我们主动
 * `avcodec_receive_frame`，第一个吐出来的帧就是目标位置（或其后最近的关键帧），
 * 不存在「陈旧的、位置更高的帧混进来」这整类问题。
 *
 * 这是迁移里唯一一处「FFmpeg 版本比 libvlc 版本简单得多」的能力，因此值得替换。
 *
 * ## 为什么复用 [AndroidMediaCodecHwSolver]
 *
 * 它已经把「解码 → 必要时 `av_hwframe_transfer_data` → `sws_scale` → 打包 BGRA」
 * 整条链路走通了，并且按解码器【类型】（[FfmpegHwBackendKind]）而不是 `hardware`
 * 布尔分派 mediacodec 参数。这里再写一遍色彩空间转换只会多一份可能出错的代码。
 *
 * ## 失败一律返回 null
 *
 * 缩略图是可选装饰：没有它播放照旧。而一条异常穿过协程会拖垮共享 IO 调度器，
 * 所以全程 `runCatching` + null，不向调用方抛。
 */
object FfmpegFrameExtractor {

    private val logger = DreamLog.of("FfmpegFrameExtractor")

    /** 打开输入 + 解出首帧的总预算。超时即放弃，缩略图不值得卡住界面。 */
    private const val EXTRACT_TIMEOUT_MS = 12_000L

    /** CDN 防盗链会拒绝默认 UA；与 `FfmpegSession` 保持一致。 */
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /**
     * 一次性提取：打开 [url]，跳到 [offsetNanos]，取一帧，缩放并编码为 JPEG。
     *
     * @return JPEG（无 JPEG 写入器时退化为 PNG）字节，或任何一步失败时的 null
     */
    fun extractJpeg(url: String, offsetNanos: Long, w: Int, h: Int): ByteArray? {
        if (w <= 0 || h <= 0) return null
        val safeUrl = runCatching { MediaHostGuard.resolveSafeUrl(url) }
            .getOrElse {
                logger.warn("rejected unsafe media URL {}: {}", url, it.message)
                return null
            }
        if (!runCatching { FfmpegNatives.install() }.getOrDefault(false)) {
            logger.debug("no FFmpeg runtime; thumbnail extraction unavailable")
            return null
        }
        val deadline = System.nanoTime() + EXTRACT_TIMEOUT_MS * 1_000_000L
        val referer = runCatching { MediaHosts.refererFor(safeUrl) }.getOrNull()

        val demuxer = FfmpegDemuxer(safeUrl, referer = referer, userAgent = BROWSER_UA)
        var solver: AndroidMediaCodecHwSolver? = null
        return try {
            if (!demuxer.open()) {
                logger.warn("thumbnail: demux open failed for {}: {}", safeUrl, demuxer.lastError)
                return null
            }
            val codec = FfmpegCodec.fromName(FfmpegDemuxer.codecName(demuxer.videoCodecId))
            if (codec == FfmpegCodec.UNKNOWN) {
                logger.debug("thumbnail: unsupported codec id {}", demuxer.videoCodecId)
                return null
            }
            val width = demuxer.videoWidth
            val height = demuxer.videoHeight
            if (width <= 0 || height <= 0) return null

            // 硬解在缩略图上没有意义（只要一帧），而且 mediacodec 需要真实 Surface，
            // 在游戏 GL 上下文之外更容易失败。直接软件档：稳定、无设备依赖。
            val s = AndroidMediaCodecHwSolver(
                width = width,
                height = height,
                decoderName = codec.softwareDecoder,
                kind = FfmpegHwBackendKind.SOFTWARE,
            )
            solver = s
            if (!s.open(demuxer.videoExtradata())) {
                logger.debug("thumbnail: decoder open failed: {}", s.lastError)
                return null
            }
            if (offsetNanos > 0L && !demuxer.seekTo(offsetNanos / 1000L)) {
                // seek 失败不是致命错误：沿当前读数继续，只是可能取到靠前的帧。
                logger.debug("thumbnail: seek to {} failed, using first decodable frame", offsetNanos)
            }
            while (System.nanoTime() < deadline) {
                when (demuxer.readVideoPacket()) {
                    FfmpegDemuxer.Result.PACKET_READY -> {
                        val view = demuxer.packetView()
                        if (view == null) continue
                        when (s.decode(view, demuxer.packetSize(), demuxer.packetPtsMicros())) {
                            AndroidMediaCodecHwSolver.Result.FRAME_READY -> {
                                val bytes = readBgra(s) ?: return null
                                return encodeJpeg(bytes, s.frameWidth, s.frameHeight, w, h)
                            }
                            AndroidMediaCodecHwSolver.Result.EOF -> return null
                            AndroidMediaCodecHwSolver.Result.ERROR -> {
                                logger.debug("thumbnail: decode error: {}", s.lastError)
                                return null
                            }
                            // NEED_MORE_INPUT / NOT_OPEN: 继续读下一个包
                            else -> Unit
                        }
                    }
                    FfmpegDemuxer.Result.EOF -> return null
                    FfmpegDemuxer.Result.ERROR -> {
                        logger.debug("thumbnail: read error: {}", demuxer.lastError)
                        return null
                    }
                    // 该路径只读视频包，音频包在这里已被 demuxer 跳过。
                    else -> Unit
                }
            }
            logger.debug("thumbnail: timed out before the first frame for {}", safeUrl)
            null
        } catch (t: Throwable) {
            logger.debug("thumbnail extraction failed: {}", t.message)
            null
        } finally {
            runCatching { solver?.close() }
            runCatching { demuxer.close() }
        }
    }

    /**
     * 一个视频一条长寿命会话：容器与解码器只打开一次，之后每次 [extractAt] 只做
     * seek → flush → 解一帧。
     *
     * ## 为什么值得保留「每 key 复用」
     *
     * 打开容器要建连接、读 moov/索引、probe 码流，在网络源上是几百毫秒到几秒。拖动进度条时
     * 鼠标每移动几十像素就会要一帧，若每次都重开连接，预览会一直停在旧帧上。复用后省掉的
     * 恰好是这段固定开销。
     *
     * ## 与 libvlc 版本的根本差别
     *
     * libvlc 的 `ScrubSession` 必须「边播放边抓帧」（vmem 推模型），因此需要对齐缓冲、
     * 代际令牌、时间门控和保活列表——它没法保证拿到的是哪个位置的帧，只能靠启发式丢弃。
     * 这里 `av_seek_frame` + `avcodec_flush_buffers` 之后主动 `receive_frame`，第一个
     * 吐出来的帧就是目标位置（或其后最近的可解码帧），不存在「陈旧帧混进来」这个问题。
     *
     * ## 线程约束
     *
     * 与 [AndroidMediaCodecHwSolver] 一致：**非线程安全**，所有方法必须串行调用。
     * `ScrubPreview` 保证每个 key 同时只有一个提取协程。
     */
    class ScrubSession(private val url: String) : AutoCloseable {

        private val logger = DreamLog.of("FfmpegScrubSession")

        private var demuxer: FfmpegDemuxer? = null
        private var solver: AndroidMediaCodecHwSolver? = null
        private var codec: FfmpegCodec = FfmpegCodec.UNKNOWN
        private var srcWidth = 0
        private var srcHeight = 0

        /** 打开容器与解码器。重复调用是空操作。失败时自行清理，下次调用可重试。 */
        fun open(): Boolean {
            if (solver != null) return true
            val safeUrl = runCatching { MediaHostGuard.resolveSafeUrl(url) }.getOrElse { error ->
                logger.warn("scrub: rejected unsafe media URL {}: {}", url, error.message)
                return false
            }
            if (!runCatching { FfmpegNatives.install() }.getOrDefault(false)) {
                logger.debug("scrub: no FFmpeg runtime")
                return false
            }
            val d = FfmpegDemuxer(safeUrl, referer = runCatching { MediaHosts.refererFor(safeUrl) }.getOrNull(), userAgent = BROWSER_UA)
            if (!d.open()) {
                logger.warn("scrub: demux open failed for {}: {}", safeUrl, d.lastError)
                runCatching { d.close() }
                return false
            }
            val c = FfmpegCodec.fromName(FfmpegDemuxer.codecName(d.videoCodecId))
            if (c == FfmpegCodec.UNKNOWN || d.videoWidth <= 0 || d.videoHeight <= 0) {
                logger.warn("scrub: unsupported or sizeless video for {} (codecId={})", safeUrl, d.videoCodecId)
                runCatching { d.close() }
                return false
            }
            // 软解档：预览只要一帧，硬解省不下任何东西，却要真实 Surface 并可能整条失败。
            val s = AndroidMediaCodecHwSolver(
                width = d.videoWidth,
                height = d.videoHeight,
                decoderName = c.softwareDecoder,
                kind = FfmpegHwBackendKind.SOFTWARE,
            )
            if (!s.open(d.videoExtradata())) {
                logger.warn("scrub: decoder open failed for {}: {}", safeUrl, s.lastError)
                runCatching { s.close() }
                runCatching { d.close() }
                return false
            }
            demuxer = d
            solver = s
            codec = c
            srcWidth = d.videoWidth
            srcHeight = d.videoHeight
            return true
        }

        /**
         * 取 [offsetNanos] 处的一帧，缩放为 [w]x[h] 并编码。任何一步失败返回 null。
         *
         * 这里是「拉」模型：seek 之后必须 [AndroidMediaCodecHwSolver.flush]，否则解码器
         * 内部还压着上一个位置的包和已重排待输出的帧，会先吐出几张旧画面。
         */
        @Synchronized
        fun extractAt(offsetNanos: Long, w: Int, h: Int): ByteArray? {
            val d = demuxer ?: return null
            val s = solver ?: return null
            if (w <= 0 || h <= 0) return null
            val deadline = System.nanoTime() + EXTRACT_TIMEOUT_MS * 1_000_000L

            // 位置 0 无需 seek：一来 seek 到 0 可能仍要重建索引，二来起播位置天然就在开头。
            if (offsetNanos > 0L) {
                if (!d.seekTo(offsetNanos / 1000L)) {
                    // seek 失败不致命：沿当前读数继续，只是可能取到更靠前的帧。
                    logger.debug("scrub: seek to {} failed, using the current position", offsetNanos)
                }
                // seek 之后必须清空解码器：不清就会先吐旧位置的帧。
                s.flush()
            }
            while (System.nanoTime() < deadline) {
                when (d.readVideoPacket()) {
                    FfmpegDemuxer.Result.PACKET_READY -> {
                        val view = d.packetView() ?: continue
                        when (s.decode(view, d.packetSize(), d.packetPtsMicros())) {
                            AndroidMediaCodecHwSolver.Result.FRAME_READY -> {
                                val bgra = readBgra(s) ?: return null
                                return encodeJpeg(bgra, srcWidth, srcHeight, w, h)
                            }
                            AndroidMediaCodecHwSolver.Result.EOF,
                            AndroidMediaCodecHwSolver.Result.ERROR -> return null
                            else -> Unit
                        }
                    }
                    FfmpegDemuxer.Result.EOF -> return null
                    FfmpegDemuxer.Result.ERROR -> {
                        logger.debug("scrub: read error at {}: {}", offsetNanos, d.lastError)
                        return null
                    }
                    else -> Unit
                }
            }
            logger.debug("scrub: timed out seeking to {} for {}", offsetNanos, url)
            return null
        }

        /** 释放容器与解码器。可重复调用。 */
        @Synchronized
        override fun close() {
            runCatching { solver?.close() }
            runCatching { demuxer?.close() }
            solver = null
            demuxer = null
            codec = FfmpegCodec.UNKNOWN
            srcWidth = 0
            srcHeight = 0
        }
    }

    /** 把解码器内部复用的 BGRA 缓冲拷出来（下一帧会覆盖它，必须当场取走）。 */
    private fun readBgra(solver: AndroidMediaCodecHwSolver): ByteArray? {
        val buf = solver.outputBgra() ?: return null
        val remaining = buf.remaining()
        if (remaining <= 0) return null
        val out = ByteArray(remaining)
        buf.get(out)
        return out
    }

    /**
     * BGRA → 等比缩放 → JPEG。
     *
     * 缩放用最近邻而不是双线性：缩略图最终只有几十像素高，且这一步在 IO 线程上跑，
     * 品质差异看不出来，而最近邻没有浮点循环。
     */
    private fun encodeJpeg(bgra: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray? {
        if (srcW <= 0 || srcH <= 0 || bgra.size < srcW * srcH * 4) return null
        val rect = ThumbScalePolicy.letterbox(srcW, srcH, dstW, dstH)
        val ox = rect[0]
        val oy = rect[1]
        val dw = rect[2]
        val dh = rect[3]
        val image = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB)
        // 先铺黑底：letterbox 的边必须真的黑，不能依赖 BufferedImage 的初始值。
        for (y in 0 until dstH) {
            for (x in 0 until dstW) image.setRGB(x, y, 0)
        }
        for (y in 0 until dh) {
            val srcY = (y.toLong() * srcH / dh).toInt().coerceIn(0, srcH - 1)
            val srcRow = srcY * srcW * 4
            for (x in 0 until dw) {
                val srcX = (x.toLong() * srcW / dw).toInt().coerceIn(0, srcW - 1)
                image.setRGB(ox + x, oy + y, ThumbScalePolicy.bgraToRgbInt(bgra, srcRow + srcX * 4))
            }
        }
        return encode(image)
    }

    /** 编码为 JPEG；没有写入器时退化为 PNG。都没有则 null。 */
    private fun encode(image: BufferedImage): ByteArray? {
        val out = ByteArrayOutputStream()
        try {
            if (ImageIO.write(image, "jpg", out)) return out.toByteArray()
            logger.debug("no JPEG writer available; trying PNG")
        } catch (t: Throwable) {
            logger.debug("JPEG encode failed: {}", t.message)
        }
        return try {
            ByteArrayOutputStream().use { pngOut ->
                if (ImageIO.write(image, "png", pngOut)) pngOut.toByteArray() else null
            }
        } catch (t: Throwable) {
            logger.debug("PNG encode failed: {}", t.message)
            null
        }
    }
}