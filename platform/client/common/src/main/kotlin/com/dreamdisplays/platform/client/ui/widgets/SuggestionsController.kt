package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.core.services.keys.MediaServices
import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.api.media.source.YouTubeUrls
import com.dreamdisplays.api.media.source.CustomMediaUrls
import com.dreamdisplays.api.media.source.MediaPlatform
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.source.bilibili.BilibiliApi
import com.dreamdisplays.media.source.bilibili.BilibiliMetadataCache
import com.dreamdisplays.media.source.bilibili.BilibiliSearchItem
import com.dreamdisplays.media.source.kick.KickMetadataCache
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata
import com.dreamdisplays.media.source.twitch.TwitchApi
import com.dreamdisplays.media.source.twitch.TwitchMetadata
import com.dreamdisplays.media.source.twitch.TwitchMetadataCache
import com.dreamdisplays.media.source.twitch.TwitchSearchItem
import com.dreamdisplays.media.source.vimeo.VimeoMetadataCache
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.render.Thumbnails
import com.dreamdisplays.platform.client.storage.CustomVideoStore
import com.dreamdisplays.platform.client.storage.WatchedVideoStore
import com.dreamdisplays.util.DreamCoroutines
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

    private var loadStatusKey: String? = null

    /** Wall-clock start of the in-flight load, for the elapsed-seconds suffix on the loading message. */
    var loadStartedAtMs: Long = 0L; private set

    /** Currently selected sort/filter; see [setSort]. */
    var sortOption: SortOption = SortOption.RELEVANCE; private set

    private val requestSeq = atomic(0)
    private var currentVideoId: String? = null
    private var lastQuery: String? = null
    private var moreMode: MoreMode? = null
    private var continuationToken: String? = null
    private var loadingMore = false

    /** Reload hook the panel uses to reset scroll when new results land. */
    var onResults: () -> Unit = {}

    /** True while the status line is the animated loading message. */
    val isLoading: Boolean get() = loadStatusKey == KEY_LOADING

    /** True while a background "load more" request is in flight (distinct from the initial [isLoading]). */
    val isLoadingMore: Boolean get() = loadingMore

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
     * Runs a free-text or URL search for [query]; an empty query falls back to the current related list.
     * URL queries resolve directly instead of searching.
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
        val svc = DreamServices.registry.get(MediaServices.SEARCH)
        val maybeId = YouTubeUrls.extractVideoId(q)
        val source = MediaSource.from(q)

        // A direct / long-tail link needs no network to show its card, so publish it synchronously.
        // Platform links (Twitch / Vimeo / Kick / Bilibili) instead resolve real metadata below.
        if (maybeId == null && source !is MediaSource.Twitch &&
            source !is MediaSource.Vimeo && source !is MediaSource.Kick && source !is MediaSource.Bilibili
        ) {
            customUrlOf(source)?.let {
                publish(seq, listOf(customResult(it)), null)
                return
            }
        }

        launchLoad {
            val results = runCatching {
                when {
                    maybeId != null -> {
                        val meta = runCatching { svc.metadata(maybeId) }
                            .onFailure { if (it is CancellationException) throw it; logger.warn("URL meta: ${it.message}") }
                            .getOrNull()
                        listOf(meta ?: fallbackResult(maybeId))
                    }

                    source is MediaSource.Twitch -> {
                        val meta = runCatching {
                            withContext(Dispatchers.IO) { TwitchMetadataCache.resolveBlocking(source) }
                        }
                            .onFailure { if (it is CancellationException) throw it; logger.warn("Twitch meta: ${it.message}") }
                            .getOrNull()
                        listOf(twitchResult(source, meta))
                    }

                    source is MediaSource.Vimeo -> {
                        val meta = runCatching {
                            withContext(Dispatchers.IO) { VimeoMetadataCache.resolveBlocking(source) }
                        }
                            .onFailure { if (it is CancellationException) throw it; logger.warn("Vimeo meta: ${it.message}") }
                            .getOrNull()
                        listOf(
                            platformResult(
                                source.url,
                                MediaPlatform.VIMEO,
                                meta,
                                fallbackTitle = "Vimeo ${source.videoId}"
                            )
                        )
                    }

                    source is MediaSource.Kick -> {
                        val meta = runCatching {
                            withContext(Dispatchers.IO) { KickMetadataCache.resolveBlocking(source) }
                        }
                            .onFailure { if (it is CancellationException) throw it; logger.warn("Kick meta: ${it.message}") }
                            .getOrNull()
                        val fallback = source.channel ?: source.videoUuid ?: "Kick"
                        listOf(platformResult(source.url, MediaPlatform.KICK, meta, fallbackTitle = fallback))
                    }

                    source is MediaSource.Bilibili -> {
                        val meta = runCatching {
                            withContext(Dispatchers.IO) { BilibiliMetadataCache.resolveBlocking(source) }
                        }
                            .onFailure {
                                if (it is CancellationException) throw it
                                logger.warn("Bilibili meta: ${it.message}.")
                            }
                            .getOrNull()
                        val fallback = source.bvid ?: source.avid?.let { "av$it" } ?: source.roomId?.toString() ?: "Bilibili"
                        listOf(platformResult(source.url, MediaPlatform.BILIBILI, meta, fallbackTitle = fallback))
                    }

                    else -> {
                        val twitchLogin = twitchLoginCandidate(q)

                        val ytDeferred = async {
                            runCatching { svc.searchPage(q, PAGE_SIZE, sortOption.networkSort) }
                                .onFailure { if (it is CancellationException) throw it; logger.warn("Search failed: ${it.message}") }
                                .getOrNull()
                        }

                        val twitchDeferred = async {
                            twitchLogin?.let { runCatching { liveTwitchResult(it) }.getOrNull() }
                        }

                        // Bilibili is overwhelmingly Chinese-language content; only worth searching (and even
                        // then, only its truly popular hits) when the player is typing Chinese themselves.
                        val bilibiliDeferred = if (looksChinese(q)) {
                            async {
                                runCatching { withContext(Dispatchers.IO) { BilibiliApi.searchVideos(q) } }
                                    .onFailure { if (it is CancellationException) throw it; logger.debug("Bilibili search failed: ${it.message}") }
                                    .getOrNull()
                                    ?.filter { (it.viewCount ?: 0L) >= BILIBILI_MIN_VIEWS }
                                    ?.map(::bilibiliSearchResult)
                            }
                        } else null
                        val twitchSearchDeferred = async {
                            runCatching {
                                withContext(Dispatchers.IO) { TwitchApi.searchChannels(twitchSearchFragment(q)) }
                            }
                                .onFailure { if (it is CancellationException) throw it; logger.debug("Twitch search failed: ${it.message}") }
                                .getOrNull()
                                ?.map(::twitchSearchResult)
                        }

                        val youtubePage = ytDeferred.await()
                        val youtubeResults = youtubePage?.results
                        var liveTwitch = twitchDeferred.await()
                        val bilibiliResults = bilibiliDeferred?.await()
                        val twitchSearchResults = twitchSearchDeferred.await()

                        if (liveTwitch == null) {
                            val fuzzyLogin = fuzzyTwitchLogin(q, youtubeResults)
                            if (fuzzyLogin != null && fuzzyLogin != twitchLogin) {
                                liveTwitch = runCatching { liveTwitchResult(fuzzyLogin) }.getOrNull()
                            }
                        }

                        if (youtubeResults == null && liveTwitch == null &&
                            bilibiliResults.isNullOrEmpty() && twitchSearchResults.isNullOrEmpty()
                        ) {
                            publish(seq, null, KEY_ERROR)
                            return@launchLoad
                        }

                        // YouTube stays the dominant source (~80%). Bilibili (gated to Chinese queries and
                        // popular-only hits above) gets a strong share once it's competing at all, since a
                        // Chinese query is exactly where it's most useful. Kick is not mixed in here at all
                        // anymore. Twitch keeps its own unweighted mix-in below, untouched by this ratio.
                        val youtubeAndMinor = weightedInterleave(
                            listOf(
                                youtubeResults.orEmpty() to YOUTUBE_WEIGHT,
                                bilibiliResults.orEmpty().take(BILIBILI_RESULT_CAP) to BILIBILI_WEIGHT,
                            )
                        )
                        // Twitch channel hits get folded in at a small weight too, same idea as Bilibili
                        // above: YouTube should stay the thing you mostly see, Twitch an occasional find.
                        val onDemand = weightedInterleave(
                            listOf(
                                youtubeAndMinor to (1.0 - TWITCH_WEIGHT),
                                twitchSearchResults.orEmpty().take(TWITCH_RESULT_CAP) to TWITCH_WEIGHT,
                            )
                        )
                        val combined = ArrayList<MediaSearchResult>(1 + onDemand.size).apply {
                            liveTwitch?.let(::add)
                            addAll(onDemand)
                        }
                        publish(
                            seq,
                            combined,
                            null,
                            nextToken = youtubePage?.continuationToken,
                            mode = MoreMode.Search(q)
                        )
                        return@launchLoad
                    }
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                publish(seq, null, KEY_ERROR)
            }.getOrNull()

            results?.let { publish(seq, it, null) }
        }
    }

    private fun looksChinese(query: String): Boolean = CHINESE_CHAR_RE.containsMatchIn(query)

    private fun twitchLoginCandidate(query: String): String? {
        val q = query.trim()
        return if (TWITCH_LOGIN_RE.matches(q)) q.lowercase() else null
    }

    private fun twitchSearchFragment(query: String): String {
        val cleaned = query.replace(NON_WORD_RE, " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && !it.lowercase().isNoiseWord() }
            .joinToString(" ")
        return cleaned.ifBlank { query }
    }

    private fun String.isNoiseWord(): Boolean =
        this in TWITCH_SEARCH_NOISE_WORDS || removeSuffix("s") in TWITCH_SEARCH_NOISE_WORDS

    private fun liveTwitchResult(login: String): MediaSearchResult? = runCatching {
        TwitchApi.queryChannel(login)?.takeIf { it.isLive }?.let { meta ->
            twitchResult(MediaSource.Twitch(url = "https://www.twitch.tv/$login", channel = login), meta)
        }
    }.onFailure { e ->
        logger.debug("Twitch live-channel lookup failed for '$login': ${e.message}.")
    }.getOrNull()

    private fun bilibiliSearchResult(item: BilibiliSearchItem): MediaSearchResult {
        val url = "https://www.bilibili.com/video/${item.bvid}"
        return MediaSearchResult(
            id = url,
            title = item.title,
            uploader = item.uploader,
            durationSec = item.durationSec,
            viewCount = item.viewCount,
            watchUrlOverride = url,
            thumbnailUrlOverride = item.thumbnailUrl,
            platform = MediaPlatform.BILIBILI,
        )
    }

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

    private fun toLoginShape(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(25)

    private fun fuzzyThreshold(length: Int): Int = when {
        length <= 2 -> 0
        length <= 5 -> 1
        else -> 2
    }

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
     * a page isn't already in flight, and the list isn't already exhausted. Called by the panel as the
     * user scrolls near the end of the currently loaded cards; safe to call every frame.
     */
    fun loadMoreIfNeeded() {
        if (loadingMore || isLoading) return
        // The local link list is complete by definition; there is no page two to fetch
        if (sortOption.isOwnList) return
        val mode = moreMode ?: return
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

    private fun launchLoad(block: suspend CoroutineScope.() -> Unit) =
        DreamCoroutines.clientIo.launch(block = block)

    private fun startLoad() {
        loadStatusKey = KEY_LOADING
        loadStartedAtMs = System.currentTimeMillis()
        cards.clear()
        moreMode = null
        continuationToken = null
        onResults()
    }

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

    private fun customUrlOf(source: MediaSource): String? = when (source) {
        is MediaSource.DirectStream -> source.streamUrl
        is MediaSource.Remote -> CustomMediaUrls.normalize(source.url)
        else -> null
    }

    private fun customResult(url: String): MediaSearchResult = MediaSearchResult(
        id = url,
        title = CustomMediaUrls.displayName(url),
        uploader = CustomMediaUrls.hostOf(url),
        durationSec = null,
        viewCount = null,
        watchUrlOverride = url,
        isCustom = true,
    )

    private fun fallbackResult(videoId: String) =
        MediaSearchResult(videoId, YouTubeUrls.watchUrl(videoId), null, null, null)

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

        private const val KEY_LOADING = "dreamdisplays.suggestions.loading"
        private const val KEY_ERROR = "dreamdisplays.suggestions.error"
        private const val KEY_EMPTY = "dreamdisplays.suggestions.empty"
        private const val KEY_NO_LINKS = "dreamdisplays.suggestions.no_links"
        private val logger = LoggerFactory.getLogger(javaClass)
        private val TWITCH_LOGIN_RE = Regex("^[A-Za-z0-9_]{3,25}$")
        private val CHINESE_CHAR_RE = Regex("[一-鿿]")
        private val NON_WORD_RE = Regex("[^\\p{L}\\p{N}\\s]")

        private val TWITCH_SEARCH_NOISE_WORDS = setOf(
            "stream", "streaming", "streamer", "live", "gameplay", "gaming", "playing",
            "highlights", "clips", "clip", "vod", "vods", "channel", "twitch",
        )

        private const val FUZZY_CANDIDATE_LIMIT = 8
        private const val BILIBILI_RESULT_CAP = 10
        private const val BILIBILI_MIN_VIEWS = 50_000L
        private const val TWITCH_RESULT_CAP = 4
        private const val FRACTION_EPSILON = 1e-9
        private const val YOUTUBE_WEIGHT = 0.8
        private const val BILIBILI_WEIGHT = 0.6
        private const val TWITCH_WEIGHT = 0.12
    }
}
