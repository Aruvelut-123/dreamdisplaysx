package com.dreamdisplayx.media.player.cdn

/**
 * Known Bilibili CDN mirror hosts. Based on [PiliPlus CDNService](https://github.com/piliplus/piliplus)
 * and [BiliRoaming](https://github.com/yujincheng08/BiliRoaming).
 *
 * When a Bilibili video URL uses one of the `upos-*` / `bilivideo.com` mirror hosts, the stream
 * resolver can replace the host with a selected mirror — the API returns the same content from any
 * of them.  This lets the user (or an auto-probe) pick the fastest edge for their location.
 *
 * Each entry's [host] is the FQDN to substitute into the URI.  Entries without a [host] are special
 * selectors: `BASE_URL` uses the API's original URL as-is, and `BACKUP_URL` uses the first available
 * backup URL from the API response.
 */
enum class BilibiliCdnMirror(
    /** Human-readable label shown in the UI. */
    val label: String,
    /** The hostname to replace into the mirror URL, or null for special selectors. */
    val host: String? = null,
) {
    BASE_URL("基础URL（不推荐）"),
    BACKUP_URL("备用URL"),
    ALI("ali（阿里云）", "upos-sz-mirrorali.bilivideo.com"),
    ALIB("alib（阿里云）", "upos-sz-mirroralib.bilivideo.com"),
    ALIO1("alio1（阿里云）", "upos-sz-mirroralio1.bilivideo.com"),
    COS("cos（腾讯云）", "upos-sz-mirrorcos.bilivideo.com"),
    COSB("cosb（腾讯云，VOD加速类型）", "upos-sz-mirrorcosb.bilivideo.com"),
    COSO1("coso1（腾讯云）", "upos-sz-mirrorcoso1.bilivideo.com"),
    HW("hw（华为云，融合CDN）", "upos-sz-mirrorhw.bilivideo.com"),
    HWB("hwb（华为云，融合CDN）", "upos-sz-mirrorhwb.bilivideo.com"),
    HWO1("hwo1（华为云，融合CDN）", "upos-sz-mirrorhwo1.bilivideo.com"),
    HW_08C("08c（华为云，融合CDN）", "upos-sz-mirror08c.bilivideo.com"),
    HW_08H("08h（华为云，融合CDN）", "upos-sz-mirror08h.bilivideo.com"),
    HW_08CT("08ct（华为云，融合CDN）", "upos-sz-mirror08ct.bilivideo.com"),
    TF_HW("tf_hw（华为云）", "upos-tf-all-hw.bilivideo.com"),
    TF_TX("tf_tx（腾讯云）", "upos-tf-all-tx.bilivideo.com"),
    AKAMAI("akamai（Akamai海外）", "upos-hz-mirrorakam.akamaized.net"),
    ALIOV("aliov（阿里云海外）", "upos-sz-mirroraliov.bilivideo.com"),
    COSOV("cosov（腾讯云海外）", "upos-sz-mirrorcosov.bilivideo.com"),
    HWOV("hwov（华为云海外）", "upos-sz-mirrorhwov.bilivideo.com"),
    HK_Bcache("hk_bcache（Bilibili海外）", "cn-hk-eq-bcache-01.bilivideo.com"),
    ;

    companion object {
        /**
         * Maps config values (mirror host, friendly name, or special value) to translatable
         * label keys for the config UI dropdown.  The keys are localised via
         * `dreamdisplayx.config.cdn.<label>`.
         */
        val CDN_LABELS: Map<String, String> = entries.flatMap { e ->
            when (e) {
                BASE_URL -> listOf("BASE_URL" to "base_url")
                BACKUP_URL -> listOf("BACKUP_URL" to "backup_url")
                else -> {
                    val host = e.host
                    val label = e.name.lowercase()
                    if (host != null) listOf(host to label, e.name to label) else emptyList()
                }
            }
        }.distinctBy { it.first }.toMap() + ("auto" to "auto")

        /** All config values that can be stored in `bilibili-cdn-mirror` setting: friendly names and hosts. */
        val CONFIG_VALUES: List<String> = buildList {
            add("auto")
            add("BASE_URL")
            add("BACKUP_URL")
            for (e in entries) {
                if (e.host != null) {
                    add(e.name)
                    add(e.host)
                }
            }
        }
        /**
         * Regex for Bilibili upos mirror URLs whose host can be replaced.
         * Matches `upos-*-*` and `upos-tf-*` and `proxy-tf-*` on bilivideo.com / akamaized.net,
         * with an `/upgcxcode/` path.
         */
        private val MIRROR_REGEX = Regex(
            """^https?://(?:upos-\w+-(?!302)\w+|(?:upos|proxy)-tf-[^/]+)\.(?:bilivideo|akamaized)\.(?:com|net)/upgcxcode""",
        )

        private val MCDN_TF_REGEX = Regex(
            """^https?://(?:(?:\d{1,3}\.){3}\d{1,3}|[^/]+\.mcdn\.bilivideo\.(?:com|cn|net))(?::\d{1,5})?/v\d/resource""",
        )

        /**
         * Finds the first Bilibili mirror URL in [urls] and returns it with its host replaced by
         * [mirrorHost].  If [mirrorHost] is null or the URL is not a mirror URL, the original URL is
         * returned unchanged.
         */
        fun replaceHost(urls: Iterable<String>, mirrorHost: String?): String? {
            var mcdnTf: String? = null
            var mcdnUpgcxcode: String? = null
            var last: String? = null

            for (url in urls) {
                last = url
                if (MIRROR_REGEX.containsMatchIn(url)) {
                    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: continue
                    if (uri.query?.contains("os=mcdn") == true) {
                        mcdnUpgcxcode = url
                        continue
                    }
                    if (mirrorHost == null) return url
                    return uri.run {
                        java.net.URI(scheme, mirrorHost, path, query, fragment).toString()
                    }
                }
                if (MCDN_TF_REGEX.containsMatchIn(url)) {
                    mcdnTf = url
                    continue
                }
                if (url.contains("/upgcxcode/")) {
                    mcdnUpgcxcode = url
                    continue
                }
            }

            // Fallback: if we found a upgcxcode URL, replace its host
            if (mcdnUpgcxcode != null && mirrorHost != null) {
                val uri = runCatching { java.net.URI(mcdnUpgcxcode) }.getOrNull()
                if (uri != null) {
                    return java.net.URI(
                        uri.scheme, mirrorHost, uri.path, uri.query, uri.fragment
                    ).toString()
                }
            }

            // Last resort: proxy-tf redirect
            if (mcdnTf != null) {
                return "https://proxy-tf-all-ws.bilivideo.com?url=$mcdnTf"
            }

            return last
        }

        /**
         * Returns the same [url] with its host replaced by [mirrorHost], if the URL is a Bilibili
         * mirror URL.  Otherwise returns [url] unchanged.
         */
        fun replaceHostInUrl(url: String, mirrorHost: String): String {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
            if (!MIRROR_REGEX.containsMatchIn(url)) return url
            return java.net.URI(uri.scheme, mirrorHost, uri.path, uri.query, uri.fragment).toString()
        }
    }
}