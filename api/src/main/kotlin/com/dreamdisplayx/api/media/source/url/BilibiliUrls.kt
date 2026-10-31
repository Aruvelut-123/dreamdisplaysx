package com.dreamdisplayx.api.media.source.url

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.security.model.MediaHttpUrl
import java.util.*

/**
 * Recognizes and dissects Bilibili URLs (VODs, bangumi episodes / seasons, live rooms, `b23.tv` short links).
 *
 * @since 1.9.x
 */
@DreamDisplaysXUnstableApi
object BilibiliUrls {
    /** A `BVID`: `BV` followed by 10 alphanumeric characters. */
    private val BVID_RE = Regex("^BV[0-9A-Za-z]{10}$")

    /** A legacy `AVID`: `av` followed by digits. */
    private val AVID_RE = Regex("^[aA][vV](\\d+)$")

    /** A bangumi episode id: `ep` followed by digits. */
    private val EP_RE = Regex("^[eE][pP](\\d+)$")

    /** A bangumi season id: `ss` followed by digits. */
    private val SS_RE = Regex("^[sS][sS](\\d+)$")

    /** Route names that may follow an optional locale prefix. */
    private val ROUTES = setOf("video", "bangumi")

    /** A Bilibili locale path prefix such as `zh-Hans`, `zh-CN` or `en`. */
    private val LOCALE_PREFIX_RE = Regex("^[a-z]{2,3}(?:[-_][a-z]{2,4})?$", RegexOption.IGNORE_CASE)

    /**
     * Drops a leading locale segment (`/zh-Hans/video/...`) so route parsing always starts at the
     * real route. Only strips when a known route follows, so `/video/...` keeps its first segment.
     */
    private fun routeSegments(segments: List<String>): List<String> {
        val first = segments.firstOrNull() ?: return segments
        if (!LOCALE_PREFIX_RE.matches(first)) return segments
        val rest = segments.drop(1)
        return if (rest.firstOrNull()?.lowercase(Locale.ROOT) in ROUTES) rest else segments
    }

    /** True when [url] points at a BIlibili VOD, live room, or short link. */
    fun isBilibili(url: String): Boolean = parse(url) != null

    /**
     * Parses a bare Bilibili id (`BV...`, `av...`, `ep...`, `ss...`) pasted without a URL, or
     * returns null when [id] is not one. The returned source carries a canonical `bilibili.com`
     * URL so downstream resolvers always have something to fetch.
     */
    fun parseBareId(id: String): MediaSource.Bilibili? {
        val trimmed = id.trim()
        if (BVID_RE.matches(trimmed)) {
            return MediaSource.Bilibili(
                url = "https://www.bilibili.com/video/$trimmed",
                bvid = trimmed,
            )
        }
        AVID_RE.matchEntire(trimmed)?.let { m ->
            val avid = m.groupValues[1].toLongOrNull() ?: return null
            return MediaSource.Bilibili(
                url = "https://www.bilibili.com/video/$trimmed",
                avid = avid,
            )
        }
        EP_RE.matchEntire(trimmed)?.let { m ->
            val epId = m.groupValues[1].toLongOrNull() ?: return null
            return MediaSource.Bilibili(
                url = "https://www.bilibili.com/bangumi/play/$trimmed",
                epId = epId,
            )
        }
        SS_RE.matchEntire(trimmed)?.let { m ->
            val seasonId = m.groupValues[1].toLongOrNull() ?: return null
            return MediaSource.Bilibili(
                url = "https://www.bilibili.com/bangumi/play/$trimmed",
                seasonId = seasonId,
            )
        }
        return null
    }

    /**
     * Parses [url] into a [com.dreamdisplayx.api.media.source.MediaSource.Bilibili], or null if not recognizable. A `b23.tv` short link
     * is recognized but left unresolved (no bvid / avid / roomId) — following its redirect needs a
     * network call, which belongs in [com.dreamdisplayx.media.source.bilibili.BilibiliApi], not here.
     */
    fun parse(url: String): MediaSource.Bilibili? {
        parseBareId(url)?.let { return it }
        val parsed = MediaHttpUrl.parse(url) ?: MediaHttpUrl.parse("https://${url.trim()}") ?: return null
        val host = parsed.uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.removePrefix("m.") ?: return null

        val normalized = parsed.value
        val segments = parsed.uri.path?.split('/')?.filter { it.isNotBlank() } ?: emptyList()

        if (host == "b23.tv") {
            if (segments.isEmpty()) return null
            return MediaSource.Bilibili(url = normalized)
        }

        if (host == "live.bilibili.com") {
            // Mobile share links put a route in front of the room id (`/h5/<id>`, `/blanc/<id>`), so the
            // room id is the first purely numeric segment, not necessarily the first one.
            val roomId = segments.firstNotNullOfOrNull { it.toLongOrNull() } ?: return null
            return MediaSource.Bilibili(url = normalized, roomId = roomId)
        }

        if (host != "bilibili.com") return null
        val route = routeSegments(segments)

        // Bangumi / movie: /bangumi/play/ep<id> or /bangumi/play/ss<id>.
        if (route.getOrNull(0) == "bangumi" && route.getOrNull(1) == "play") {
            val id = route.getOrNull(2) ?: return null
            EP_RE.matchEntire(id)?.let { m ->
                val epId = m.groupValues[1].toLongOrNull() ?: return null
                return MediaSource.Bilibili(url = normalized, epId = epId)
            }
            SS_RE.matchEntire(id)?.let { m ->
                val seasonId = m.groupValues[1].toLongOrNull() ?: return null
                return MediaSource.Bilibili(url = normalized, seasonId = seasonId)
            }
            return null
        }

        if (route.getOrNull(0) != "video") return null
        val id = route.getOrNull(1) ?: return null
        val part = partQueryParam(parsed.uri.rawQuery)?.toIntOrNull()

        if (BVID_RE.matches(id)) return MediaSource.Bilibili(url = normalized, bvid = id, part = part)
        AVID_RE.matchEntire(id)?.let { m ->
            val avid = m.groupValues[1].toLongOrNull() ?: return null
            return MediaSource.Bilibili(url = normalized, avid = avid, part = part)
        }
        return null
    }

    /** Returns the value of the `p` (part index) query parameter in [rawQuery], or null when absent. */
    private fun partQueryParam(rawQuery: String?): String? =
        rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) null else pair.substring(0, idx).takeIf { it == "p" }?.let { pair.substring(idx + 1) }
        }
}
