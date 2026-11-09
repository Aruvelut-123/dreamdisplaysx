/**
 * FFmpeg 硬解后端选择器：候选链 + 逐级降级 + 显式覆盖。
 *
 * ## 为什么要有它
 *
 * libvlc 那条路径早就有这套形状：`LibVlc.hwBackendChain` 按平台与 GPU 厂商排出
 * `cuda → d3d11va → none` 这样的顺序，`LibVlc.advanceHwBackendAfterHwFailure` 在识别到
 * 硬解失败标记后前进一步，`-Ddreamdisplayx.hwDecode` 可以强制某个后端或强制软解。
 *
 * FFmpeg 这条路径原来只有一个写死的 `h264_mediacodec`：设备上打不开就整条路放弃，
 * 既没有候选，也没有留下「为什么放弃」的记录。本对象把同一套形状搬过来，
 * 但**判据比 libvlc 可靠**——libvlc 只能抓日志里的字符串（`HW_FAILURE_MARKERS`），
 * 我们直接看 `avcodec_open2` / `avcodec_send_packet` / `avcodec_receive_frame` 的返回码。
 *
 * ## 为什么是纯 JVM
 *
 * 本文件**不引用任何 `android.*`、`org.bytedeco.*`、JNA**，全部是字符串与枚举运算。
 * 这样：
 *  - 桌面端永远不会因为碰它而加载 Android 类；
 *  - 候选链、覆盖、降级顺序可以被单元测试钉住（`FfmpegHwBackendPolicyTest`），
 *    而不必上真机才能知道第 2 步是不是真的被走到。
 *
 * 真正的 native 决策（解码器是否注册、能否 open）由 [FfmpegCodecScheduler] 在
 * 拿到 [Selection] 之后用返回码确认。
 *
 * ## 链的构成
 *
 * 每一档都是 FFmpeg 的**解码器名**（`avcodec_find_decoder_by_name` 的入参），
 * 而不是 `-hwaccel` 那种加速器名：`h264_mediacodec` 本身就是一个完整解码器，
 * 它自己完成「送包 → 硬解 → 取帧」。链的最后一档永远是同一编码的**软件解码器**
 * （`h264` / `hevc` / …），这样降级的终点是「能出画面」而不是「没有后端」。
 *
 * 桌面端目前只有软件那一档：桌面 FFmpeg 解码管线尚未开始（见 README 进度表），
 * 这里不预先编一条 `cuda → vaapi` 的链，避免文档与代码再次脱节。
 */
package com.dreamdisplayx.media.player.ffmpeg

/**
 * FFmpeg 侧需要为之一条链的编码。
 *
 * @param mediacodecDecoder Android 硬解用的 FFmpeg 解码器名；null 表示该编码没有
 *   mediacodec 包装器（这些编码在 Android 上只能软解）
 * @param softwareDecoder 同编码的软件解码器名，链的终点
 */
internal enum class FfmpegCodec(
    val mediacodecDecoder: String?,
    val softwareDecoder: String,
) {
    /** H.264 / AVC。 */
    H264("h264_mediacodec", "h264"),

    /** H.265 / HEVC。 */
    HEVC("hevc_mediacodec", "hevc"),

    /** VP8。 */
    VP8("vp8_mediacodec", "vp8"),

    /** VP9。 */
    VP9("vp9_mediacodec", "vp9"),

    /** AV1。 */
    AV1("av1_mediacodec", "av1"),

    /** MPEG-4 Part 2。 */
    MPEG4("mpeg4_mediacodec", "mpeg4"),

    /**
     * 无法识别的编码：没有链（[FfmpegHwBackendPolicy.chain] 返回空）。
     *
     * 未知编码不该被硬解选择器猜一个名字去试——猜错只会换来一次无意义的
     * `avcodec_find_decoder_by_name` 失败和一条误导性日志。
     */
    UNKNOWN(null, "");

    /** 该编码在 Android 上是否有 mediacodec 硬解解码器。 */
    val hasMediacodec: Boolean get() = mediacodecDecoder != null

    companion object {
        /**
         * 由可读编码名（[FfmpegDemuxer.codecName] 的输出，如 `"h264"`）反查枚举。
         *
         * 接受解码器名（`"h264_mediacodec"`）也接受裸编码名（`"h264"`），
         * 因为调用点两种都有。
         */
        fun fromName(name: String): FfmpegCodec {
            val bare = name.removeSuffix("_mediacodec").lowercase()
            return when (bare) {
                "h264", "avc" -> H264
                "hevc", "h265" -> HEVC
                "vp8" -> VP8
                "vp9" -> VP9
                "av1" -> AV1
                "mpeg4" -> MPEG4
                else -> UNKNOWN
            }
        }
    }
}

/** 链上的一档：一个 FFmpeg 解码器名，以及它是否走硬件。 */
internal data class FfmpegHwBackend(
    /** `avcodec_find_decoder_by_name` 的入参。 */
    val decoderName: String,
    /** true 表示这一档会走 GPU（mediacodec / cuvid / …），false 表示纯软件。 */
    val hardware: Boolean,
)

/**
 * 选中的后端。
 *
 * @param decoderName 实际要交给 `avcodec_find_decoder_by_name` 的名字
 * @param hardware 是否硬件解码
 * @param step 在 [chain] 中的下标；由 `-Ddreamdisplayx.hwDecode` 强制指定时为 -1
 * @param chain 本次选择所依据的完整候选链，供日志与 F3 诊断展示
 */
