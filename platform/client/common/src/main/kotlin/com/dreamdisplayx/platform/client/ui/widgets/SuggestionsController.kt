package com.dreamdisplayx.platform.client.ui.widgets

import com.dreamdisplayx.api.media.service.keys.MediaServices
import com.dreamdisplayx.api.media.search.model.MediaSearchResult
import com.dreamdisplayx.api.media.source.url.YouTubeUrls
import com.dreamdisplayx.api.media.source.url.CustomMediaUrls
import com.dreamdisplayx.api.media.source.model.MediaPlatform
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.media.source.bilibili.BilibiliApi
import com.dreamdisplayx.media.source.bilibili.BilibiliMetadataCache
import com.dreamdisplayx.media.source.bilibili.BilibiliSearchItem
import com.dreamdisplayx.media.source.bilibili.BilibiliSearchType
import com.dreamdisplayx.media.source.kick.KickMetadataCache
import com.dreamdisplayx.media.source.platform.PlatformVideoMetadata
import com.dreamdisplayx.media.source.twitch.TwitchApi
import com.dreamdisplayx.media.source.twitch.TwitchMetadata
import com.dreamdisplayx.media.source.twitch.TwitchMetadataCache
import com.dreamdisplayx.media.source.twitch.TwitchSearchItem
import com.dreamdisplayx.media.source.vimeo.VimeoMetadataCache
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.render.Thumbnails
import com.dreamdisplayx.platform.client.storage.CustomVideoStore
import com.dreamdisplayx.platform.client.storage.WatchedVideoStore
import com.dreamdisplayx.util.DreamCoroutines
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Async state machine behind the suggestions panel: runs searches and related-video lookups on a
 * background coroutine, publishes results back on the client thread, and drops stale responses via a
 * request sequence number. Holds no rendering state, so the panel widget stays a pure view.
 */
class SuggestionsController {
    /** Current result cards, mutated only on the client thread. */
    val cards = ArrayList<MediaSearchResult>()

    /**
     * [cards] after applying [sortOption]'s client-side effect: [SortOption.POPULARITY] / [SortOption.NEWEST] re-sort,
     * [SortOption.STREAMS] filters to live.
     */
    val visibleCards: List<MediaSearchResult>
        get() = when (sortOption) {
            SortOption.RELEVANCE -> cards
            SortOption.POPULARITY -> cards.sortedByDescending { it.viewCount ?: -1L }
            SortOption.NEWEST -> cards.sortedBy { it.publishedDaysAgo ?: Int.MAX_VALUE }
            SortOption.STREAMS -> cards.filter { it.isLive }
            SortOption.UNWATCHED -> cards.filterNot { WatchedVideoStore.isWatched(it.id) }
            SortOption.WATCHED -> cards.filter { WatchedVideoStore.isWatched(it.id) }
            SortOption.MY_LINKS -> CustomVideoStore.asResults()
        }

    /**
     * Translation key of the current status line (loading/empty/error), or null when results are
     * shown. While [SortOption.MY_LINKS] is active it reports on the local link list instead of the
     * in-flight request, so an unrelated search still loading never hides the remembered links.
     */
    val statusKey: String?
        get() = if (sortOption.isOwnList) {
            if (CustomVideoStore.isEmpty()) KEY_NO_LINKS else null
        } else {
            loadStatusKey
        }

    /** Status of the current network load, independent of which list is on screen. */
    private var loadStatusKey: String? = null

    /** Wall-clock start of the in-flight load, for the elapsed-seconds suffix on the loading message. */
    var loadStartedAtMs: Long = 0L; private set

    /** Currently selected sort/filter; see [setSort]. */
    var sortOption: SortOption = SortOption.RELEVANCE; private set

    private val requestSeq = atomic(0)
    private var currentVideoId: String? = null

    /** The last non-empty text search query, so [setSort] knows what to re-run for a network sort change. */
    private var lastQuery: String? = null

