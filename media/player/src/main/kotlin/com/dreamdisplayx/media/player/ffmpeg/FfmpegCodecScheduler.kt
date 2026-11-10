package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.util.OsInfo
import com.dreamdisplayx.util.log.DreamLog
import java.util.concurrent.atomic.AtomicInteger

internal object FfmpegCodecScheduler {
    private val logger = DreamLog.of("FfmpegCodecScheduler")

    /**
     * 当前是否走 FFmpeg 解码路径。
     *
     * 注意：这个开关的记录值是「环境是否具备条件」。真正在 libvlc 与 FFmpeg 之间做选择的
     * 是 `MediaPlayer`，它读这个值决定起哪条会话。
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
     * 桌面端不再短路为软解/libvlc：只要下载到的 FFmpeg 运行时能注册，`useFfmpeg` 就为 true，
     * 由 `FfmpegHwBackendPolicy` 在桌面链上按 `cuvid/qsv/vaapi/videotoolbox → 软件` 逐级降级。
     *
     * @param ffmpegPipelineEnabled 客户端 `enable-ffmpeg-pipeline` 的取值
     */
    fun probeAndSet(ffmpegPipelineEnabled: Boolean) {
        if (!ffmpegPipelineEnabled) {
            useFfmpeg = false
            logger.info("FFmpeg pipeline disabled by config, using libvlc")
            return
        }
        // 原生运行时是否就绪与平台无关；Android 额外要求 MediaCodec 桥可用（硬解门槛）。
        val runtimeReady = FfmpegNatives.install()
        if (!runtimeReady) {
            useFfmpeg = false
            logger.warn("FFmpeg pipeline enabled but the native runtime is not available, using libvlc")
            return
        }
        if (OsInfo.isAndroid) {
            if (FfmpegMediaCodecBridge.isAvailable()) {
                useFfmpeg = true
                logger.info("Android: FFmpeg MediaCodec hard decode enabled")
            } else {
                // Android 上的软解档同样需要 AVCodec，桥只决定硬解那几档；
                // 这里仍允许走 FFmpeg，让策略链自行降到软件解码器。
                useFfmpeg = true
                logger.warn("Android: MediaCodec bridge unavailable; FFmpeg will use the software rung")
            }
        } else {
            useFfmpeg = true
            logger.info("Desktop: FFmpeg decode pipeline enabled (hw chain → software)")
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
        val platform = FfmpegHwBackendPolicy.Platform.of(
            isAndroid = OsInfo.isAndroid,
            isWindows = OsInfo.isWindows,
            isMac = OsInfo.isMac,
        )
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