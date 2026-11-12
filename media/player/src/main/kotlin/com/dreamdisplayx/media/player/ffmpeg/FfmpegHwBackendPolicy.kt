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
 * 桌面端同样按平台排出独立硬解链，最后一档是同一编码的软件解码器。候选解码器名
 * 是**实测**的（各平台 bytedeco 预编译包的实际注册表），见 [FfmpegHwBackendPolicy.desktopHardDecoderNames]。
 * 桌面硬解与 Android 硬解的区别在 `avcodec_open2` 之前要配的东西不同，因此链上每一档
 * 都带一个 [FfmpegHwBackendKind] 标明类型，而不是靠一个 `hardware` 布尔去猜。
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

/**
 * 链上的一档：一个 FFmpeg 解码器名，以及它属于哪一类后端。
 *
 * @param kind 这一档的解码器类型，决定 `avcodec_open2` 之前要配什么（见 [FfmpegHwBackendPolicy]）
 */
internal data class FfmpegHwBackend(
    /** `avcodec_find_decoder_by_name` 的入参。 */
    val decoderName: String,
    /** 这一档的解码器类型。 */
    val kind: FfmpegHwBackendKind = FfmpegHwBackendKind.fromDecoderName(decoderName),
) {
    /** true 表示这一档会走 GPU（mediacodec / cuvid / qsv / …），false 表示纯软件。 */
    val hardware: Boolean get() = kind != FfmpegHwBackendKind.SOFTWARE
}

/**
 * 硬解后端的**类型**，而不是它的名字。
 *
 * ## 为什么必须区分
 *
 * 三类硬解在 `avcodec_open2` 之前要配的东西完全不同，配错就直接打不开：
 *
 * - [MEDIACODEC]：`AV_PIX_FMT_MEDIACODEC` + `ndk_codec=1`（Android 专属）；
 * - [DESKTOP]：**不设** `pix_fmt`——cuvid / qsv / d3d11va / videotoolbox 各自在
 *   `avcodec_open2` 里创建并协商自己的 hwdevice/hwframes，从外面预设 mediacodec 的
 *   像素格式会让它当场失败；
 * - [SOFTWARE]：不设 `pix_fmt`，让解码器自己选（通常 YUV420P）。
 *
 * 「`hardware == true` 就套 mediacodec 参数」是这条链上最容易犯、也最难发现的错误：
 * 它在 Android 上完全正确，在桌面上则让每一档硬解都必然开不起来——而失败会被
 * 逐级降级掩盖，最终表现为「硬解开关打开了，但永远在跑软解」。
 */
internal enum class FfmpegHwBackendKind {
    /** Android MediaCodec 包装器（`*_mediacodec`），需 mediacodec 像素格式与 `ndk_codec`。 */
    MEDIACODEC,

    /** 桌面独立硬解解码器（cuvid / qsv / vaapi / videotoolbox / d3d11va / dxva2 / mf / vulkan）。 */
    DESKTOP,

    /** 纯软件解码器，链的终点。 */
    SOFTWARE;

    companion object {
        /**
         * 由解码器名推断类型。
         *
         * 判据是名字的**后缀**，不是 `hardware` 标志——名字才是真正交给
         * `avcodec_find_decoder_by_name` 的东西，因此它是唯一自洽的依据。
         */
        fun fromDecoderName(name: String): FfmpegHwBackendKind {
            val bare = name.lowercase()
            if (bare.endsWith("_mediacodec")) return MEDIACODEC
            if (bare in FfmpegHwBackendPolicy.desktopHardDecoderNames) return DESKTOP
            return SOFTWARE
        }
    }
}

/**
 * 选中的后端。
 *
 * @param decoderName 实际要交给 `avcodec_find_decoder_by_name` 的名字
 * @param kind 解码器类型，决定 open 前要不要配 mediacodec 像素格式
 * @param step 在 [chain] 中的下标；由 `-Ddreamdisplayx.hwDecode` 强制指定时为 -1
 * @param chain 本次选择所依据的完整候选链，供日志与 F3 诊断展示
 */
internal data class FfmpegHwSelection(
    val decoderName: String,
    val kind: FfmpegHwBackendKind,
    val step: Int,
    val chain: List<FfmpegHwBackend>,
) {
    /** 是否硬件解码。 */
    val hardware: Boolean get() = kind != FfmpegHwBackendKind.SOFTWARE

    /** 是否已降级到软件解码。 */
    val isSoftware: Boolean get() = !hardware

    /** 是否还有可降级的下一档。 */
    val hasNext: Boolean get() = step >= 0 && step + 1 < chain.size

    override fun toString(): String =
        "FfmpegHwSelection($decoderName, kind=$kind, step=$step/${chain.size})"
}

