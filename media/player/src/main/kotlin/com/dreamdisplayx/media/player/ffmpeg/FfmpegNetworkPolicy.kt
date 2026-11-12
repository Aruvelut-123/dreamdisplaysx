package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.api.media.source.model.CustomMediaKind

/**
 * 打开网络输入时该给 `avformat_open_input` 配什么。
 *
 * ## 为什么这需要是一个独立可测的对象
 *
 * FFmpeg 打开 **自适应流清单**（HLS `.m3u8` / DASH `.mpd`）和打开一个直接的文件走的是完全
 * 不同的两段协议：清单本身是一个文本索引，读完之后 demuxer 还要**再打开**它列出的分片
 * 与子清单，而这些嵌套打开要经过协议白名单。
 *
 * 白名单默认是 `file,crypto,data` —— 只有本地协议。于是：
 *
 * - 直链 `.mp4`：`avformat_open_input` 一次打开，白名单不参与，**能播**；
 * - HLS/DASH 清单：demuxer 打开第一个 `https://.../seg1.ts` 时被白名单拦下，
 *   `open()` 报 `Protocol not on whitelist 'file,crypto,data'` 并失败。
 *
 * 这就是「网络流」缺口的实质：不是 FFmpeg 不支持协议，而是调用方没告诉它允许嵌套打开。
 * `hls` demuxer 是纯文本解析，任何构建都有；`dash` 是 XML，取决于该构建是否带 libxml2
 * （`native/ffmpeg/collect.sh` 只断言解码器，从不检查 demuxer，所以这里不做猜测）。
 * 无论哪一种，白名单都必须先放行，否则连「能不能解析」都轮不到。
 *
 * ## 为什么不无条件放行全部协议
 *
 * 白名单的作用是限制「一个被解析的输入能让 FFmpeg 去打开什么」。一个直链文件没有嵌套
 * 打开，就不该放宽任何东西；只有确认是清单时才需要。判据放在这里而不是散在调用点，
 * 是因为它必须能被单测钉住（`FfmpegNetworkPolicyTest`）——配错的症状只是「某些链接放不出
 * 画面」，不会有任何异常指向真正的原因。
 *
 * 本对象**不引用** `android.*` / `org.bytedeco.*` / JNA，也不做网络请求，因此可以在任何
 * 平台上运行单测。
 */
internal object FfmpegNetworkPolicy {

    /**
     * 清单输入要放行的协议集合。
     *
     * 只有这几种是真正需要的：
     * - `http` / `https`：分片与子清单本身；
     * - `tls`：`https` 的底层传输，FFmpeg 把它作为独立协议打开；
     * - `tcp`：`tls` 的底层传输。
     *
     * 刻意**不含** `file`/`crypto`/`data`/`pipe`：清单没有任何合法理由让播放器去读本地文件，
     * 而 `file` 恰恰是 SSRF 与本地信息泄露最常用的一条。`https` 的默认白名单里有它们，
     * 那是因为它默认只打算打开本地输入；我们是在明确放宽，所以只放行需要的那几个。
     */
    const val MANIFEST_PROTOCOLS: String = "http,https,tls,tcp"

    /**
     * 判断 [url] 指向的是不是自适应流清单。
     *
     * 依据是路径扩展名，与 [com.dreamdisplayx.api.media.source.url.CustomMediaUrls] 的分类保持一致：
     * 那一个是在 URL 层面做分类（`DirectStreamResolver` 用它决定要不要去解析清单），
     * 这里是同一个事实的第二个消费者，两处必须对同一组扩展名给出同一个答案。
     *
     * 之所以不看 `Content-Type`：这个判断要在**打开之前**做（它决定 open 时配什么选项），
     * 而此时还没有响应头可用。猜错方向的代价不对称——对直链多加白名单毫无效果，
     * 对清单漏加则直接打不开——所以宁可在拿不准时按清单处理（见 [optionsFor] 的说明）。
     *
     * @param kind 解析器已经判定的类型；非 `null` 时优先采用它
     */
    fun isManifest(url: String, kind: CustomMediaKind? = null): Boolean = when (kind) {
        CustomMediaKind.HLS, CustomMediaKind.DASH -> true
        CustomMediaKind.PROGRESSIVE, CustomMediaKind.AUDIO_ONLY -> false
        CustomMediaKind.UNKNOWN, null -> hasManifestExtension(url)
    }