    /** How to continue the current result list when [loadMoreIfNeeded] fires; null when the current
     *  list isn't paginable (single-video / Twitch-only results). */
    private var moreMode: MoreMode? = null

    /** Continuation token for the next page, or null when the list is exhausted (or not yet loaded). */
    private var continuationToken: String? = null

    /** Guards against firing a second page-2 request while one is already in flight. */
    private var loadingMore = false

    /** Reload hook the panel uses to reset scroll when new results land. */
    var onResults: () -> Unit = {}

    /** True while the status line is the animated loading message. */
    val isLoading: Boolean get() = loadStatusKey == KEY_LOADING

    /** True while a background "load more" request is in flight (distinct from the initial [isLoading]). */
    val isLoadingMore: Boolean get() = loadingMore

    /** Distinguishes what a follow-up "load more" page should continue: a text search or a related-video list. */
    private sealed class MoreMode {
        data class Search(val query: String) : MoreMode()
        data class Related(val videoId: String) : MoreMode()
    }

    /** Shows videos related to [videoId]; clears the panel when null/empty; no-op if already shown. */
    fun setRelatedTo(videoId: String?) {
        if (videoId.isNullOrEmpty()) {
            currentVideoId = null
            lastQuery = null
            cards.clear()
            loadStatusKey = null
            return
        }
        if (videoId == currentVideoId && cards.isNotEmpty()) return
        currentVideoId = videoId
        lastQuery = null
        loadRelated(videoId)
    }

    /**
     * Changes the active sort / filter. [SortOption.UNWATCHED]/[SortOption.WATCHED] take effect purely through [visibleCards];
     * others may re-fetch.
     */
    fun setSort(option: SortOption) {
        if (option == sortOption) return
        sortOption = option
        onResults()
        if (option.refetches) lastQuery?.let { runSearch(it) }
    }

    /**
     * Forgets a remembered custom link. Only meaningful while [SortOption.MY_LINKS] shows the local
     * list; a no-op for anything else, since only custom results back a forgettable entry. The list
     * is read live from [CustomVideoStore], so dropping it there is all the refresh the panel needs.
     */
    fun forgetCustom(result: MediaSearchResult): Boolean {
        if (!result.isCustom) return false
        CustomVideoStore.forget(result.getWatchUrl())
        onResults()
        return true
    }

