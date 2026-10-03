package com.dreamdisplayx.media.source

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.search.model.MediaSearchResult
import com.dreamdisplayx.api.media.source.model.MediaPlatform
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.media.source.url.CustomMediaUrls
import com.dreamdisplayx.api.media.source.url.YouTubeUrls
import com.dreamdisplayx.api.security.policy.MediaUrlPolicy
import com.dreamdisplayx.media.source.bilibili.BilibiliMetadataCache
import com.dreamdisplayx.media.source.platform.PlatformVideoMetadata
import com.dreamdisplayx.media.source.twitch.TwitchMetadata
import com.dreamdisplayx.media.source.twitch.TwitchMetadataCache

/**
 * Pure URL → single-card mapping for the search box's "paste a link, play it" flow.
 *
 * Given a pasted media link (or bare YouTube id), builds the single [MediaSearchResult] card shown
 * instead of text-searching the URL string; returns null when the input is a plain search phrase.
 * Anything [MediaUrlPolicy] rejects stays a search phrase. Matching is keyword-free so a URL
 * containing search terms is never half-search / half-card.
 *
 * Kept free of Minecraft / client types so the mapping is unit-testable on the JVM.
 */
@DreamDisplaysXUnstableApi
object PastedMediaCards {

    /**
     * Recognizes [query] as a pasted media link and builds its single-card result, or returns null
     * for a plain search phrase.
     */
    fun fromQuery(query: String): MediaSearchResult? {
        if (query.isBlank()) return null
        if (!MediaUrlPolicy.isAllowed(query)) return null
        val source = MediaSource.from(query)
        return when (source) {
            is MediaSource.YouTube -> fallbackResult(source.videoId)
            is MediaSource.Twitch -> twitchResult(source, null)
            is MediaSource.Vimeo -> platformResult(
                source.url, MediaPlatform.VIMEO, null, CustomMediaUrls.displayName(source.url),
            )
            is MediaSource.Kick -> platformResult(
                source.url, MediaPlatform.KICK, null, CustomMediaUrls.displayName(source.url),
            )
            // Bilibili URLs keep their platform badge; bangumi / episode / live URLs stay playable as-is.
            // Any metadata the cache already holds (a previously played / resolved link) fills in a real
            // title and thumbnail right away; the client enriches the card in the background otherwise.
            is MediaSource.Bilibili -> bilibiliResult(
                source,
                BilibiliMetadataCache.cacheKey(source)?.let(BilibiliMetadataCache::get),
            )
            is MediaSource.DirectStream -> customResult(source.streamUrl)
            is MediaSource.Remote -> customResult(source.url)
            is MediaSource.Ingest -> customResult(source.url)
        }
    }

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

    /**
     * Builds a single-card result for a pasted Bilibili link. [meta] fills in the title / uploader /
     * thumbnail when available (a cached or just-resolved entry); the card falls back to the URL's
     * display name so the link stays visible even before metadata lands.
     */
    fun bilibiliResult(source: MediaSource.Bilibili, meta: PlatformVideoMetadata?): MediaSearchResult =
        MediaSearchResult(
            id = source.url,
            title = meta?.title?.takeIf { it.isNotBlank() } ?: CustomMediaUrls.displayName(source.url),
            uploader = meta?.uploader,
            durationSec = meta?.durationSec,
            viewCount = meta?.viewCount,
            watchUrlOverride = source.url,
            thumbnailUrlOverride = meta?.thumbnailUrl,
            isLive = meta?.isLive ?: false,
            platform = MediaPlatform.BILIBILI,
        )

    /** The single card shown for a pasted link. Built purely from the URL: file name as the title, host as the uploader. */
    private fun customResult(url: String, platform: MediaPlatform = MediaPlatform.YOUTUBE): MediaSearchResult =
        MediaSearchResult(
            id = url,
            title = CustomMediaUrls.displayName(url),
            uploader = CustomMediaUrls.hostOf(url),
            durationSec = null,
            viewCount = null,
            watchUrlOverride = url,
            isCustom = true,
            platform = platform,
        )
}