internal object FfmpegHwBackendPolicy {

    /**
     * 独立硬件解码器名：能直接喂给 `avcodec_find_decoder_by_name` 的名字。
     *
     * ## 这些名字是实测出来的，不是猜的
     *
     * `native/ffmpeg/collect.sh` 打包的是 bytedeco 官方预编译包
     * `ffmpeg-8.1.2-1.5.14-<platform>.jar`，注册表因平台而异。名字取自两路证据：
     *
     *  - **能直接执行的平台**（aarch64 本机）跑 `ffprobe -decoders` 取实际注册表；
     *  - 其余平台对 jar 里的 `libavcodec.so.N` / `avcodec-62.dll` / dylib 取字符串。
     *
     * 实测结果（`h264` 与 `hevc` 各有一套同名后缀，此处合并书写）：
     *
     * | 平台 | 真正的硬解解码器 |
     * |---|---|
     * | Windows x86_64 | `cuvid` `qsv` `d3d11va` `dxva2` `mf` `vulkan` |
     * | macOS | `videotoolbox` `vulkan` |
     * | Linux x86_64 | `vaapi` `cuvid` `v4l2m2m` `vulkan` |
     * | Linux arm64 | `cuvid` `v4l2m2m`（**无** vaapi：字符串存在但未注册） |
     *
     * ## 为什么这里刻意不写 `vulkan`
     *
     * Vulkan 解码器需要调用方在 `avcodec_open2` 之前自己建 hwdevice 并挂上去，
     * 而我们的管线不做这一步（[AndroidMediaCodecHwSolver] 只按名字打开解码器）。
     * 列进去只会得到一次必然的 open 失败外加一次无谓的降级，所以不列。
     *
     * ## 名字不存在不是错误
     *
     * 列了但本机没注册的名字会在 `avcodec_find_decoder_by_name` 返回 null，
     * 由 [FfmpegHwBackendPolicy.advance] 立即步进到下一档。这是有意的：
     * 判据必须在**运行期**用返回码确认，而不是在编译期猜（见 [select] 的注释）。
     */
    internal val desktopHardDecoderNames: List<String> =
        listOf(
            // NVIDIA NVDEC —— Windows / Linux x86_64 / Linux arm64 都有。
            "h264_cuvid", "hevc_cuvid",
            // Intel QuickSync —— 目前只有 Windows 的预编译包带它。
            "h264_qsv", "hevc_qsv",
            // Linux VA-API —— x86_64 有；arm64 未注册（字符串残留），打了会白跑一档。
            "h264_vaapi", "hevc_vaapi",
            // macOS VideoToolbox —— 它是**解码器**，不是只能编码。
            "h264_videotoolbox", "hevc_videotoolbox",
            // Windows 的 D3D11 / DXVA2 / Media Foundation —— 不依赖 GPU 厂商，
            // 是 Windows 上 AMD 显卡唯一能用到的硬解。
            "h264_d3d11va", "hevc_d3d11va",
            "h264_dxva2", "hevc_dxva2",
            "h264_mf", "hevc_mf",
            // 树莓派 / 部分 ARM SoC 的硬件解码器。
            "h264_v4l2m2m", "hevc_v4l2m2m",
        )

    /** 平台维度：链的构成只取决于它。 */
    enum class Platform {
        ANDROID,

        /** Windows 桌面（D3D11 / DXVA2 / MF / QSV / CUVID 可用）。 */
        WINDOWS,

        /** macOS 桌面（VideoToolbox 可用）。 */
        MACOS,

        /** Linux 桌面（VA-API / CUVID / V4L2-M2M 可用）。 */
        LINUX;

        companion object {
            /**
             * 由运行环境判定平台。
             *
             * 这里刻意**不**引入 `OsInfo`：本文件是全平台可编译的纯 JVM 策略，
             * 引入它会把 `util` 模块拖进单测依赖。调用方（[FfmpegCodecScheduler]）
             * 已经知道自己在哪，把判断留在那一层即可。
             */
            fun of(isAndroid: Boolean, isWindows: Boolean, isMac: Boolean): Platform = when {
                isAndroid -> ANDROID
                isWindows -> WINDOWS
                isMac -> MACOS
                else -> LINUX
            }
        }
    }