internal data class FfmpegHwSelection(
    val decoderName: String,
    val hardware: Boolean,
    val step: Int,
    val chain: List<FfmpegHwBackend>,
) {
    /** 是否已降级到软件解码。 */
    val isSoftware: Boolean get() = !hardware

    /** 是否还有可降级的下一档。 */
    val hasNext: Boolean get() = step >= 0 && step + 1 < chain.size

    override fun toString(): String =
        "FfmpegHwSelection($decoderName, hardware=$hardware, step=$step/${chain.size})"
}

internal object FfmpegHwBackendPolicy {

    /** 平台维度：链的构成只取决于它。 */
    enum class Platform { ANDROID, DESKTOP }

    /**
     * 为 [codec] 在 [platform] 上排出候选链。
     *
     * - Android 且有 mediacodec 包装器：`[<codec>_mediacodec, <codec>]`
     * - 其余情况：`[<codec>]`（只有软件一档）
     * - [FfmpegCodec.UNKNOWN]：空链（调用方据此放弃而不是瞎猜）
     *
     * 顺序在运行期永不重排——硬件不会变。降级只靠 [advance] 让下标前移。
     */
    fun chain(codec: FfmpegCodec, platform: Platform): List<FfmpegHwBackend> {
        val software = codec.softwareDecoder
        if (software.isEmpty()) return emptyList()
        val softwareBackend = FfmpegHwBackend(software, hardware = false)
        val mc = codec.mediacodecDecoder
        return if (platform == Platform.ANDROID && mc != null) {
            listOf(FfmpegHwBackend(mc, hardware = true), softwareBackend)
        } else {
            listOf(softwareBackend)
        }
    }

    /**
     * 为下一次打开决定用哪一档。
     *
     * 优先级（与 libvlc 的 `LibVlc.currentHwBackend` 一致）：
     *  1. `-Ddreamdisplayx.hwDecode=<name>` 非空 → 强制该解码器，链不参与；
     *  2. 该覆盖变量为空串 → 强制软件（对应 libvlc 的 `"none"`）；
     *  3. 硬解被关闭（[useHwAccel] 与 [noHardwareAccel]）→ 软件那一档；
     *  4. 否则取链上第 [step] 档，越界则取最后一档。
     *
     * @return null 表示这条编码没有可用后端（未知编码），调用方必须放弃
     *   并交给上层回退，而不是继续尝试
     */
    fun select(
        codec: FfmpegCodec,
        platform: Platform,
        step: Int,
        useHwAccel: Boolean,
        noHardwareAccel: Boolean,
        override: String?,
    ): FfmpegHwSelection? {
        val chain = chain(codec, platform)
        if (chain.isEmpty()) return null

        // 1 / 2. 显式覆盖：与 libvlc 完全同形，空串是「明确软解」，不是「没设置」。
        //        之所以要区分：不传覆盖时行为由链决定，若把空串当成没设置，
        //        `-Ddreamdisplayx.hwDecode=` 就无法用来做「彻底关掉硬解」的对照实验。
        //
        //        空串必须翻译成该编码的**软件解码器名**，而不是原样当成解码器名：
        //        `avcodec_find_decoder_by_name("")` 必然返回 null，那样「强制软解」会变成
        //        「强制失败」。libvlc 那边传的是 "none"（一个后端名），我们这里传的是
        //        解码器名，语义要换算过来。
        if (override != null) {
            val decoder = if (override.isEmpty()) codec.softwareDecoder else override
            return FfmpegHwSelection(
                decoderName = decoder,
                hardware = decoder != codec.softwareDecoder,
                step = -1,
                chain = chain,
            )
        }

        // 3. 硬解被全局关闭：直接落到链尾（软件那一档），而不是返回 null。
        //    返回 null 会让调用方以为「没有后端」从而整条路放弃——软解明明可用。
        if (!useHwAccel || noHardwareAccel) {
            val last = chain.lastIndex
            return FfmpegHwSelection(chain[last].decoderName, chain[last].hardware, last, chain)
        }

        // 4. 取当前档位，越界收敛到最后一档（链被重置过 / 上报的 step 过期都不该炸）。
        val index = step.coerceIn(0, chain.lastIndex)
        return FfmpegHwSelection(chain[index].decoderName, chain[index].hardware, index, chain)
    }

    /**
     * 当前档失败后，下一档该用谁。
     *
     * @return 降级后的选择；null 表示已经在链尾（或本就没有链），无可降级
     *
     * 与 libvlc 的 `advanceHwBackendAfterHwFailure` 有一处刻意的差别：那边把
     * 「有覆盖变量」当成不降级，这里在 [select] 就把覆盖处理掉了，[selection]
     * 的 `step == -1` 天然表明「被强制」，因此这里同样返回 null——强制指定的后端
     * 失败时应当直接报错，而不是悄悄换一个用户没要求的后端。
     */
    fun advance(selection: FfmpegHwSelection): FfmpegHwSelection? {
        if (!selection.hasNext) return null
        val next = selection.step + 1
        val backend = selection.chain[next]
        return FfmpegHwSelection(backend.decoderName, backend.hardware, next, selection.chain)
    }

    /** 链的可读形式，供日志与 F3 叠加层显示，例如 `"h264_mediacodec → h264"`。 */
    fun describe(chain: List<FfmpegHwBackend>): String =
        if (chain.isEmpty()) "(none)" else chain.joinToString(" → ") { it.decoderName }
}
