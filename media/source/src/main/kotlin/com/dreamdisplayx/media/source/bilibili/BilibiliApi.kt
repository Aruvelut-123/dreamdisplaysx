package com.dreamdisplayx.media.source.bilibili

import com.dreamdisplayx.api.media.source.url.BilibiliUrls
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.media.stream.model.MediaStream
import com.dreamdisplayx.api.media.stream.model.MediaStreamType
import com.dreamdisplayx.media.source.bilibili.danmaku.BiliDmSegParser
import com.dreamdisplayx.media.source.bilibili.danmaku.DanmakuEntry
import com.dreamdisplayx.media.source.platform.PlatformVideoMetadata
import com.dreamdisplayx.util.*
import com.dreamdisplayx.util.json.DreamJson
import com.dreamdisplayx.util.net.DreamHttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A resolved BIlibili VOD or live room: playable [streams] and [metadata]. */
data class BilibiliPlayback(
    val streams: List<MediaStream>,
    val metadata: PlatformVideoMetadata,
    val isSeekable: Boolean,
    /**
     * True when the upload is a 360/VR panorama (equirectangular), which this mod cannot project.
     * Resolvers turn this into a clear "not playable here" error instead of painting a warped
     * sphere map onto a flat wall.
     */
    val panoramic: Boolean = false,
)

/** One search-result card from a Bilibili keyword search (video / bangumi / movie). */
data class BilibiliSearchItem(
    val bvid: String? = null,
    val title: String,
    val uploader: String? = null,
    val thumbnailUrl: String? = null,
    val durationSec: Long? = null,
    val viewCount: Long? = null,
    /** Bangumi / movie episode id (market `bangumi/play/ep<id>`), null for plain videos. */
    val epId: Long? = null,
    /** Bangumi / movie season id (market `bangumi/play/ss<id>`), null for plain videos. */
    val seasonId: Long? = null,
    /** Media category returned by the search API: `video` / `media_bangumi` / `media_ft` / `pgc`. */
    val mediaType: String = "video",
    /** Access mode returned by the API: 0=free, 1=VIP, 2=pay-per-view, etc. */
    val mediaMode: Int? = null,
    /** Display label for the access mode (e.g. "VIP", "付费", "独家"), or null. */
    val accessLabel: String? = null,
)

/** Minimal VOD identity needed to fetch Bilibili danmaku segments: bvid / aid / cid / part. */
data class BiliDanmakuVideo(
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val part: Int,
)

/**
 * Resolves BIlibili VODs and live rooms through BIlibili's public web API — the same JSON the
 * website uses, gated behind WBI-signed `playurl` requests (no API key or auth required).
 */
object BilibiliApi {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/BilibiliApi")

