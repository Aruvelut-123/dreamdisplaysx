package com.dreamdisplayx.media.player.ffmpeg

/**
 * 纯算术部分：Android `AudioTrack` 的固定常量、缓冲尺寸决策，以及播放头计数器的
 * 跨 2^32 回绕换算。
 *
 * ## 为什么单独拆出来
 *
 * `AudioTrack` 本身必须经反射调用（理由见 [FfmpegAndroidPcmSink]），反射代码无法在
 * 桌面 JVM 上单测。但这一块——「缓冲该开多大」「已播字节怎么从 32 位计数器累加」——
 * 是可以脱离设备验证的，而它恰好是最容易算错的两处：
 *
 * - 缓冲太小：每次 [AudioTrackSpec.MODE_STREAM] 写入都立刻见底，声音断断续续；
 * - 缓冲太大：seek 之后要等旧声音播完，音画不同步；
 * - 播放头不处理回绕：2^32 帧 ≈ 27 小时，长时间挂着的显示器会突然跳时钟。
 *
 * 这些一旦算错都不会抛异常，只会「听起来不对」，所以必须能单测
 * （`AudioTrackSpecTest`）。
 */
internal object AudioTrackSpec {

    // ── android.media.AudioTrack 常量 ────────────────────────────────────────
    //
    // 这里重写一遍而不是引用：本模块不可 `import android.media.*`——桌面与
    // Pojav/Zalith 的游戏 JVM 都没有 android.jar，引用即 `NoClassDefFoundError`。

    /** `AudioManager.STREAM_MUSIC`：显示器的声音属于媒体流，会被媒体音量控制。 */
    const val STREAM_MUSIC = 3

    /** `AudioFormat.CHANNEL_OUT_STEREO`。 */
    const val CHANNEL_OUT_STEREO = 0x0000000C

    /** `AudioFormat.ENCODING_PCM_16BIT`。 */
    const val ENCODING_PCM_16BIT = 2

    /** `AudioTrack.MODE_STREAM`：边写边播，适合无限长的播放流。 */
    const val MODE_STREAM = 1

    // ── 缓冲决策 ─────────────────────────────────────────────────────────────

    /**
     * 目标缓冲时长。
     *
     * 与桌面 `FfmpegDesktopPcmSink` 的 200 ms 保持同一量级，但理由不同：桌面那条是
     * 为了扛住解码突发（B 帧重排 + 关键帧常达 150 ms），Android 这条要同时容忍
     * OpenSL/AAudio 底层的调度抖动。取 150 ms——比桌面的 200 ms 略小，因为 Android
     * 上音画同源（同一台设备），lead 更容易被察觉。
     */
    const val TARGET_BUFFER_MS = 150

    /**
     * 缓冲下限（毫秒）。低于这个值在低端机上必然欠载。
     *
     * 40 ms 约等于一个硬件周期，是「再小就没有意义」的界线。
     */
    const val MIN_BUFFER_MS = 40

    /** 缓冲上限（毫秒）。超过 500 ms 之后 seek 会有明显延迟。 */
    const val MAX_BUFFER_MS = 500

    /** 覆盖值：`-Ddreamdisplayx.androidAudioBufferMs`。 */
    const val BUFFER_MS_PROPERTY = "dreamdisplayx.androidAudioBufferMs"