    /**
     * Runs a Bilibili-only free-text search. This fork's search is Bilibili-exclusive; it queries the
     * three Bilibili categories (video / bangumi / movie), ranks them by relevance, then publishes
     * the first page of [BILIBILI_PAGE_SIZE] results. An empty query falls back to the current
     * related list.
     */
    fun runSearch(query: String) {
        val q = query.trim()

        if (q.isEmpty()) {
            lastQuery = null
            currentVideoId?.let { loadRelated(it) }
            return
        }

        // Pasting into the box is a request to play that link, so leaving a local-list filter
        // active would hide the result the player just asked for.
        if (sortOption.isOwnList) sortOption = SortOption.RELEVANCE

        lastQuery = q
        startLoad()

        val seq = requestSeq.incrementAndGet()
        launchLoad {
            val results = runCatching {
                withContext(Dispatchers.IO) {
                    val videos = BilibiliApi.searchVideos(q)
                    val bangumi = BilibiliApi.searchBangumi(q)
                    val media = BilibiliApi.searchMedia(q)
                    val all = videos + bangumi + media
                    all.filter { (it.viewCount ?: 0L) >= BILIBILI_MIN_VIEWS }
                        .filter { bilibiliFilter.matches(it) }
                        .map(::bilibiliSearchResult)
                        .sortedWith(bilibiliRank(q))
                        .also { pendingResults = it }
                        .also { pendingOffset = 0 }
                        .take(BILIBILI_PAGE_SIZE)
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                logger.warn("Bilibili search failed: ${e.message}")
            }.getOrNull()

            results?.let { publish(seq, it, null, mode = MoreMode.Search(q)) }
        }
    }

    /** The active Bilibili media-type filter; see [setBilibiliFilter]. */
    var bilibiliFilter: BilibiliSearchType = BilibiliSearchType.ALL
        private set

    /** Switches the Bilibili media-type filter and re-runs the current search when one is active. */
    fun setBilibiliFilter(type: BilibiliSearchType) {
        if (type == bilibiliFilter) return
        bilibiliFilter = type
        onResults()
        lastQuery?.let { runSearch(it) }
    }

    /** Full sorted result set for the current Bilibili search, kept client-side for pagination. */
    private var pendingResults: List<MediaSearchResult> = emptyList()

    /** How many items from [pendingResults] have already been exposed to [cards]. */
    private var pendingOffset: Int = 0

    /** True when [query] contains a Han (Chinese-script) character, the gate for even trying a Bilibili search. */
    private fun looksChinese(query: String): Boolean = CHINESE_CHAR_RE.containsMatchIn(query)

    /** Returns [query] lowercased when it looks like a Twitch login (letters/digits/underscore, 3-25 chars). */
    private fun twitchLoginCandidate(query: String): String? {
        val q = query.trim()
        return if (TWITCH_LOGIN_RE.matches(q)) q.lowercase() else null
    }

    /**
     * Twitch's `searchSuggestions` matches channel/game names, not free text — a phrase like
     * "cs:go stream" matches nothing, while "cs go" (punctuation and the generic word stripped) does.
     * Strips punctuation and common filler words so a natural-language query still finds channels.
     */
    private fun twitchSearchFragment(query: String): String {
        val cleaned = query.replace(NON_WORD_RE, " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && !it.lowercase().isNoiseWord() }
            .joinToString(" ")
        return cleaned.ifBlank { query }
    }

    /** True when [this] (already lowercased) or its naive singular (trailing "s" stripped) is a known noise word. */
    private fun String.isNoiseWord(): Boolean =
        this in TWITCH_SEARCH_NOISE_WORDS || removeSuffix("s") in TWITCH_SEARCH_NOISE_WORDS

    /** Looks up [login] on Twitch and, if it's live, builds the card shown ahead of the YouTube results. */
    private fun liveTwitchResult(login: String): MediaSearchResult? = runCatching {
        TwitchApi.queryChannel(login)?.takeIf { it.isLive }?.let { meta ->
            twitchResult(MediaSource.Twitch(url = "https://www.twitch.tv/$login", channel = login), meta)
        }
    }.onFailure { e ->
        logger.debug("Twitch live-channel lookup failed for '$login': ${e.message}.")
    }.getOrNull()

    /** Builds a search-result card for a Bilibili hit — video by `bvid`, bangumi/movie by `epId`/`seasonId`. */
    private fun bilibiliSearchResult(item: BilibiliSearchItem): MediaSearchResult {
        val url = when {
            !item.bvid.isNullOrEmpty() -> "https://www.bilibili.com/video/${item.bvid}"
            item.epId != null -> "https://www.bilibili.com/bangumi/play/ep${item.epId}"
            item.seasonId != null -> "https://www.bilibili.com/bangumi/play/ss${item.seasonId}"
            else -> return MediaSearchResult(
                id = "", title = item.title, uploader = item.uploader,
                durationSec = item.durationSec, viewCount = item.viewCount,
                watchUrlOverride = null, thumbnailUrlOverride = item.thumbnailUrl,
                platform = MediaPlatform.BILIBILI,
                bilibiliMediaType = item.mediaType,
            )
        }
        return MediaSearchResult(
            id = url,
            title = item.title,
            uploader = item.uploader,
            durationSec = item.durationSec,
            viewCount = item.viewCount,
            watchUrlOverride = url,
            thumbnailUrlOverride = item.thumbnailUrl,
            platform = MediaPlatform.BILIBILI,
            bilibiliMediaType = item.mediaType,
        )
    }

    /** Priority bucket for Bilibili ranking. Lower = shown first. */
    private enum class RankClass { BANGUMI_MOVIE, UPLOADER_MATCH, TITLE_MATCH, OTHER }

    /**
     * Ranks Bilibili results so bangumi/movies surface first, then uploader-name matches, then
     * title matches, then everything else. The [query] is lowercased once for case-insensitive matching.
     */
    private fun bilibiliRank(query: String): Comparator<MediaSearchResult> {
        val q = query.lowercase()
        return compareBy<MediaSearchResult> { result ->
            when {
                result.bilibiliMediaType == "media_bangumi" || result.bilibiliMediaType == "pgc" -> RankClass.BANGUMI_MOVIE
                result.uploader?.lowercase()?.contains(q) == true -> RankClass.UPLOADER_MATCH
                result.title.lowercase().contains(q) -> RankClass.TITLE_MATCH
                else -> RankClass.OTHER
            }
        }.thenBy { result ->
            // Within the same bucket, sort by view count (descending).
            -(result.viewCount ?: 0L)
        }
    }

    /** Builds a search-result card for a Twitch channel hit, keyed by its channel URL like other platform cards. */
    private fun twitchSearchResult(item: TwitchSearchItem): MediaSearchResult {
        val url = "https://www.twitch.tv/${item.login}"
        return MediaSearchResult(
            id = url,
            title = item.title ?: item.displayName ?: item.login,
            uploader = item.displayName ?: item.login,
            durationSec = null,
            viewCount = item.viewCount,
            watchUrlOverride = url,
            thumbnailUrlOverride = item.thumbnailUrl,
            channelAvatarUrl = item.avatarUrl,
            isVerified = item.isVerified,
            isTwitch = true,
            isLive = item.isLive,
            platform = MediaPlatform.TWITCH,
        )
    }

    /**
     * Spreads every non-empty list in [lists] through the combined output so a mixed-platform search
     * reads as one shuffled list rather than "all YouTube, then all Bilibili, then all Twitch...". Each
     * list is entitled to a share of the output proportional to its [weight] rather than its own size —
     * e.g. weight 0.8 keeps claiming a slot roughly 4x as often as weight 0.1, regardless of how many
     * items either list actually has. A list that runs out early just stops competing; it does not
     * inflate the others' share.
     */
    private fun weightedInterleave(lists: List<Pair<List<MediaSearchResult>, Double>>): List<MediaSearchResult> {
        val sources = lists.filter { it.first.isNotEmpty() }
        if (sources.isEmpty()) return emptyList()
        if (sources.size == 1) return sources[0].first

        val totalSize = sources.sumOf { it.first.size }
        val result = ArrayList<MediaSearchResult>(totalSize)
        val taken = IntArray(sources.size)
        repeat(totalSize) {
            var bestIdx = -1
            var bestKey = Double.MAX_VALUE
            for (i in sources.indices) {
                val (list, weight) = sources[i]
                if (taken[i] >= list.size) continue
                val key = (taken[i] + 1) / weight
                val better = key < bestKey - FRACTION_EPSILON ||
                        (key < bestKey + FRACTION_EPSILON && (bestIdx == -1 || weight > sources[bestIdx].second))
                if (better) {
                    bestKey = key
                    bestIdx = i
                }
            }
            result.add(sources[bestIdx].first[taken[bestIdx]])
            taken[bestIdx]++
        }
        return result
    }

    /**
     * Picks the uploader (from [youtubeResults]) whose login shape is closest to [query] by edit
     * distance, within the tolerance [fuzzyThreshold] allows for its length, e.g. so "shrou" or
     * "shroug" still matches an uploader named "shroud". Returns null when no uploader is close enough.
     */
    private fun fuzzyTwitchLogin(query: String, youtubeResults: List<MediaSearchResult>?): String? {
        if (youtubeResults.isNullOrEmpty()) return null
        val qShape = toLoginShape(query)
        if (qShape.length < 3) return null
        val threshold = fuzzyThreshold(qShape.length)
        val seen = HashSet<String>()
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        for (result in youtubeResults) {
            val candidate = result.uploader?.let(::toLoginShape) ?: continue
            if (candidate.length < 3 || !seen.add(candidate)) continue
            val dist = levenshtein(qShape, candidate)
            if (dist <= threshold && dist < bestDist) {
                bestDist = dist
                best = candidate
            }
            if (seen.size >= FUZZY_CANDIDATE_LIMIT) break
        }
        return best
    }

    /** Normalizes free text to a Twitch login's character set: lowercase letters, digits, underscore. */
    private fun toLoginShape(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(25)

    /**
     * Elasticsearch-style "AUTO" fuzziness: how many single-character edits still count as the same
     * word, scaled by its length (short strings tolerate none, longer ones tolerate more).
     */
    private fun fuzzyThreshold(length: Int): Int = when {
        length <= 2 -> 0
        length <= 5 -> 1
        else -> 2
    }

    /** Levenshtein edit distance between [a] and [b] (Wagner-Fischer dynamic programming, single-row). */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /** Loads the related-videos list for [videoId] in the background. */
    private fun loadRelated(videoId: String) {
        startLoad()
        val seq = requestSeq.incrementAndGet()
        launchLoad {
            runCatching {
                DreamServices.registry.get(MediaServices.SEARCH).relatedPage(videoId, PAGE_SIZE)
            }.onSuccess { page ->
                publish(seq, page.results, null, nextToken = page.continuationToken, mode = MoreMode.Related(videoId))
            }.onFailure { e ->
                if (e is CancellationException) throw e
                logger.warn("Related failed $videoId: ${e.message}")
                publish(seq, null, KEY_ERROR)
            }
        }
    }

    /**
     * Appends the next page of results if the current list came from a paginable search / related load,
     * a page isn't already in flight, and the list isn't already exhausted. Bilibili searches page
     * locally from the fully-loaded sorted set, exposing [BILIBILI_PAGE_SIZE] more items per scroll.
     */
    fun loadMoreIfNeeded() {
        if (loadingMore || isLoading) return
        // The local link list is complete by definition; there is no page two to fetch
        if (sortOption.isOwnList) return
        val mode = moreMode ?: return

        if (mode is MoreMode.Search) {
            // Bilibili search: serve more from the already-fetched ranked list.
            val remaining = pendingResults.size - pendingOffset
            if (remaining <= 0) return
            val next = pendingResults.subList(pendingOffset, minOf(pendingOffset + BILIBILI_PAGE_SIZE, pendingResults.size))
            pendingOffset += next.size
            Minecraft.getInstance().execute {
                loadingMore = false
                appendCards(next)
            }
            return
        }

        val token = continuationToken ?: return
        loadingMore = true
        val seq = requestSeq.value
        launchLoad {
            val page = runCatching {
                val svc = DreamServices.registry.get(MediaServices.SEARCH)
                when (mode) {
                    is MoreMode.Search -> svc.searchMore(token, PAGE_SIZE)
                    is MoreMode.Related -> svc.relatedMore(token, PAGE_SIZE)
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                logger.warn("Load-more failed: ${e.message}")
            }.getOrNull()

            Minecraft.getInstance().execute {
                loadingMore = false
                if (seq != requestSeq.value || page == null) return@execute
                continuationToken = page.continuationToken
                appendCards(page.results)
            }
        }
    }

    /** Launch load. */
    private fun launchLoad(block: suspend CoroutineScope.() -> Unit) =
        DreamCoroutines.clientIo.launch(block = block)

    /** Switches the panel into the loading state and clears stale results. */
    private fun startLoad() {
        loadStatusKey = KEY_LOADING
        loadStartedAtMs = System.currentTimeMillis()
        cards.clear()
        moreMode = null
        continuationToken = null
        onResults()
    }

    /**
     * Applies a finished request on the client thread, ignoring it if a newer request superseded it.
     * [nextToken]/[mode] are set for paginable loads (plain search / related-videos); left null for
     * single-video or Twitch-only results, which have nothing more to page through.
     */
    private fun publish(
        seq: Int, results: List<MediaSearchResult>?, error: String?,
        nextToken: String? = null, mode: MoreMode? = null,
    ) {
        Minecraft.getInstance().execute {
            if (seq != requestSeq.value) return@execute
            cards.clear()
            moreMode = mode
            continuationToken = nextToken
            onResults()
            if (error != null) {
                loadStatusKey = error
                return@execute
            }
            if (results.isNullOrEmpty()) {
                loadStatusKey = KEY_EMPTY
                return@execute
            }
            loadStatusKey = null
            appendCards(results)
        }
    }

    /**
     * Adds [results] to [cards] (skipping ids already shown — search / related continuation pages can
     * overlap the previous page when the underlying list shifts between requests) and kicks off their
     * thumbnail downloads; must run on the client thread.
     */
    private fun appendCards(results: List<MediaSearchResult>) {
        val startIndex = cards.size
        val seen = HashSet<String>(cards.size).apply { cards.mapTo(this) { it.id } }
        for (info in results) if (seen.add(info.id)) cards.add(info)
        for (i in startIndex until cards.size) {
            val info = cards[i]
            val thumbUrl = info.thumbnailUrlOverride
            when {
                // A platform result (Twitch / Vimeo / Kick) carries its thumbnail URL directly
                thumbUrl != null -> Thumbnails.request(info.id, thumbUrl)
                // Only real YouTube ids derive a thumbnail; a custom link or a metadata-less platform
                // card has none, and deriving a ytimg URL from its URL-shaped id would fetch nonsense.
                info.isYouTubeResult -> Thumbnails.request(info.id, Thumbnails.Quality.LOW)
            }
        }
    }

    /**
     * The playable URL behind [source] when it is a custom link (a direct media file, or any other
     * remote page the extractor chain may know), or null when it is not a URL at all - which is how
     * a plain search phrase stays a search phrase.
     */
    private fun customUrlOf(source: MediaSource): String? = when (source) {
        is MediaSource.DirectStream -> source.streamUrl
        is MediaSource.Remote -> CustomMediaUrls.normalize(source.url)
        else -> null
    }

    /** The single card shown for a pasted link. Built purely from the URL: file name as the title, host as the uploader. */
    private fun customResult(url: String): MediaSearchResult = MediaSearchResult(
        id = url,
        title = CustomMediaUrls.displayName(url),
        uploader = CustomMediaUrls.hostOf(url),
        durationSec = null,
        viewCount = null,
        watchUrlOverride = url,
        isCustom = true,
    )

    /** Minimal result used when URL metadata could not be fetched. */
    private fun fallbackResult(videoId: String) =
        MediaSearchResult(videoId, YouTubeUrls.watchUrl(videoId), null, null, null)

    /** Builds a single-card result for a pasted Twitch URL, using [meta] when the Helix lookup succeeded. */
    private fun twitchResult(source: MediaSource.Twitch, meta: TwitchMetadata?): MediaSearchResult {
        val id = TwitchMetadataCache.cacheKey(source) ?: source.url
        val fallbackTitle = source.channel ?: source.videoId ?: source.clipSlug ?: source.url
        return MediaSearchResult(
            id = id,
            title = meta?.title ?: fallbackTitle,
            uploader = meta?.channelName,
            durationSec = null,
            viewCount = meta?.viewCount,
            watchUrlOverride = source.url,
            thumbnailUrlOverride = meta?.thumbnailUrl,
            isTwitch = true,
            isLive = meta?.isLive ?: false,
            platform = MediaPlatform.TWITCH,
        )
    }

    /**
     * Builds a single-card result for a pasted Vimeo / Kick link. The card is keyed by the watch URL
     * (unlike Twitch, whose id is a cache key) so its thumbnail slot never collides with a YouTube id;
     * [meta] fills in the title / uploader / thumbnail when the metadata lookup succeeded.
     */
    private fun platformResult(
        url: String,
        platform: MediaPlatform,
        meta: PlatformVideoMetadata?,
        fallbackTitle: String,
    ): MediaSearchResult = MediaSearchResult(
        id = url,
        title = meta?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
        uploader = meta?.uploader,
        durationSec = meta?.durationSec,
        viewCount = meta?.viewCount,
        watchUrlOverride = url,
        thumbnailUrlOverride = meta?.thumbnailUrl,
        isLive = meta?.isLive ?: false,
        platform = platform,
    )

    companion object {
        /** Results fetched per page; the panel loads another page as the user scrolls near the end. */
        const val PAGE_SIZE = 15

        /** Translation kes for the status line. */
        private const val KEY_LOADING = "dreamdisplayx.suggestions.loading"

        /** Translation key for the error status line. */
        private const val KEY_ERROR = "dreamdisplayx.suggestions.error"

        /** Translation key for the empty status line. */
        private const val KEY_EMPTY = "dreamdisplayx.suggestions.empty"

        /** Translation key shown when the player has not saved any custom links yet. */
        private const val KEY_NO_LINKS = "dreamdisplayx.suggestions.no_links"

        /** Logger. */
        private val logger = LoggerFactory.getLogger("DreamDisplaysX/Suggestions")

        /** Twitch login shape: letters, digits, underscore, 3-25 chars (real handles never contain spaces). */
        private val TWITCH_LOGIN_RE = Regex("^[A-Za-z0-9_]{3,25}$")

        /** Matches any Han-script character (CJK Unified Ideographs); the sole signal for "this query is in Chinese" ([looksChinese]). */
        private val CHINESE_CHAR_RE = Regex("[一-鿿]")

        /** Anything that isn't a letter/digit/space, stripped when building a [twitchSearchFragment]. */
        private val NON_WORD_RE = Regex("[^\\p{L}\\p{N}\\s]")

        /** Generic words that defeat Twitch's channel-name matcher when left in a [twitchSearchFragment]. */
        private val TWITCH_SEARCH_NOISE_WORDS = setOf(
            "stream", "streaming", "streamer", "live", "gameplay", "gaming", "playing",
            "highlights", "clips", "clip", "vod", "vods", "channel", "twitch",
        )

        /** Max distinct uploader names scanned for a fuzzy Twitch-login match, so a big result page stays cheap. */
        private const val FUZZY_CANDIDATE_LIMIT = 8

        /** Items exposed per scroll-triggered "load more" for Bilibili searches. */
        private const val BILIBILI_PAGE_SIZE = 20

        /** Max BIlibili search hits mixed into a single results page — generous, since BIlibili only ever
         *  competes at all on a Chinese query ([looksChinese]), where it should show up strongly. */
        private const val BILIBILI_RESULT_CAP = 10

        /** Only BIlibili hits with at least this many views survive the popularity filter in [runSearch]. */
        private const val BILIBILI_MIN_VIEWS = 50_000L

        /** Max Twitch channel hits mixed in; channel cards are heavier (whole-channel, not a single video) than a VOD card. */
        private const val TWITCH_RESULT_CAP = 4

        /** Tolerance for comparing [weightedInterleave]'s fractional "how due" scores, avoiding float-equality flakiness. */
        private const val FRACTION_EPSILON = 1e-9

        /** [weightedInterleave] share YouTube is entitled to against Bilibili. */
        private const val YOUTUBE_WEIGHT = 0.8

        /**
         * BIlibili's share in [weightedInterleave]. It only ever competes at all on a Chinese query
         * ([looksChinese]) — and on those, it should show up strongly rather than as an afterthought.
         */
        private const val BILIBILI_WEIGHT = 0.6

        /** Twitch's share of the final [weightedInterleave] pass — YouTube should dominate, Twitch pop up rarely. */
        private const val TWITCH_WEIGHT = 0.12
    }
}
