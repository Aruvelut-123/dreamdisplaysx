package com.dreamdisplayx.media.player.session

/**
 * 决定某个播放会话由哪个后端承担哪些职责。
 *
 * ## 为什么需要它
 *
 * 迁移到 FFmpeg 后，一个显示器不再等于一个后端：FFmpeg 管线只解视频（解封装 + MediaCodec
 * 硬解 + 发布 BGRA），音频仍然要走 libvlc，因为 libvlc 的 `:no-video` 音频路径承载了 Android
 * 的 AudioTrack 预算、暖轨道池和 park/resume 记账。把那段逻辑在 FFmpeg 旁边重写一遍，等于把
 * 模块里最危险的代码复制一份而收益为零。
 *
 * 于是"用哪个后端"不再是单个布尔值，而是三件事各自独立的答案：
 *
 *  1. 谁产视频帧？
 *  2. 谁播音频？
 *  3. 谁提供播放位置（主时钟）？
 *
 * 这三件事分开问，是因为它们**不总是同一个后端**。桌面全部走 libvlc 时三者相同；Android 打开
 * FFmpeg 硬解后，视频归 FFmpeg、音频与时钟归 libvlc。把它们合并成一个开关，接线的第一天就会
 * 写出"视频在 FFmpeg、却去问 libvlc 要 FPS"这类错位调用。
 *
 * 纯 JVM、无 Minecraft/libvlc 依赖，因此可以直接单测（`FfmpegVideoOwnershipTest`）。
 */
internal object FfmpegVideoOwnership {

    /**
     * 一次播放编排中，每个职责由谁承担。
     *
     * @param videoBackend 产视频帧的后端
     * @param audioBackend 出声音的后端
     * @param clockBackend 提供播放位置的后端
     * @param split true 表示视频与音频分处两个后端（需要额外编排：两边都要 start/seek/stop）
     */
    internal data class Plan(
        val videoBackend: Backend,
        val audioBackend: Backend,
        val clockBackend: Backend,
    ) {
        /** 是否把视频/音频拆给了两个后端。 */
        val isSplit: Boolean get() = videoBackend != audioBackend

        /** 视频是否由 FFmpeg 承担。所有"只对 FFmpeg 有意义"的分支都查这个，而不是 `is` 判断。 */
        val videoIsFfmpeg: Boolean get() = videoBackend == Backend.FFMPEG

        /** 音频是否由 FFmpeg 承担。当前恒为 false（见类注释），但调用方不该假设。 */
        val audioIsFfmpeg: Boolean get() = audioBackend == Backend.FFMPEG
    }

    /** 承担职责的后端。 */
    internal enum class Backend { LIBVLC, FFMPEG }

    /**
     * 按配置与平台选出本次播放的编排。
     *
     * 只有在**同时**满足以下条件时才把视频交给 FFmpeg：
     *
     *  - 该显示器被允许使用 FFmpeg 管线（服务端/客户端配置，默认关闭）；
     *  - 当前平台是 Android（mediacodec 后端只在那里存在；桌面链里只有软件档，尚无收益）；
     *  - 该显示器配置允许硬解（软解在 Android 上会明显卡顿，等同于退化）。
     *
     * 任何一条不满足都整体退回 libvlc：**不做**"视频 FFmpeg + 音频 FFmpeg"这种半成品组合，
     * 因为 FFmpeg 侧还没有音频实现，那样会得到静音画面。
     *
     * @param ffmpegRequested 配置是否请求了 FFmpeg 视频管线
     * @param isAndroid 当前是否 Android（Pojav/FCL/Zalith 这类游戏 JVM 也算）
     * @param hwAccelEnabled 是否允许硬解
     * @return 编排；不确定时返回全 libvlc 的保守结果
     */
    internal fun plan(
        ffmpegRequested: Boolean,
        isAndroid: Boolean,
        hwAccelEnabled: Boolean,
    ): Plan = if (ffmpegRequested && isAndroid && hwAccelEnabled) {
        Plan(
            videoBackend = Backend.FFMPEG,
            audioBackend = Backend.LIBVLC,
            clockBackend = Backend.LIBVLC,
        )
    } else {
        Plan(
            videoBackend = Backend.LIBVLC,
            audioBackend = Backend.LIBVLC,
            clockBackend = Backend.LIBVLC,
        )
    }

    /**
     * 播放位置该向谁要。
     *
     * 拆开播放时由音频后端给时钟：音频输出是连续流，天然不抖；而 FFmpeg 侧只有挂钟
     * （见 `FfmpegSession.currentPacingNanos` 的注释），用它做对齐会让每帧显示时刻漂。
     */
    internal fun clockOwner(plan: Plan): Backend = plan.clockBackend
}