    /**
     * 本会话该请 `AudioTrack.getMinBufferSize` 给多少字节。
     *
     * 取「平台建议值」与「本策略目标值」的较大者：平台建议值是硬件能吃下的最小值，
     * 而我们还需要一点余量扛解码突发。所以先按目标时长算，再抬到不低于平台值。
     *
     * @param sampleRate 输出采样率（Hz）
     * @param bytesPerFrame 一帧立体声 S16 的字节数（4）
     * @param platformMinBytes `AudioTrack.getMinBufferSize` 的返回值；<= 0 表示查询失败
     * @param bufferMs 目标缓冲毫秒数，最终会被钳进 [MIN_BUFFER_MS]..[MAX_BUFFER_MS]
     * @return 请求的缓冲字节数，保证是 [bytesPerFrame] 的整数倍且 > 0
     */
    fun bufferBytes(
        sampleRate: Int,
        bytesPerFrame: Int,
        platformMinBytes: Int,
        bufferMs: Int = TARGET_BUFFER_MS,
    ): Int {
        if (sampleRate <= 0 || bytesPerFrame <= 0) return 0
        val ms = bufferMs.coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS)
        val byDuration = sampleRate.toLong() * bytesPerFrame.toLong() * ms / 1000L
        val chosen = maxOf(byDuration, platformMinBytes.toLong().coerceAtLeast(0L))
        // 对齐到整帧：半帧写入会让左右声道永久错开（与桌面 sink 同一条规则）。
        val aligned = (chosen / bytesPerFrame * bytesPerFrame).coerceAtLeast(bytesPerFrame.toLong())
        return aligned.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** 从系统属性读覆盖值；缺省或非法时回落到 [TARGET_BUFFER_MS]。 */
    fun bufferMsFromProperty(): Int =
        System.getProperty(BUFFER_MS_PROPERTY)?.trim()?.toIntOrNull()
            ?.coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS)
            ?: TARGET_BUFFER_MS

    // ── 播放头计数器 ─────────────────────────────────────────────────────────

    /**
     * `getPlaybackHeadPosition()` 返回的是 32 位**无符号**帧计数，Java 拿到的是有符号
     * `int`。长会话里它会静默回绕（2^32 帧 ≈ 27 小时 @44.1 kHz）。
     */
    fun unsigned(raw32: Int): Long = raw32.toLong() and 0xFFFFFFFFL

    /**
     * 单次轮询增量帧数，回绕安全。
     *
     * 用「当前 - 上次」按 2^32 取模得到增量：计数器从 `0xFFFFFFFF` 走到 `0` 时，
     * 差值在 32 位补码下正好是 1，不需要额外的回绕分支。
     *
     * @param currentRaw32 本次 `getPlaybackHeadPosition()` 的原始返回值
     * @param lastRawUnsigned 上次的**无符号**值；首次轮询传 {@code unsigned(currentRaw32)}
     *   以外任何值都会算出一个巨大增量，因此调用方必须先用 [unsigned] 初始化基线
     * @return 增量帧数（0..[MAX_PLAUSIBLE_DELTA]）；不可信时返回 0
     */
    fun deltaFrames(currentRaw32: Int, lastRawUnsigned: Long): Long {
        val cur = unsigned(currentRaw32)
        val last = lastRawUnsigned and 0xFFFFFFFFL
        val delta = (cur - last) and 0xFFFFFFFFL
        if (delta > MAX_PLAUSIBLE_DELTA) {
            // 超过 2^31 帧的「前进」只可能是设备被重置、或调用方基线没初始化。
            // 当成 0 而不是当成倒退：宁可少算一次，也不要让主时钟跳变。
            return 0L
        }
        return delta
    }

    /**
     * 一次轮询最多可信多少帧增量。
     *
     * 2^31 帧 ≈ 13.5 小时 @44.1 kHz，远超任何合理轮询间隔（本项目 1 s），因此任何
     * 超过它的差值都不是真实播放进度。
     */
    const val MAX_PLAUSIBLE_DELTA: Long = 1L shl 31

    /** 帧数 → 字节数。 */
    fun framesToBytes(frames: Long, bytesPerFrame: Int): Long =
        if (frames <= 0L || bytesPerFrame <= 0) 0L else frames * bytesPerFrame

    /**
     * 设备环形缓冲里还没播出的字节数（这才是真实的 A/V lead）。
     *
     * @param writtenBytes 我们交给设备的字节总数
     * @param playedBytes 设备报告的已播字节数
     */
    fun queuedBytes(writtenBytes: Long, playedBytes: Long): Long =
        (writtenBytes - playedBytes).coerceAtLeast(0L)
}
