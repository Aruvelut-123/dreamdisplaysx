package com.dreamdisplayx.media.source.bilibili

import com.dreamdisplayx.api.media.source.url.BilibiliUrls
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.media.stream.model.MediaStream
import com.dreamdisplayx.api.media.stream.model.MediaStreamType
import com.dreamdisplayx.media.source.platform.PlatformVideoMetadata
import com.dreamdisplayx.util.*
import com.dreamdisplayx.util.json.DreamJson
import com.dreamdisplayx.util.net.DreamHttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A resolved BIlibili VOD or live room: playable [streams] and [metadata]. */
data class BilibiliPlayback(
    val streams: List<MediaStream>,
    val metadata: PlatformVideoMetadata,
    val isSeekable: Boolean,
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

/**
 * Resolves BIlibili VODs and live rooms through BIlibili's public web API — the same JSON the
 * website uses, gated behind WBI-signed `playurl` requests (no API key or auth required).
 */
object BilibiliApi {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/BilibiliApi")

    /**
     * Bilibili login cookie string set by the client after a platform login (see `BilibiliAuth`),
     * e.g. `SESSDATA=...; bili_jct=...`. Enables VIP / higher-quality playback; empty when not logged in.
     */
    @Volatile
    var cookie: String = ""

    /** BIlibili's API rejects requests without a browser-shaped `Referer` / `User-Agent`; the login cookie is added when present. */
    private fun headers(): Map<String, List<String>> =
        DreamHttpClient.headersOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Accept" to "application/json",
            "Referer" to "https://www.bilibili.com",
            "Cookie" to cookie,
        )

    /** `b23.tv` short links get followed for at most this many hops before giving up. */
    private const val MAX_REDIRECT_HOPS = 5

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
    private fun stripHighlightTags(title: String): String = title.replace(Regex("</?em[^>]*>"), "")

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

        val viewRoot = getJson("https://api.bilibili.com/x/web-interface/view?${plainQuery(viewParams)}")
        val data = viewRoot?.obj("data") ?: return null

        val pages = data.array("pages")?.mapNotNull { it.asJsonObjectOrNull() }
        val page = pages?.firstOrNull { it.optInt("page") == part } ?: pages?.firstOrNull()
        val cid = page?.optLong("cid") ?: data.optLong("cid") ?: return null
        val resolvedBvid = data.optString("bvid") ?: bvid

        val playurlParams = buildMap {
            resolvedBvid?.let { put("bvid", it) }
            put("cid", cid.toString())
            put("qn", "80")
            put("fnval", "4048")
            put("fourk", "1")
        }
        val playurlRoot = getJson("https://api.bilibili.com/x/player/playurl?${signedQuery(playurlParams)}")
        val streams = buildStreams(playurlRoot?.obj("data"))
        if (streams.isEmpty()) return null

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
        return BilibiliPlayback(streams = streams, metadata = metadata, isSeekable = true)
    }

    /** Resolves a bangumi/movie episode identified by [epId], or the first episode of season [seasonId]. */
    private fun resolveBangumi(epId: Long?, seasonId: Long?): BilibiliPlayback? {
        val seasonParams = buildMap {
            epId?.let { put("ep_id", it.toString()) }
            seasonId?.let { put("season_id", it.toString()) }
        }
        if (seasonParams.isEmpty()) return null

        val seasonRoot = getJson("https://api.bilibili.com/pgc/view/web/season?${plainQuery(seasonParams)}")
        val result = seasonRoot?.obj("result") ?: return null

        val episodes = result.array("episodes")?.mapNotNull { it.asJsonObjectOrNull() } ?: return null
        val episode = epId?.let { id -> episodes.firstOrNull { it.optLong("ep_id") == id } }
            ?: episodes.firstOrNull()
            ?: return null
        val resolvedEpId = episode.optLong("ep_id") ?: return null

        val playurlRoot =
            getJson("https://api.bilibili.com/pgc/player/web/v2/playurl?fnval=12240&fourk=1&ep_id=$resolvedEpId")
        val streams = buildStreams(playurlRoot?.obj("result")?.obj("video_info"))
        if (streams.isEmpty()) return null

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
        val playRoot = getJson(
            "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?${signedQuery(playParams)}",
        )
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
                    codec = null,
                    width = v.optInt("width"),
                    // Bilibili movie / bangumi DASH streams report the actual encoded height
                    // (e.g. 808 instead of 1080), which makes the quality selector show "808p"
                    // instead of "1080p". Map the qn (id) to the canonical height when available.
                    height = qnToStandardHeight(v.optInt("id")) ?: v.optInt("height"),
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

    /** URL-encodes [value] for a query string, using `%20` (not `+`) for spaces. */
    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Returns the lowercase hex MD5 digest of [s]. */
    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** GETs [url] and parses it as a JSON object, or null on any failure (offline, blocked, 404). */
    private fun getJson(url: String): JsonObject? = runCatching {
        val body = DreamHttpClient.readText(
            url,
            DreamHttpClient.RequestOptions(headers = headers(), connectTimeoutMs = 8_000, readTimeoutMs = 8_000),
        )
        DreamJson.compact.parseToJsonElement(body).asJsonObjectOrNull()
    }.onFailure { logger.debug("Bilibili API fetch failed for {}: {}.", url, it.message) }.getOrNull()
}