    /**
     * [url] 是否需要清单协议白名单。
     *
     * 与 [isManifest] 分开是因为调用点手里往往只有 URL（播放器拿到的已经是解析后的
     * 单个流 URL），而这里要回答的是「open 时配什么」。
     */
    fun needsManifestProtocols(url: String): Boolean = hasManifestExtension(url)

    /**
     * 打开 [url] 时要设置的字典项。
     *
     * 返回的顺序即设置顺序，便于测试断言；值里不含任何用户输入以外的内容。
     *
     * @param referer CDN 防盗链用的 Referer；空串与 `null` 等价
     * @param userAgent 自定义 UA；空串与 `null` 等价
     */
    fun optionsFor(
        url: String,
        referer: String? = null,
        userAgent: String? = null,
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(3)
        referer?.takeIf { it.isNotBlank() }?.let { out += "headers" to "Referer: $it\r\n" }
        userAgent?.takeIf { it.isNotBlank() }?.let { out += "user_agent" to it }
        if (needsManifestProtocols(url)) {
            out += "protocol_whitelist" to MANIFEST_PROTOCOLS
        }
        return out
    }

    /**
     * 低延迟探测参数。
     *
     * 与白名单分开：这两个选项对所有输入都一样，而白名单只对清单有意义。放在一起会让
     * 「这个 URL 是不是清单」和「探测多久」两件事纠缠在同一个断言里。
     *
     * `analyzeduration` 单位是微秒，3 秒是这里能接受的上限：再高会明显拖慢起播，
     * 再低会让部分 Bilibili DASH 源在 `find_stream_info` 阶段拿不到分辨率。
     */
    val PROBE_OPTIONS: List<Pair<String, String>> = listOf(
        "analyzeduration" to "3000000",
        "probesize" to "1000000",
    )

    /**
     * 从 URL 的路径部分取出小写扩展名；没有扩展名返回空串。
     *
     * 只看路径，不看查询串：签名 URL 常把 `?sign=...` 附在末尾，而 CDN 也可能把扩展名
     * 放进查询参数里（`?format=m3u8`）。前者必须忽略，后者**故意**不识别——一个
     * `?format=` 参数完全可能是别的东西，按它放宽协议白名单等于用对方的输入决定我们的
     * 安全边界。真正的清单如果 URL 里看不出来，由解析器在 `resolve()` 阶段按响应体判定，
     * 那一步拿到的 `MediaStream` 类型会作为 [kind] 传进来。
     */
    private fun hasManifestExtension(url: String): Boolean {
        val path = pathOf(url)
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot == path.length - 1) return false
        return when (path.substring(dot + 1).lowercase()) {
            "m3u8", "m3u", "mpd" -> true
            else -> false
        }
    }

    /** [url] 的路径部分，不含查询串与片段；无法解析时退化为整个字符串。 */
    private fun pathOf(url: String): String {
        val trimmed = url.trim()
        val noFragment = trimmed.substringBefore('#')
        val noQuery = noFragment.substringBefore('?')
        // 去掉 scheme 与 authority：netloc 里可能出现 `.`（主机名），不能参与扩展名判断。
        val schemeEnd = noQuery.indexOf("://")
        if (schemeEnd < 0) return noQuery
        val pathStart = noQuery.indexOf('/', schemeEnd + 3)
        return if (pathStart < 0) "" else noQuery.substring(pathStart)
    }
}