    /** Lowercase hex digits, indexed by nibble — avoids `"%02x".format` per byte in [md5]. */
    private val HEX_CHARS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )

    /**
     * Bilibili login cookie string set by the client after a platform login (see `BilibiliAuth`),
     * e.g. `SESSDATA=...; bili_jct=...`. Enables VIP / higher-quality playback; empty when not logged in.
     */
    @Volatile
    var cookie: String = ""

    /**
     * The anonymous device fingerprint Bilibili hands out (`buvid3` / `buvid4`) and expects back on
     * every subsequent request.
     *
     * Without it the API treats the caller as a fresh, unidentifiable bot: `x/web-interface/view`
     * then answers `HTTP 412 {"code":-412,"message":"request was banned"}` while WBI-signed
     * endpoints like search still work, which is exactly the split a tester reported (search showed
     * thumbnails, opening any of them failed). Supplying the cookie fixes it.
     */
    @Volatile
    private var deviceCookies: String = ""

    /** Serialises the one-time fingerprint fetch so a burst of resolves cannot fire it N times. */
    private val deviceCookieLock = Any()

    /**
     * Fetches `buvid3` / `buvid4` once, at most once per [DEVICE_COOKIE_RETRY_MS] after a 412.
     *
     * Returns the cookie fragment to append, or an empty string when the fetch failed — callers must
     * keep working without it rather than failing the whole resolution.
     */
    private fun ensureDeviceCookies(force: Boolean = false): String {
        deviceCookies.takeIf { it.isNotEmpty() && !force }?.let { return it }
        synchronized(deviceCookieLock) {
            if (!force && deviceCookies.isNotEmpty()) return deviceCookies
            val now = Clock.System.now()
            if (force && now - lastDeviceCookieAttempt < DEVICE_COOKIE_RETRY_MS) {
                // A WAF ban is sticky for a while; hammering the endpoint keeps it alive. Keep using
                // whatever we already have and let the caller fail fast instead.
                return deviceCookies
            }
            lastDeviceCookieAttempt = now
            val fetched = runCatching {
                val body = DreamHttpClient.readText(
                    "https://api.bilibili.com/x/frontend/finger/spi",
                    DreamHttpClient.RequestOptions(
                        headers = DreamHttpClient.headersOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "application/json",
                            "Referer" to "https://www.bilibili.com",
                        ),
                        connectTimeoutMs = 8_000,
                        readTimeoutMs = 8_000,
                    ),
                )
                val data = DreamJson.compact.parseToJsonElement(body).asJsonObjectOrNull()?.obj("data")
                listOfNotNull(
                    data?.optString("b_3")?.takeIf { it.isNotBlank() }?.let { "buvid3=$it" },
                    data?.optString("b_4")?.takeIf { it.isNotBlank() }?.let { "buvid4=$it" },
                ).joinToString("; ")
            }.getOrElse {
                logger.warn("Bilibili device-fingerprint fetch failed: {}.", it.toString())
                ""
            }
            if (fetched.isNotEmpty()) deviceCookies = fetched
            return deviceCookies
        }
    }

    /** When the last `buvid` attempt happened, so a WAF ban is not refreshed on every resolve. */
    @Volatile
    private var lastDeviceCookieAttempt: Instant = Instant.fromEpochMilliseconds(0)

    /** BIlibili's API rejects requests without a browser-shaped `Referer` / `User-Agent`; the login cookie and the anonymous device fingerprint are added when present. */
    private fun headers(): Map<String, List<String>> =
        DreamHttpClient.headersOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "Referer" to "https://www.bilibili.com",
            "Cookie" to cookiesForRequest(),
        )

    /** Joins the login cookie and the anonymous device fingerprint into one `Cookie` header. */
    private fun cookiesForRequest(): String =
        listOf(ensureDeviceCookies(), cookie.trim())
            .filter { it.isNotEmpty() }
            .joinToString("; ")

    /** `b23.tv` short links get followed for at most this many hops before giving up. */
    private const val MAX_REDIRECT_HOPS = 5

    /** Browser-shaped UA; Bilibili rejects unknown clients outright. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /**
     * How long a new `buvid` is withheld after a WAF ban. Re-requesting one on every failed resolve
     * kept a 412 alive (a tester's log showed ~30 identical `view` requests inside one second); the
     * ban needs to expire before a fresh fingerprint helps.
     */
    private val DEVICE_COOKIE_RETRY_MS = 5.minutes

    /** How long Bilibili API calls are parked after a 412, so a short throttle is not extended. */
    private val WAF_COOLDOWN = 60.seconds

    /** Bilibili's WAF rejection code, delivered either as a 412 status or inside an HTTP-200 body. */
    private const val WAF_BAN_CODE = -412


    /**
     * Bilibili's uppermost `qn` (quality number): 127 is the 8K / 4320p rung.
     *
     * `qn` is a *request* for one preferred rendition, NOT a menu. Asking for 1080p (80) made the
     * server answer with only the renditions near that rung, which is why most videos exposed a
     * single quality in the display menu while a handful (whose CDN payload happened to carry extra
     * tracks) offered several. Requesting the top rung makes the server return every rendition the
     * account is entitled to; entitlement is still enforced server-side and the client clamps the
     * list in `DisplayScreen.getAvailableQualities()`.
     */
    private const val QN_MAX = "127"


    /**
     * `fnval` bitfield for the full DASH ladder: DASH (16) + HDR (64) + 4K (128) + Dolby audio (256)
     * + Dolby vision (512) + 8K (1024) + AV1 (2048) = 4048. Withholding any of these bits silently
     * removes those renditions from the response, so the value is spelled out here instead of being
     * scattered as a magic number at each call site.
     */
    private const val FNVAL_DASH_ALL = "4048"

    /**
     * `fnval` for the PGC (bangumi / movie) endpoint: the full ladder above plus the PGC-only
     * 8192 bit = 12240. The PGC player rejects the plain video bitfield and answers with no streams.
     */
    private const val FNVAL_DASH_ALL_PGC = "12240"


    /** Fixed permutation table BIlibili uses to derive the 32-char WBI mixin key. Publicly documented, stable! */
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12,
        38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62,
        11, 36, 20, 34, 44, 52,
    )

    /** The `img_key` / `sub_key` WBI signing keys, cached since they rotate roughly once a day. */
    private data class WbiKeys(val imgKey: String, val subKey: String, val fetchedAt: Instant)

    private var wbiCache: WbiKeys? = null
    private val WBI_TTL = 20.hours

    /** Resolves [source] (a VOD, live room, bangumi episode/season, or unresolved short link), or null when nothing is playable. */
    fun resolve(source: MediaSource.Bilibili): BilibiliPlayback? = when {
        source.bvid != null || source.avid != null -> resolveVod(source.bvid, source.avid, source.part ?: 1)
        source.epId != null || source.seasonId != null -> resolveBangumi(source.epId, source.seasonId)
        source.roomId != null -> resolveLive(source.roomId!!)
        else -> resolveShortlink(source.url)?.let { resolve(it) }
    }

    /** Metadata-only lookup for the cache. */
    fun metadata(source: MediaSource.Bilibili): PlatformVideoMetadata? = resolve(source)?.metadata

    /**
     * Resolves the VOD identity (bvid / aid / cid / part) needed to fetch danmaku for [source]
     * via a lightweight `/x/web-interface/view` call, or null when [source] is not a resolvable VOD.
     */
    fun danmakuVideo(source: MediaSource.Bilibili): BiliDanmakuVideo? {
        if (source.roomId != null || source.epId != null || source.seasonId != null) return null
        val viewParams = buildMap {
            source.bvid?.let { put("bvid", it) }
            source.avid?.let { put("aid", it.toString()) }
        }
        if (viewParams.isEmpty()) return null
        val data = fetchView("https://api.bilibili.com/x/web-interface/view?${plainQuery(viewParams)}")?.obj("data")
            ?: return null
        val pages = data.array("pages")?.mapNotNull { it.asJsonObjectOrNull() }
        val part = source.part ?: 1
        val page = pages?.firstOrNull { it.optInt("page") == part } ?: pages?.firstOrNull()
        val cid = page?.optLong("cid") ?: data.optLong("cid") ?: return null
        val aid = data.optLong("aid") ?: source.avid ?: return null
        return BiliDanmakuVideo(
            bvid = data.optString("bvid") ?: source.bvid ?: return null,
            aid = aid,
            cid = cid,
            part = page?.optInt("page") ?: part,
        )
    }

    /**
     * Fetches one 6-minute VOD danmaku segment (1-based) for [video]. Tries the WBI-signed protobuf
     * `seg.so` endpoint first, then the plain protobuf endpoint, then the legacy XML `list.so`.
     * Returns an empty list when nothing could be fetched or parsed.
     */
    fun fetchDanmakuSegment(video: BiliDanmakuVideo, segmentIndex: Int): List<DanmakuEntry> {
        val segment = maxOf(1, segmentIndex)
        val referer = "https://www.bilibili.com/video/${video.bvid}"
        val params = mapOf(
            "type" to "1",
            "oid" to video.cid.toString(),
            "pid" to video.aid.toString(),
            "segment_index" to segment.toString(),
        )

        val wbi = getBytes("https://api.bilibili.com/x/v2/dm/wbi/web/seg.so?${signedQuery(params)}", referer)
        if (wbi != null) {
            val entries = BiliDmSegParser.parseProtobuf(wbi)
            if (entries.isNotEmpty()) return entries
        }

        val plain = getBytes("https://api.bilibili.com/x/v2/dm/web/seg.so?${plainQuery(params)}", referer)
        if (plain != null) {
            val entries = BiliDmSegParser.parseProtobuf(plain)
            if (entries.isNotEmpty()) return entries
        }

        val xml = getBytes("https://api.bilibili.com/x/v1/dm/list.so?oid=${video.cid}", referer)
        if (xml != null) {
            val entries = BiliDmSegParser.parseXml(xml)
            if (entries.isNotEmpty()) return entries
        }
        return emptyList()
    }

    /** Keyword video search, WBI-signed like `playurl` — used to mix BIlibili results into the free-text suggestions list. */
    fun searchVideos(keyword: String, page: Int = 1): List<BilibiliSearchItem> {
        val params = mapOf("search_type" to "video", "keyword" to keyword, "page" to page.toString())
        val root = getJson("https://api.bilibili.com/x/web-interface/wbi/search/type?${signedQuery(params)}")
        val results = root?.obj("data")?.array("result")?.mapNotNull { it.asJsonObjectOrNull() } ?: return emptyList()
        return results.mapNotNull { item ->
            val bvid = item.optString("bvid") ?: return@mapNotNull null
            BilibiliSearchItem(
                bvid = bvid,
                title = stripHighlightTags(item.optString("title").orEmpty()),
                uploader = item.optString("author"), // real uploader name from search result
                thumbnailUrl = normalizeThumbnailUrl(item.optString("pic")),
                durationSec = parseSearchDuration(item.optString("duration")),
                viewCount = item.optLong("play"),
                mediaType = "video",
            )
        }
    }

    /**
     * Keyword bangumi (anime / series) search; results are keyed by `ep_id` / `season_id` instead of
     * `bvid`, so they render as cards whose tap target is a `bangumi/play/...` URL.
     */
    fun searchBangumi(keyword: String, page: Int = 1): List<BilibiliSearchItem> {
        val params = mutableMapOf(
            "search_type" to "media_bangumi",
            "keyword" to keyword,
            "page" to page.toString(),
            "page_size" to "20",
            "platform" to "pc",
            "web_location" to "1430654",
        )
        val root = getJson("https://api.bilibili.com/x/web-interface/wbi/search/type?${signedQuery(params)}")
        val results = root?.obj("data")?.array("result")?.mapNotNull { it.asJsonObjectOrNull() } ?: return emptyList()
        return results.mapNotNull { item ->
            val seasonId = item.optLong("season_id") ?: return@mapNotNull null
            val mode = item.optInt("media_mode")
            val label = accessLabelFor(item)
            // Skip unreleased / trailer items that cannot be played
            if (label == "unreleased") return@mapNotNull null
            BilibiliSearchItem(
                title = seriesTitle(item),
                uploader = null,
                thumbnailUrl = normalizeThumbnailUrl(item.optString("cover")),
                durationSec = null,
                viewCount = item.optLong("play"),
                epId = item.optLong("ep_id"),
                seasonId = seasonId,
                mediaType = "media_bangumi",
                mediaMode = mode,
                accessLabel = label,
            )
        }
    }

    /** Keyword media (movie / TV / documentary) search via `media_ft`. */
    fun searchMedia(keyword: String, page: Int = 1): List<BilibiliSearchItem> {
        val params = mutableMapOf(
            "search_type" to "media_ft",
            "keyword" to keyword,
            "page" to page.toString(),
            "page_size" to "20",
            "platform" to "pc",
            "web_location" to "1430654",
        )
        val root = getJson("https://api.bilibili.com/x/web-interface/wbi/search/type?${signedQuery(params)}")
        val results = root?.obj("data")?.array("result")?.mapNotNull { it.asJsonObjectOrNull() } ?: return emptyList()
        return results.mapNotNull { item ->
            val seasonId = item.optLong("season_id") ?: return@mapNotNull null
            val mode = item.optInt("media_mode")
            val label = accessLabelFor(item)
            // Skip unreleased / trailer items that cannot be played
            if (label == "unreleased") return@mapNotNull null
            BilibiliSearchItem(
                title = seriesTitle(item),
                uploader = null,
                thumbnailUrl = normalizeThumbnailUrl(item.optString("cover")),
                durationSec = null,
                viewCount = item.optLong("play"),
                epId = item.optLong("ep_id"),
                seasonId = seasonId,
                mediaType = "pgc",
                mediaMode = mode,
                accessLabel = label,
            )
        }
    }

    /** Strips the `<em class="keyword">...</em>` highlight markup BIlibili's search API wraps matches in. */
    private val EM_TAG_RE = Regex("</?em[^>]*>")

    private fun stripHighlightTags(title: String): String = title.replace(EM_TAG_RE, "")

    /** Any Han (Chinese-script) character, used to pick the localised title below. */
    private val HAN_CHAR_RE = Regex("[一-鿿]")

    /**
     * Chooses the localised series/movie title for a bangumi or movie search result. Bilibili returns
     * two fields that may carry different languages — `title` (the search-highlighted name) and
     * `org_title` (the original/alternate name). The field holding the query match is often the
     * English name (e.g. searching "WALL-E" puts "WALL·E" in `title` while `org_title` is
     * "机器人总动员"), so prefer whichever candidate contains Chinese characters to keep titles in the
     * user's language.
     */
    private fun seriesTitle(item: JsonObject): String {
        val candidates = listOf(
            item.optString("title"),
            item.optString("org_title"),
            item.optString("media_name"),
        ).mapNotNull { it?.let(::stripHighlightTags)?.takeIf { s -> s.isNotBlank() } }
        return candidates.firstOrNull { HAN_CHAR_RE.containsMatchIn(it) }
            ?: candidates.firstOrNull()
            ?: ""
    }

    /**
     * Derives a user-facing access marker for a bangumi / movie search result from the API's `badges`
     * / `display_info` arrays (each entry carries a `text` like "大会员" / "会员特价"). Only returns
     * a marker for genuinely restricted content:
     *  - "vip"  for pink "大会员" / "VIP" badges (watchable with a Big Membership),
     *  - "paid" for yellow pay-per-view badges ("付费" / "会员特价" / "单片购买"),
     *  - "unreleased" for items that are not yet available ("预告" / "未上映" / "敬请期待"),
     *  - null   otherwise (free, or other badge kinds like "独家" which aren't paywalled here).
     * `media_mode` is deliberately not used — Bilibili sets it to 2 for both VIP and pay-per-view.
     */
    private fun accessLabelFor(item: JsonObject): String? {
        val badgeTexts = buildList {
            item.array("badges")?.forEach { e -> (e as? JsonObject)?.optString("text")?.let { add(it) } }
            item.array("display_info")?.forEach { e -> (e as? JsonObject)?.optString("text")?.let { add(it) } }
        }
        val joined = badgeTexts.joinToString(" ")
        return when {
            // Unreleased / trailer items cannot be played at all — filter out via accessLabel
            joined.contains("预告") || joined.contains("未上映") || joined.contains("敬请期待")
                || joined.contains("即将上映") || joined.contains("尚未上映") -> "unreleased"
            joined.contains("大会员") || joined.contains("VIP") -> "vip"
            joined.contains("付费") || joined.contains("特价") || joined.contains("购买") || joined.contains("单片") -> "paid"
            else -> null
        }
    }

    /** BIlibili's search API returns protocol-relative thumbnail URLs (`//i0.hdslb.com/...`). */
    private fun normalizeThumbnailUrl(pic: String?): String? = when {
        pic.isNullOrEmpty() -> null
        pic.startsWith("//") -> "https:$pic"
        else -> pic
    }

    /** Parses a search result's `"mm:ss"` / `"hh:mm:ss"` duration string into seconds. */
    private fun parseSearchDuration(text: String?): Long? {
        val parts = text?.split(":")?.map { it.toLongOrNull() ?: return null } ?: return null
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    /** Follows a `b23.tv` short link's redirect chain and reparses the final URL into a BIlibili source. */
    private fun resolveShortlink(url: String): MediaSource.Bilibili? {
        var current = url
        repeat(MAX_REDIRECT_HOPS) {
            val location = runCatching {
                DreamHttpClient.peekRedirectLocation(
                    current,
                    DreamHttpClient.RequestOptions(headers = headers(), connectTimeoutMs = 8_000, readTimeoutMs = 8_000),
                )
            }.getOrNull() ?: return BilibiliUrls.parse(current)?.takeIf { it.isResolved }

            val resolved = BilibiliUrls.parse(location)
            if (resolved != null && resolved.isResolved) return resolved
            current = location
        }
        return BilibiliUrls.parse(current)?.takeIf { it.isResolved }
    }

    /** True once a BIlibili source carries an actual id, i.e. is no longer a bare `b23.tv` short link. */
    private val MediaSource.Bilibili.isResolved: Boolean
        get() = bvid != null || avid != null || roomId != null || epId != null || seasonId != null

    /** Resolves a VOD identified by [bvid] or legacy [avid], at part [part] (1-based). */
    private fun resolveVod(bvid: String?, avid: Long?, part: Int): BilibiliPlayback? {
        val viewParams = buildMap {
            bvid?.let { put("bvid", it) }
            avid?.let { put("aid", it.toString()) }
        }
        if (viewParams.isEmpty()) return null

        val viewUrl = "https://api.bilibili.com/x/web-interface/view?${plainQuery(viewParams)}"
        val viewRoot = fetchView(viewUrl)
            ?: return failResolve("view API request failed", viewUrl)
        biliRejectionReason(viewRoot)?.let { return failResolve("view API returned $it", viewUrl) }
        val data = viewRoot.obj("data") ?: return failResolve("view API carried no data object", viewUrl)

        val pages = data.array("pages")?.mapNotNull { it.asJsonObjectOrNull() }
        val page = pages?.firstOrNull { it.optInt("page") == part } ?: pages?.firstOrNull()
        val cid = page?.optLong("cid") ?: data.optLong("cid")
            ?: return failResolve("view API carried no cid for part $part", viewUrl)
        val resolvedBvid = data.optString("bvid") ?: bvid

        val playurlParams = buildMap {
            resolvedBvid?.let { put("bvid", it) }
            put("cid", cid.toString())
            // `qn` is a *request* for one preferred rendition, not a menu. Requesting 1080p made
            // Bilibili return only the renditions near that rung, so most videos exposed a single
            // quality in the display menu while a few (where the CDN happened to include extra
            // tracks) offered several. 127 is Bilibili's uppermost qn (8K/4320p): the server then
            // answers with every rendition the account is entitled to and the display menu can list
            // all of them, with entitlement still enforced server-side.
            put("qn", QN_MAX)
            put("fnval", FNVAL_DASH_ALL)
            put("fourk", "1")
        }
        val playurlUrl = "https://api.bilibili.com/x/player/playurl?${signedQuery(playurlParams)}"
        val playurlRoot = getJson(playurlUrl) ?: return failResolve("playurl request failed", playurlUrl)
        biliRejectionReason(playurlRoot)?.let { return failResolve("playurl returned $it", playurlUrl) }
        val streams = buildStreams(playurlRoot.obj("data"))
        if (streams.isEmpty()) return failResolve("playurl carried no usable streams", playurlUrl)

        // Panorama detection reads the view API's *original* dimension, not the resolved
        // streams: their height was just rewritten to a canonical rung, which would erase the
        // 2:1 equirectangular shape this check depends on.
        val dimension = data.obj("dimension")
        val panoramic = resolvedBvid != null &&
            isEquirectangularFrame(dimension?.optInt("width"), dimension?.optInt("height")) &&
            hasPanoramicTag(fetchTagNames(resolvedBvid))

        val stat = data.obj("stat")
        val owner = data.obj("owner")
        val metadata = PlatformVideoMetadata(
            title = data.optString("title"),
            uploader = owner?.optString("name"),
            thumbnailUrl = data.optString("pic"),
            uploaderAvatarUrl = owner?.optString("face"),
            viewCount = stat?.optLong("view"),
            durationSec = data.optLong("duration"),
            isLive = false,
        )
        return BilibiliPlayback(
            streams = streams, metadata = metadata, isSeekable = true, panoramic = panoramic,
        )
    }

    /** Resolves a bangumi/movie episode identified by [epId], or the first episode of season [seasonId]. */
    private fun resolveBangumi(epId: Long?, seasonId: Long?): BilibiliPlayback? {
        val seasonParams = buildMap {
            epId?.let { put("ep_id", it.toString()) }
            seasonId?.let { put("season_id", it.toString()) }
        }
        if (seasonParams.isEmpty()) return null

        val seasonUrl = "https://api.bilibili.com/pgc/view/web/season?${plainQuery(seasonParams)}"
        val seasonRoot = getJson(seasonUrl) ?: return failResolve("season API request failed", seasonUrl)
        biliRejectionReason(seasonRoot)?.let { return failResolve("season API returned $it", seasonUrl) }
        val result = seasonRoot.obj("result") ?: return failResolve("season API carried no result object", seasonUrl)

        val episodes = result.array("episodes")?.mapNotNull { it.asJsonObjectOrNull() }
            ?: return failResolve("season API carried no episodes array", seasonUrl)
        val sections = sectionEpisodes(result)
        val episode = selectBangumiEpisode(episodes, sections, epId)
        if (episode == null) {
            logger.warn(
                "Bilibili bangumi ep {} is not in the season payload ({} main episodes, {} section episodes); refusing to fall back to the first episode.",
                epId, episodes.size, sections.size,
            )
            return null
        }
        val resolvedEpId = episode.optLong("ep_id")
            ?: return failResolve("season episode carried no ep_id", seasonUrl)

        // Same reasoning as `resolveVod`: a non-top `qn` collapses the answer to the renditions near
        // that one rung, which left the quality slider with a single stop. The PGC endpoint also
        // needs its own (wider) `fnval` bitfield.
        val bangumiPlayUrl =
            "https://api.bilibili.com/pgc/player/web/v2/playurl?fnval=$FNVAL_DASH_ALL_PGC&fourk=1&qn=$QN_MAX&ep_id=$resolvedEpId"
        val playurlRoot = getJson(bangumiPlayUrl)
            ?: return failResolve("bangumi playurl request failed", bangumiPlayUrl)
        biliRejectionReason(playurlRoot)?.let { return failResolve("bangumi playurl returned $it", bangumiPlayUrl) }
        val streams = buildStreams(playurlRoot.obj("result")?.obj("video_info"))
        if (streams.isEmpty()) {
            return failResolve("bangumi playurl carried no usable streams for ep $resolvedEpId", bangumiPlayUrl)
        }

        val seasonTitle = result.optString("title")
        val episodeTitle = episode.optString("long_title")?.takeIf { it.isNotBlank() }
            ?: episode.optString("show_title")
        val title = listOfNotNull(seasonTitle, episodeTitle).joinToString(" · ").ifBlank { null }

        val metadata = PlatformVideoMetadata(
            title = title,
            uploader = null,
            thumbnailUrl = episode.optString("cover") ?: result.optString("cover"),
            uploaderAvatarUrl = null,
            viewCount = null,
            durationSec = episode.optLong("duration")?.let { it / 1000 },
            isLive = false,
        )
        return BilibiliPlayback(streams = streams, metadata = metadata, isSeekable = true)
    }

    /**
     * Flattens the `section` lists (specials / previews / trailers) that Bilibili returns beside the
     * main-run `episodes` of a season payload.
     */
    private fun sectionEpisodes(result: JsonObject): List<JsonObject> =
        result.array("section")
            ?.mapNotNull { it.asJsonObjectOrNull() }
            ?.flatMap { section -> section.array("episodes")?.mapNotNull { it.asJsonObjectOrNull() }.orEmpty() }
            .orEmpty()

    /**
     * Picks the episode to play from a season payload.
     *
     * An explicit [epId] must match a main-run or `section` episode exactly: falling back to
     * `episodes.first()` played the season's FIRST episode for a pasted `ep<id>` link, which is the
     * reported "the link resolved to a different video". A [seasonId]-only request legitimately
     * starts at the first episode. Returns null when an explicit [epId] is present nowhere, so the
     * caller reports a failure instead of playing the wrong episode.
     */
    internal fun selectBangumiEpisode(episodes: List<JsonObject>, sections: List<JsonObject>, epId: Long?): JsonObject? =
        if (epId != null) {
            episodes.firstOrNull { it.optLong("ep_id") == epId }
                ?: sections.firstOrNull { it.optLong("ep_id") == epId }
        } else {
            episodes.firstOrNull()
        }

    /** Resolves a live room identified by [roomId]. */
    private fun resolveLive(roomId: Long): BilibiliPlayback {
        val infoRoot = getJson("https://api.live.bilibili.com/room/v1/Room/get_info?room_id=$roomId")
        val info = infoRoot?.obj("data")
        val isLive = info?.optInt("live_status") == 1

        val metadata = PlatformVideoMetadata(
            title = info?.optString("title"),
            uploader = null,
            thumbnailUrl = info?.optString("user_cover") ?: info?.optString("keyframe"),
            uploaderAvatarUrl = null,
            viewCount = info?.optLong("online"),
            durationSec = null,
            isLive = isLive,
        )

        if (!isLive) return BilibiliPlayback(streams = emptyList(), metadata = metadata, isSeekable = false)

        val playParams = mapOf(
            "room_id" to roomId.toString(),
            "protocol" to "0,1",
            "format" to "0,1,2",
            "codec" to "0,1",
            "qn" to "10000",
        )
        val livePlayUrl = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?${signedQuery(playParams)}"
        val playRoot = getJson(livePlayUrl)
        if (playRoot == null) {
            logger.warn("Bilibili live playinfo request failed for room {}; returning the room metadata without streams.", roomId)
        }
        val streams = buildLiveStreams(playRoot?.obj("data"))
        return BilibiliPlayback(streams = streams, metadata = metadata, isSeekable = false)
    }

    /** Builds video-only / audio-only [MediaStream]s from a VOD `playurl` DASH response, or muxed ones from `durl`. */
    private fun buildStreams(playurlData: JsonObject?): List<MediaStream> {
        if (playurlData == null) return emptyList()

        val dash = playurlData.obj("dash")
        if (dash != null) {
            val streams = ArrayList<MediaStream>()
            dash.array("video")?.mapNotNull { it.asJsonObjectOrNull() }?.forEach { v ->
                val urls = dashUrls(v) ?: return@forEach
                streams += MediaStream(
                    url = urls.first(), backupUrls = urls.drop(1),
                    type = MediaStreamType.VIDEO,
                    // `codecs` is e.g. "avc1.64001f" (H.264) / "av01.0.05M.08" (AV1) /
                    // "hev1.1.6.L120" (HEVC). Populated so stream consumers can prefer H.264
                    // renditions (e.g. scrub extraction, which software-decodes and stalls on
                    // AV1/HEVC long-GOP streams).
                    codec = v.optString("codecs")?.takeIf { it.isNotEmpty() },
                    width = canonicalWidth(v),
                    // Bilibili movie / bangumi DASH streams report the actual encoded height
                    // (e.g. 808 instead of 1080), which makes the quality selector show "808p"
                    // instead of "1080p". Map the qn (id) to the canonical height when available.
                    // [canonicalWidth] rescales the width to match, or a portrait 1080x1920 source
                    // would keep width=1080 next to height=1080 and collapse to a 1:1 texture.
                    height = canonicalHeight(v),
                    fps = v.optString("frameRate")?.toDoubleOrNull(),
                    bitrate = v.optInt("bandwidth"),
                    audioTrackName = null,
                    audioTrackLang = null,
                )
            }
            dash.array("audio")?.mapNotNull { it.asJsonObjectOrNull() }?.forEach { a ->
                val urls = dashUrls(a) ?: return@forEach
                streams += MediaStream(
                    url = urls.first(), backupUrls = urls.drop(1),
                    type = MediaStreamType.AUDIO,
                    codec = null,
                    width = null,
                    height = null,
                    fps = null,
                    bitrate = a.optInt("bandwidth"),
                    audioTrackName = null,
                    audioTrackLang = null,
                )
            }
            if (streams.isNotEmpty()) return streams
        }

        // Very low quality / old videos have no DASH manifest, only progressive URLs.
        // Regular VODs key this "durl"; bangumi's `video_info` keys the same shape "durls".
        // Each durl entry may carry its own `url` and `backup_url` array.
        val durls = (playurlData.array("durl") ?: playurlData.array("durls"))
            ?.mapNotNull { it.asJsonObjectOrNull() } ?: return emptyList()
        val allUrls = durls.flatMap { d ->
            buildList {
                d.optString("url")?.let { add(it) }
                d.array("backup_url")?.forEach { e -> (e as? JsonPrimitive)?.content?.let { add(it) } }
                d.array("backupUrl")?.forEach { e -> (e as? JsonPrimitive)?.content?.let { add(it) } }
            }
        }.distinct()
        val primary = allUrls.firstOrNull() ?: return emptyList()
        return listOf(
            MediaStream(
                url = primary, backupUrls = allUrls.drop(1),
                type = MediaStreamType.VIDEO_AUDIO,
                codec = null, width = null, height = null, fps = null,
                bitrate = durls.firstOrNull()?.optInt("bandwidth"),
                audioTrackName = null, audioTrackLang = null,
            ),
        )
    }

    /** Builds muxed live streams (FLV / HLS) from a `getRoomPlayInfo` response. */
    private fun buildLiveStreams(playInfoData: JsonObject?): List<MediaStream> {
        val streams = ArrayList<MediaStream>()
        val codecs = playInfoData?.obj("playurl_info")?.obj("playurl")?.array("stream")
            ?.mapNotNull { it.asJsonObjectOrNull() } ?: return streams
        for (stream in codecs) {
            val formats = stream.array("format")?.mapNotNull { it.asJsonObjectOrNull() } ?: continue
            for (format in formats) {
                val codecList = format.array("codec")?.mapNotNull { it.asJsonObjectOrNull() } ?: continue
                for (codec in codecList) {
                    val baseUrl = codec.optString("base_url") ?: continue
                    val urlInfo = codec.array("url_info")?.mapNotNull { it.asJsonObjectOrNull() }.orEmpty()
                    if (urlInfo.isEmpty()) continue
                    // Collect all CDN hosts from url_info, first is primary, rest are backups
                    val urls = urlInfo.mapNotNull { ui ->
                        val host = ui.optString("host") ?: return@mapNotNull null
                        "$host$baseUrl${ui.optString("extra").orEmpty()}"
                    }.distinct()
                    val first = urls.firstOrNull() ?: continue
                    streams += MediaStream(
                        url = first, backupUrls = urls.drop(1),
                        type = MediaStreamType.VIDEO_AUDIO,
                        codec = codec.optString("codec_name"), width = null, height = null, fps = null,
                        bitrate = codec.optInt("current_qn"), audioTrackName = null, audioTrackLang = null,
                    )
                }
            }
        }
        return streams
    }

    /** Reads a DASH representation's playable URLs: primary + all backup CDNs. */
    private fun dashUrls(rep: JsonObject): List<String>? = buildList {
        rep.optString("baseUrl")?.let { add(it) }
        rep.optString("base_url")?.let { if (it !in this) add(it) }
        rep.array("backupUrl")?.forEach { e -> (e as? JsonPrimitive)?.content?.let { if (it !in this) add(it) } }
        rep.array("backup_url")?.forEach { e -> (e as? JsonPrimitive)?.content?.let { if (it !in this) add(it) } }
    }.takeIf { it.isNotEmpty() }

    /** Fetches (and caches) the WBI `img_key` / `sub_key` pair used to sign gated endpoints. */
    private fun wbiKeys(): WbiKeys? {
        wbiCache?.let { if (Clock.System.now() - it.fetchedAt < WBI_TTL) return it }

        val wbiImg = getJson("https://api.bilibili.com/x/web-interface/nav")?.obj("data")?.obj("wbi_img")
        val imgUrl = wbiImg?.optString("img_url")
        val subUrl = wbiImg?.optString("sub_url") ?: wbiImg?.optString("sub_key_url")
        if (imgUrl == null || subUrl == null) return wbiCache

        return WbiKeys(keySegment(imgUrl), keySegment(subUrl), Clock.System.now()).also { wbiCache = it }
    }

    /** Extracts the filename segment (stripped of extension) that WBI uses as a key from a signed asset URL. */
    private fun keySegment(url: String): String = url.substringAfterLast('/').substringBeforeLast('.')

    /** Builds the 32-char WBI mixin key by permuting `imgKey + subKey` through [MIXIN_KEY_ENC_TAB]. */
    private fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        val sb = StringBuilder(32)
        for (i in 0 until 32) {
            val idx = MIXIN_KEY_ENC_TAB[i]
            if (idx < raw.length) sb.append(raw[idx])
        }
        return sb.toString()
    }

    /** Builds a plain (unsigned) `key=value&...` query string, URL-encoding each value. */
    private fun plainQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }

    /**
     * Builds a WBI-signed query string for [params]: adds `wts` (unix seconds), sorts keys, strips
     * `!'()*` from values, appends the mixin key, and MD5-hashes the result into `w_rid`. Falls back
     * to a plain (unsigned) query when the WBI keys are unavailable — some endpoints still answer,
     * just at reduced quality.
     */
    private fun signedQuery(params: Map<String, String>): String {
        val wts = Clock.System.now().epochSeconds.toString()
        val all = (params + ("wts" to wts)).toSortedMap()
        val keys = wbiKeys() ?: return plainQuery(all)

        val filtered = all.mapValues { (_, v) -> v.filterNot { c -> c in "!'()*" } }
        val base = filtered.entries.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        val wRid = md5(base + mixinKey(keys.imgKey, keys.subKey))
        return "$base&w_rid=$wRid"
    }

    /** Bilibili qn (quality number) → canonical video height. The DASH stream's actual
     *  `height` may be the encoded pixel height (e.g. 808 for a movie) rather than the
     *  standard value (1080), which makes the quality selector show "808p" instead of
     *  "1080p". Mapping via the qn produces canonical labels. */
    private val QN_TO_HEIGHT = mapOf(
        6 to 240, 16 to 360, 32 to 480, 48 to 540,
        64 to 720, 74 to 720,
        80 to 1080, 112 to 1080, 116 to 1080,
        120 to 2160, 125 to 2160, 126 to 2160,
        127 to 4320,
    )

    /** Maps [qn] to the canonical height, or null when [qn] is not a known Bilibili quality number. */
    private fun qnToStandardHeight(qn: Int?): Int? = if (qn != null) QN_TO_HEIGHT[qn] else null

    /**
     * True when [width]x[height] has the 2:1 equirectangular shape of a 360/VR panorama.
     *
     * Deliberately only a *pre-filter*: 2:1 is also a common widescreen-movie ratio, and refusing
     * a video that would have played fine is worse than failing to flag one, so callers must still
     * confirm a panorama tag before acting on this.
     */
    internal fun isEquirectangularFrame(width: Int?, height: Int?): Boolean {
        if (width == null || height == null || width <= 0 || height <= 0) return false
        val longEdge = maxOf(width, height).toDouble()
        val shortEdge = minOf(width, height).toDouble()
        return abs(longEdge / shortEdge - 2.0) <= 0.04
    }

    /** True when any of [tagNames] marks the upload as a 360 / VR panorama. */
    internal fun hasPanoramicTag(tagNames: Collection<String>): Boolean = tagNames.any { raw ->
        val tag = raw.trim().lowercase()
        tag == "vr" || tag.startsWith("vr ") ||
            tag.contains("全景") || tag.contains("panorama") ||
            tag.contains("360°") || tag.contains("360度")
    }

    /**
     * Tag names for [bvid], or an empty list when the request fails.
     *
     * Only called once a video already looks equirectangular, so a normal video (the overwhelming
     * majority) never pays for this extra round trip. A failure here leaves the video playable
     * rather than blocking it.
     */
    private fun fetchTagNames(bvid: String): List<String> {
        val url = "https://api.bilibili.com/x/tag/archive/tags?bvid=$bvid"
        val root = runCatching { getJson(url) }.getOrNull() ?: return emptyList()
        return root.array("data")?.mapNotNull { element ->
            element.asJsonObjectOrNull()?.optString("tag_name")?.takeIf { it.isNotBlank() }
        } ?: emptyList()
    }

    /**
     * Canonical rung height for one DASH `video` entry: the qn's standard height when the qn is
     * known, else the encoded height as reported.
     */
    private fun canonicalHeight(video: JsonObject): Int? {
        val encoded = video.optInt("height")?.takeIf { it > 0 }
        return qnToStandardHeight(video.optInt("id")) ?: encoded
    }

    /**
     * Width scaled to the canonical rung height.
     *
     * The qn mapping rewrites the *height* to a standard rung (808 -> 1080 for a widescreen movie),
     * but Bilibili reports `height` as the long edge for portrait uploads too: a 1080x1920 phone
     * video has `id=80` (the 1080p rung) with `width=1080, height=1920`. Mapping only the height
     * turned that into 1080x1080, so the display allocated a square texture and squashed the
     * picture. Rescaling the width by the *encoded* aspect keeps the shape while the height still
     * carries a canonical rung the quality selector can compare against a target height.
     */
    private fun canonicalWidth(video: JsonObject): Int? {
        val encodedW = video.optInt("width")?.takeIf { it > 0 }
        val encodedH = video.optInt("height")?.takeIf { it > 0 }
        val canonical = canonicalHeight(video) ?: return encodedW
        if (encodedW == null || encodedH == null) return encodedW
        val scaled = (encodedW.toDouble() / encodedH.toDouble() * canonical.toDouble()).roundToInt()
        return scaled.coerceAtLeast(2)
    }

    /** URL-encodes [value] for a query string, using `%20` (not `+`) for spaces. */
    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Returns the lowercase hex MD5 digest of [s]. */
    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(HEX_CHARS[v ushr 4])
                append(HEX_CHARS[v and 0x0F])
            }
        }
    }

    /** GETs [url] and parses it as a JSON object, or null on any failure (offline, blocked, 404). */
    private fun getJson(url: String): JsonObject? {
        // Only the endpoints that actually get WAF-blocked are parked. Search was still working
        // while `view` was banned, so pausing everything would break a feature that works.
        if (isWafProneUrl(url)) {
            val cooldown = remainingWafCooldownMs()
            if (cooldown > 0) {
                logger.warn(
                    "Skipping Bilibili request for {}: still cooling down after a WAF block ({} ms left).",
                    url.substringBefore('?'), cooldown,
                )
                return null
            }
        }
        return runCatching {
            val body = DreamHttpClient.readText(
                url,
                DreamHttpClient.RequestOptions(headers = headers(), connectTimeoutMs = 8_000, readTimeoutMs = 8_000),
            )
            DreamJson.compact.parseToJsonElement(body).asJsonObjectOrNull()
        }.onFailure { error ->
            logger.warn("Bilibili API fetch failed for {}: {}.", url, error.toString())
            if (isBannedResponse(error)) noteWafBlock(url)
        }.getOrNull()
    }

    /**
     * Endpoints Bilibili's WAF actually throttles. `x/web-interface/view` is the one a tester hit;
     * the WBI-signed search endpoints kept working through the same ban, so they stay unparked.
     */
    private fun isWafProneUrl(url: String): Boolean =
        url.contains("/x/web-interface/view") ||
            url.contains("/x/player/") ||
            url.contains("/pgc/") ||
            url.contains("/xlive/")

    /** When the last WAF block was seen, so a burst of resolves cannot keep it alive. */
    @Volatile
    private var wafBlockedAt: Instant? = null

    /**
     * Milliseconds left in the post-412 cooldown, or 0 when requests may proceed.
     *
     * A tester's log showed ~30 identical `view` requests in one second, each rejected with 412.
     * Retrying a WAF ban immediately is what turns a short throttle into a long one, and every retry
     * also re-rendered a display the player could not watch, so requests are parked for
     * [WAF_COOLDOWN] after the first block.
     */
    private fun remainingWafCooldownMs(): Long {
        val since = wafBlockedAt ?: return 0
        val elapsed = Clock.System.now() - since
        val left = WAF_COOLDOWN - elapsed
        return if (left.isPositive()) left.inWholeMilliseconds else 0
    }

    /** True when [error] is Bilibili's WAF rejection rather than a transport problem. */
    internal fun isBannedResponse(error: Throwable): Boolean {
        val text = generateSequence(error as Throwable?) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
        return text.contains("HTTP 412") || text.contains("code\":-412") || text.contains("request was banned")
    }

    /** Records a WAF block and refreshes the device fingerprint, at most once per cooldown. */
    private fun noteWafBlock(url: String) {
        // Already parked: re-recording the block would push the cooldown forward forever, so a
        // 412-per-attempt loop would never expire. Let the first one run its course.
        if (remainingWafCooldownMs() > 0) return
        wafBlockedAt = Clock.System.now()
        logger.warn(
            "Bilibili blocked this client (HTTP 412) on {}; pausing Bilibili requests for {} and requesting a fresh fingerprint.",
            url.substringBefore('?'), WAF_COOLDOWN,
        )
        ensureDeviceCookies(force = true)
    }

    /**
     * Turns a Bilibili API payload into a human-readable rejection reason, or null when the call
     * really succeeded.
     *
     * Bilibili reports failures inside an HTTP 200 body: `{"code":-404,"message":"啥都木有"}` for a
     * missing or region-locked video, `{"code":-412}` when its WAF rejects the client (usually a
     * missing/blocked `Referer`, or a datacenter IP), `{"code":-10403}` when the account lacks the
     * needed entitlement. Reading only `data` made every one of those look like an unrelated null,
     * so the player only ever saw "Bilibili video/room could not be reached." with no cause.
     */
    internal fun biliRejectionReason(root: JsonObject): String? {
        val code = root.optInt("code") ?: return null
        if (code == 0) return null
        val message = root.optString("message")?.takeIf { it.isNotBlank() } ?: "no message"
        // Bilibili sometimes delivers the WAF rejection as HTTP 200 + `{"code":-412}`, not as a 412
        // status, so the ban has to be recorded from the body as well. Otherwise the cooldown that
        // stops the retry storm would only ever trigger on the status-code variant.
        if (code == WAF_BAN_CODE) noteWafBlock("api.bilibili.com (code -412 in body)")
        return "code=$code ($message)"
    }

    /**
     * Reads `x/web-interface/view`, falling back to the WBI-signed `x/web-interface/wbi/view`.
     *
     * The plain endpoint is what Bilibili's WAF throttles first: a tester's log showed it answering
     * `HTTP 412 {"code":-412,"message":"request was banned"}` while the WBI-signed search endpoint
     * kept working from the same client. The WBI variant is signed with the same `img_key`/`sub_key`
     * keys already cached for `playurl`, so trying it turns a hard failure into a working play.
     */
    private fun fetchView(plainViewUrl: String): JsonObject? {
        val query = plainViewUrl.substringAfter('?', "")
        val signed = "https://api.bilibili.com/x/web-interface/wbi/view?${signedQuery(queryParamsOf(query))}"
        // Try the signed endpoint first once a ban has been seen; it is the one that survives.
        if (wafBlockedAt != null) {
            getJson(signed)?.let { if (biliRejectionReason(it) == null) return it }
        }
        val plain = getJson(plainViewUrl)
        if (plain != null && biliRejectionReason(plain) == null) return plain
        return getJson(signed) ?: plain
    }

    /** Splits an already-encoded `a=1&b=2` query back into a map for re-signing. */
    private fun queryParamsOf(query: String): Map<String, String> =
        query.split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val key = pair.substringBefore('=')
                key to pair.substringAfter('=', "")
            }

    /**
     * Records why a resolution attempt produced nothing and returns null, so callers can keep
     * returning `BilibiliPlayback?`. Without this every failure reached the player as the same
     * opaque "Bilibili video/room could not be reached." line, with nothing in the log to say
     * whether it was the network, Bilibili's WAF, or an unsupported video.
     */
    private fun failResolve(reason: String, url: String): BilibiliPlayback? {
        logger.warn("Bilibili resolve failed: {} ({})", reason, url.substringBefore('?'))
        return null
    }

    /** GETs the raw binary body at [url] with a Bilibili-shaped [referer], or null on any failure. */
    private fun getBytes(url: String, referer: String): ByteArray? = runCatching {
        DreamHttpClient.readBytes(
            url,
            DreamHttpClient.RequestOptions(
                headers = headers() + ("Referer" to listOf(referer)),
                connectTimeoutMs = 8_000,
                readTimeoutMs = 8_000,
            ),
        )
    }.onFailure { logger.debug("Bilibili binary fetch failed for {}: {}.", url, it.message) }.getOrNull()
}
