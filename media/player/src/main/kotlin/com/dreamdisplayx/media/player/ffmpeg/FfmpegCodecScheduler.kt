package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.util.OsInfo
import com.dreamdisplayx.util.log.DreamLog
import java.util.concurrent.atomic.AtomicInteger

internal object FfmpegCodecScheduler {
    private val logger = DreamLog.of("FfmpegCodecScheduler")

    /**
     * 当前是否走 FFmpeg 解码路径。
     *
     * 注意：这个开关目前**只写不读**——`FfmpegSession` 尚未实现，所以没有任何代码
     * 依据它在 libvlc 与 FFmpeg 之间做选择。保留它是因为它记录了「环境是否具备条件」，
     * 接线时就是它来决定走哪条路。这一点已记在 README 的进度表里，不要误以为已生效。
     */
    @Volatile
    var useFfmpeg: Boolean = false

    /**
     * 当前在候选链上的档位。失败后由 [reportFailure] 前进，配置重置时归零。
     *
     * 全局单例而非每会话一份：硬件能力是进程级的，同一台机器上第二条会话没有理由
     * 重新从「第一档」开始试一遍已经证明失败的后端。这与 libvlc 的
     * `LibVlc.hwBackendStep` 语义一致。
     */
    private val hwStep = AtomicInteger(0)

    /** 最近一次选中的后端，供 F3 诊断显示「实际用了什么」而不是「本来想用什么」。 */
    @Volatile
    var activeSelection: FfmpegHwSelection? = null
        private set

    /**
     * 探测一次（由配置开关触发）。
     *
     * @param enableAndroidMediaCodec 客户端 `enable-android-mediacodec` 的取值
     */
    fun probeAndSet(enableAndroidMediaCodec: Boolean) {
        if (!OsInfo.isAndroid) {
            // 桌面端：始终使用软解回退（libvlc），不加载任何 Android 类
            useFfmpeg = false
            logger.info("Desktop platform: FFmpeg MediaCodec disabled, using libvlc fallback")
            return
        }
        if (enableAndroidMediaCodec && FfmpegMediaCodecBridge.isAvailable()) {
            useFfmpeg = true
            logger.info("Android: FFmpeg MediaCodec hard decode enabled")
        } else {
            useFfmpeg = false
            if (enableAndroidMediaCodec) {
                logger.warn("Android: MediaCodec not available, falling back to libvlc")
            } else {
                logger.info("Android: MediaCodec disabled by config, using libvlc")
            }
        }
    }

    /**
     * 为 [codec] 选出本次要用的解码器。
     *
     * @param useHwAccel 配置里的硬解开关（`PlaybackConfig.useHwAccel`）
     * @param noHardwareAccel `-Ddreamdisplayx.noHardwareAccel` 诊断开关
     * @param override `-Ddreamdisplayx.hwDecode` 覆盖值；null = 未设置，"" = 强制软解
     * @return null 表示这条编码没有任何可用后端（未知编码），调用方必须回退而不是硬试
     */
    fun selectBackend(
        codec: FfmpegCodec,
        useHwAccel: Boolean,
        noHardwareAccel: Boolean,
        override: String? = System.getProperty("dreamdisplayx.hwDecode"),
    ): FfmpegHwSelection? {
        val platform = if (OsInfo.isAndroid) {
            FfmpegHwBackendPolicy.Platform.ANDROID
        } else {
            FfmpegHwBackendPolicy.Platform.DESKTOP
        }
        val selection = FfmpegHwBackendPolicy.select(
            codec = codec,
            platform = platform,
            step = hwStep.get(),
            useHwAccel = useHwAccel,
            noHardwareAccel = noHardwareAccel,
            override = override,
        )
        if (selection == null) {
            logger.warn("No FFmpeg backend for codec {} (chain empty)", codec)
            return null
        }
        activeSelection = selection
        logger.info(
            "FFmpeg backend for {}: {} (chain: {})",
            codec, selection.decoderName, FfmpegHwBackendPolicy.describe(selection.chain),
        )
        return selection
    }

    /**
     * 报告当前后端失败，请求降级。
     *
     * 调用点应当是**确认**属于后端问题的失败（`avcodec_open2` 返回 < 0、mediacodec 死、
     * `av_hwframe_transfer_data` 持续失败），而不是网络错误——网络错误该让重试策略处理，
     * 换解码器救不回一个 404。
     *
     * @param failed 刚失败的那个选择（或用 [activeSelection]）
     * @param reason 失败原因，写进日志供诊断
     * @return 降级后的新选择；null 表示已到链尾（或当前档位被 `-Ddreamdisplayx.hwDecode`
     *   强制指定，不允许悄悄换成用户没要求的后端），调用方应当回退
     */
    fun reportFailure(failed: FfmpegHwSelection?, reason: String): FfmpegHwSelection? {
        val current = failed ?: activeSelection ?: return null
        val next = FfmpegHwBackendPolicy.advance(current)
        if (next == null) {
            logger.warn(
                "FFmpeg backend '{}' failed ({}), no further fallback in chain {}",
                current.decoderName, reason, FfmpegHwBackendPolicy.describe(current.chain),
            )
            return null
        }
        logger.warn(
            "FFmpeg backend '{}' failed ({}), falling back to '{}'",
            current.decoderName, reason, next.decoderName,
        )
        hwStep.set(next.step)
        activeSelection = next
        return next
    }

    /** 重置状态（配置变更时调用）。 */
    fun reset() {
        useFfmpeg = false
        hwStep.set(0)
        activeSelection = null
        FfmpegMediaCodecBridge.reset()
    }
}