    /**
     * 某个平台上的候选硬解解码器名，按优先级排列。
     *
     * 与 [desktopHardDecoderNames] 的区别：那张表是「全平台并集」，用于判定一个名字
     * 属于哪一类后端；这里是「这个平台真的可能有」的子集，用于排出链。
     *
     * 之所以要分平台：把本平台不存在的名字排进链，运行期会先在
     * `avcodec_find_decoder_by_name` 上失败一次再降级——功能上无害（[advance] 会
     * 兜住），但每次起播都多一次无谓尝试和一条误导性 warn 日志。
     */
    private fun hardDecoderNamesFor(platform: Platform, softwareDecoder: String): List<String> {
        val suffixes = when (platform) {
            // Android 不用这张表：它的硬解档是 `*_mediacodec`，由下方单独处理。
            Platform.ANDROID -> emptyList()
            Platform.WINDOWS -> listOf(
                "cuvid",      // NVIDIA
                "qsv",        // Intel
                "d3d11va",    // 厂商无关，AMD 显卡在 Windows 上唯一可用的硬解
                "dxva2",      // 老显卡兜底
                "mf",         // Media Foundation 兜底
            )
            Platform.MACOS -> listOf("videotoolbox")
            Platform.LINUX -> listOf(
                "cuvid",
                "vaapi",      // x86_64 有，arm64 未注册，运行期自动跳过
                "v4l2m2m",    // 树莓派 / 部分 ARM SoC
            )
        }
        return suffixes.map { "${softwareDecoder}_$it" }
    }

    /**
     * 为 [codec] 在 [platform] 上排出候选链。
     *
     * - Android 且有 mediacodec 包装器：`[<codec>_mediacodec, <codec>]`
     * - Windows → `[h264_cuvid, h264_qsv, h264_d3d11va, h264_dxva2, h264_mf, h264]`
     * - macOS → `[h264_videotoolbox, h264]`
     * - Linux → `[h264_cuvid, h264_vaapi, h264_v4l2m2m, h264]`
     * - [FfmpegCodec.UNKNOWN]：空链（调用方据此放弃而不是瞎猜）
     *
     * 顺序在运行期永不重排——硬件不会变。降级只靠 [advance] 让下标前移。
     */
    fun chain(codec: FfmpegCodec, platform: Platform): List<FfmpegHwBackend> {
        val software = codec.softwareDecoder
        if (software.isEmpty()) return emptyList()
        val softwareBackend = FfmpegHwBackend(software, FfmpegHwBackendKind.SOFTWARE)

        return when (platform) {
            Platform.ANDROID -> {
                val mc = codec.mediacodecDecoder
                if (mc != null) {
                    listOf(
                        FfmpegHwBackend(mc, FfmpegHwBackendKind.MEDIACODEC),
                        softwareBackend,
                    )
                } else {
                    listOf(softwareBackend)
                }
            }
            else -> {
                val hard = hardDecoderNamesFor(platform, software)
                    .map { FfmpegHwBackend(it, FfmpegHwBackendKind.DESKTOP) }
                hard + softwareBackend
            }
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
                kind = FfmpegHwBackendKind.fromDecoderName(decoder),
                step = -1,
                chain = chain,
            )
        }

        // 3. 硬解被全局关闭：直接落到链尾（软件那一档），而不是返回 null。
        //    返回 null 会让调用方以为「没有后端」从而整条路放弃——软解明明可用。
        if (!useHwAccel || noHardwareAccel) {
            val last = chain.lastIndex
            return FfmpegHwSelection(chain[last].decoderName, chain[last].kind, last, chain)
        }

        // 4. 取当前档位，越界收敛到最后一档（链被重置过 / 上报的 step 过期都不该炸）。
        val index = step.coerceIn(0, chain.lastIndex)
        return FfmpegHwSelection(chain[index].decoderName, chain[index].kind, index, chain)
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
        return FfmpegHwSelection(backend.decoderName, backend.kind, next, selection.chain)
    }

    /** 链的可读形式，供日志与 F3 叠加层显示，例如 `"h264_mediacodec → h264"`。 */
    fun describe(chain: List<FfmpegHwBackend>): String =
        if (chain.isEmpty()) "(none)" else chain.joinToString(" → ") { it.decoderName }
}
