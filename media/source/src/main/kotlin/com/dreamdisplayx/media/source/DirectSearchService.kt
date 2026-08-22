package com.dreamdisplayx.media.source

import com.dreamdisplayx.api.media.search.model.MediaSearchPage
import com.dreamdisplayx.api.media.search.model.MediaSearchResult
import com.dreamdisplayx.api.media.search.model.SortOrder
import com.dreamdisplayx.api.media.search.service.MediaSearchService
import com.dreamdisplayx.api.media.source.model.MediaPlatform
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.media.source.url.BilibiliUrls
import com.dreamdisplayx.api.media.source.url.CustomMediaUrls

/**
 * Lightweight search service that accepts direct URLs and known video IDs (AV/BV for Bilibili).
 *
 * When the user pastes a URL or types a platform video ID, this service resolves it to a single
 * result so the search UI can show the link as a card. No related-video or pagination support.
 */
object DirectSearchService : MediaSearchService {

    /** Accepts the query as-is (single result if it's a URL or video ID). */
    override fun search(query: String, limit: Int): List<MediaSearchResult> {
        val result = resolveQuery(query) ?: return emptyList()
        return listOf(result)
    }

    /** Not supported — returns empty. */
    override fun related(videoId: String, limit: Int): List<MediaSearchResult> = emptyList()

    /** Returns a single-result page when [query] is a URL or video ID. */
    override fun searchPage(query: String, limit: Int, sortOrder: SortOrder): MediaSearchPage {
        val result = resolveQuery(query) ?: return MediaSearchPage(emptyList())
        return MediaSearchPage(listOf(result))
    }

    /** Not supported — returns empty. */
    override fun searchMore(continuationToken: String, limit: Int): MediaSearchPage =
        MediaSearchPage(emptyList())

    /** Not supported — returns empty. */
    override fun relatedPage(videoId: String, limit: Int): MediaSearchPage =
        MediaSearchPage(emptyList())

    /** Not supported — returns empty. */
    override fun relatedMore(continuationToken: String, limit: Int): MediaSearchPage =
        MediaSearchPage(emptyList())

    /**
     * Extracts a video-like identifier from [url]. Supports Bilibili (AV/BV/room), Twitch, Vimeo,
     * Kick. Returns null for unrecognised URLs.
     */
    override fun extractVideoId(url: String): String? {
        if (url.isBlank()) return null
        val source = MediaSource.from(url)
        return when (source) {
            is MediaSource.YouTube -> null
            is MediaSource.Bilibili -> source.bvid ?: source.avid?.toString() ?: source.roomId?.toString()
            is MediaSource.Twitch -> source.channel ?: source.videoId ?: source.clipSlug
            is MediaSource.Vimeo -> source.videoId
            is MediaSource.Kick -> source.channel ?: source.videoUuid
            is MediaSource.DirectStream -> null
            is MediaSource.Remote -> null
            is MediaSource.Ingest -> null
        }
    }

    /** Returns a [MediaSearchResult] for [videoId] if it can be resolved to a known platform. */
    override fun metadata(videoId: String): MediaSearchResult? {
        val url = buildUrl(videoId) ?: return null
        return search(url, 1).firstOrNull()
    }

    /** Resolves a user query (URL or video ID) into a single [MediaSearchResult]. */
    private fun resolveQuery(query: String): MediaSearchResult? {
        if (query.isBlank()) return null
        val source = MediaSource.from(query)
        return when (source) {
            is MediaSource.YouTube -> null

            is MediaSource.Bilibili -> MediaSearchResult(
                id = source.bvid ?: source.avid?.toString() ?: source.roomId?.toString() ?: query,
                title = "Bilibili Video",
                uploader = null,
                durationSec = null,
                viewCount = null,
                platform = MediaPlatform.BILIBILI,
                watchUrlOverride = source.url,
            )

            is MediaSource.Twitch -> MediaSearchResult(
                id = source.channel ?: source.videoId ?: source.clipSlug ?: query,
                title = "Twitch${if (source.channel != null) " / ${source.channel}" else ""}",
                uploader = source.channel,
                durationSec = null,
                viewCount = null,
                isTwitch = true,
                platform = MediaPlatform.TWITCH,
                watchUrlOverride = source.url,
            )

            is MediaSource.Vimeo -> MediaSearchResult(
                id = source.videoId,
                title = "Vimeo Video",
                uploader = null,
                durationSec = null,
                viewCount = null,
                platform = MediaPlatform.VIMEO,
                watchUrlOverride = source.url,
            )

            is MediaSource.Kick -> MediaSearchResult(
                id = source.channel ?: source.videoUuid ?: query,
                title = "Kick${if (source.channel != null) " / ${source.channel}" else ""}",
                uploader = source.channel,
                durationSec = null,
                viewCount = null,
                platform = MediaPlatform.KICK,
                watchUrlOverride = source.url,
            )

            is MediaSource.DirectStream -> MediaSearchResult(
                id = query,
                title = CustomMediaUrls.displayName(query),
                uploader = null,
                durationSec = null,
                viewCount = null,
                isCustom = true,
                platform = MediaPlatform.DIRECT,
                watchUrlOverride = source.streamUrl,
            )

            is MediaSource.Remote -> MediaSearchResult(
                id = query,
                title = CustomMediaUrls.displayName(query),
                uploader = null,
                durationSec = null,
                viewCount = null,
                isCustom = true,
                platform = MediaPlatform.OTHER,
                watchUrlOverride = query,
            )

            is MediaSource.Ingest -> MediaSearchResult(
                id = query,
                title = "Live Ingest",
                uploader = null,
                durationSec = null,
                viewCount = null,
                isLive = true,
                isCustom = true,
                platform = MediaPlatform.OTHER,
                watchUrlOverride = source.url,
            )
        }
    }

    /** Builds a watch URL from a bare video ID, or null when the ID is not recognised. */
    private fun buildUrl(videoId: String): String? = when {
        videoId.startsWith("BV") || videoId.startsWith("bv") -> "https://www.bilibili.com/video/$videoId"
        videoId.startsWith("av") && videoId.length > 2 && videoId.substring(2).all { it.isDigit() } ->
            "https://www.bilibili.com/video/$videoId"
        videoId.contains("://") -> videoId
        else -> null
    }
}