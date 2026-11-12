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
     * ## 视频归谁
     *
     * 只有在**同时**满足以下条件时才把视频交给 FFmpeg：
     *
     *  - 配置请求了 FFmpeg 管线（默认关闭，必须显式打开）；
     *  - 平台维度可行：Android 需要允许硬解（软解在手机上会明显卡顿，等同于退化），
     *    桌面则没有这个要求——桌面链本来就只有软件档，且桌面 CPU 足以软解 1080p。
     *
     *  ## 音频归谁
     *
     *  音频交给 FFmpeg 的前提是本平台真有 PCM 输出实现（[audioSinkAvailable]）。
     *  这是硬性前提，不是修饰：`FfmpegSession` 的音频半边完全建立在 `FfmpegPcmSink`
     *  之上，没有设备就等于没有声音。
     *
     *  这一点曾经决定了 Android 与桌面的分野：Android 长期没有 PCM 设备实现，
     *  音频只能留在 libvlc，形成「FFmpeg 出画面 + libvlc 出声音」的拆分编排。自从
     *  安卓端的 AudioTrack sink 落地，两个平台都有了设备实现，`isSplit` 因此
     *  **恒为 false**——除非日后某个平台真的失去设备能力。
     *
     *  之所以仍把能力作为入参而不是写死 true：Android 上「有实现」和「这台设备能打开
     *  设备」是两件事（Pojav 的游戏 JVM 根本没有 android.jar）。不过后者属于运行期问题，
     *  由 `FfmpegAudioOutput.open()` 自己降级成静音，**不该**让编排回退到 libvlc——
     *  否则一个没有音频类的主机就永远用不上 FFmpeg 视频解码。
     *
     *  @param ffmpegRequested 配置是否请求了 FFmpeg 管线
     *  @param isAndroid 当前是否 Android（Pojav/FCL/Zalith 这类游戏 JVM 也算）
     *  @param hwAccelEnabled 是否允许硬解（仅 Android 影响视频归属）
     *  @param audioSinkAvailable 本平台是否有可用的 FFmpeg PCM 输出实现
     *  @return 编排；不确定时返回全 libvlc 的保守结果
     */
    internal fun plan(
        ffmpegRequested: Boolean,
        isAndroid: Boolean,
        hwAccelEnabled: Boolean,
        audioSinkAvailable: Boolean = false,
    ): Plan {
        val videoIsFfmpeg = ffmpegRequested && (if (isAndroid) hwAccelEnabled else true)
        if (!videoIsFfmpeg) {
            return Plan(
                videoBackend = Backend.LIBVLC,
                audioBackend = Backend.LIBVLC,
                clockBackend = Backend.LIBVLC,
            )
        }
        val audioIsFfmpeg = audioSinkAvailable
        return Plan(
            videoBackend = Backend.FFMPEG,
            audioBackend = if (audioIsFfmpeg) Backend.FFMPEG else Backend.LIBVLC,
            // 时钟跟着音频走：音频输出是连续流，天然不抖，是唯一可靠的音画基准。
            // 音频不在 FFmpeg 侧时，就只有 libvlc 的播放器时钟可用（FFmpeg 侧那点
            // 挂钟只适合单后端兜底，见 FfmpegSession.currentPacingNanos）。
            clockBackend = if (audioIsFfmpeg) Backend.FFMPEG else Backend.LIBVLC,
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
