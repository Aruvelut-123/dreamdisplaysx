package com.dreamdisplayx.api.media.source.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.source.url.YouTubeUrls
import com.dreamdisplayx.api.media.source.url.BilibiliUrls
import com.dreamdisplayx.api.media.source.url.CustomMediaUrls
import com.dreamdisplayx.api.media.source.url.KickUrls
import com.dreamdisplayx.api.media.source.url.VimeoUrls
import com.dreamdisplayx.api.security.model.MediaHttpUrl
import java.util.*

/**
 * User-provided media locator before resolver-specific stream extraction.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
sealed interface MediaSource {
    /** Generic remote URL, passed through to the resolver pipeline. */
    data class Remote(val url: String) : MediaSource

    /** YouTube video identified by its 11-character id. */
    data class YouTube(val videoId: String) : MediaSource

    /** Twitch source: live channel, VOD, or clip. Exactly one of [channel] / [videoId] / [clipSlug] is set. */
    data class Twitch(
        val url: String,
        val channel: String? = null,
        val videoId: String? = null,
        val clipSlug: String? = null,
    ) : MediaSource

    /** Vimeo video identified by [videoId] and optional unlisted-video [hash] for authorization. */
    data class Vimeo(
        val url: String,
        val videoId: String,
        val hash: String? = null,
    ) : MediaSource

    /** Kick source: live channel or VOD. [channel] for live, [videoUuid] for VOD. */
    data class Kick(
        val url: String,
        val channel: String? = null,
        val videoUuid: String? = null,
    ) : MediaSource

    /**
     * BIlibili source: a VOD ([bvid] or legacy [avid], with optional multipart [part]), a live
     * [roomId], or a bangumi/movie episode ([epId]) or season ([seasonId]). An unresolved `b23.tv`
     * short link carries none of these — the resolver follows the redirect itself, since that needs
     * a network call this synchronous parser cannot make.
     */
    data class Bilibili(
        val url: String,
        val bvid: String? = null,
        val avid: Long? = null,
        val part: Int? = null,
        val roomId: Long? = null,
        val epId: Long? = null,
        val seasonId: Long? = null,
    ) : MediaSource

    /** Direct playable stream URL or manifest; [kind] records what [com.dreamdisplayx.api.media.source.url.CustomMediaUrls] recognized. */
    data class DirectStream(
        val streamUrl: String,
        val kind: CustomMediaKind = CustomMediaKind.PROGRESSIVE,
    ) : MediaSource

    /**
     * A live ingest endpoint (`rtmp://`, `rtmps://`, or `srt://`) that a client pushes to — screen
     * sharing / casting (OBS-style). The stream is live and never seekable.
     */
    data class Ingest(
        val url: String,
    ) : MediaSource

    /** Which service this source belongs to, for UI badging and metadata routing. */
    val platform: MediaPlatform
        get() = when (this) {
            is YouTube -> MediaPlatform.YOUTUBE
            is Twitch -> MediaPlatform.TWITCH
            is Vimeo -> MediaPlatform.VIMEO
            is Kick -> MediaPlatform.KICK
            is Bilibili -> MediaPlatform.BILIBILI
            is DirectStream -> MediaPlatform.DIRECT
            is Ingest -> MediaPlatform.OTHER
            is Remote -> MediaPlatform.OTHER
        }

    /** Returns the HTTP(S) URL a resolver can feed to `yt-dlp` / `NewPipeExtractor`. */
    fun toResolvableUrl(): String? = when (this) {
        is YouTube -> YouTubeUrls.watchUrl(videoId)
        is Remote -> url
        is DirectStream -> streamUrl
        is Ingest -> url
        is Twitch -> url
        is Vimeo -> url
        is Kick -> url
        is Bilibili -> url
    }

    companion object {
        /**
         * Parses [url] into a typed source: platform hosts first, then [com.dreamdisplayx.api.media.source.url.CustomMediaUrls] for direct streams, else [Remote].
         */
        fun from(url: String): MediaSource {
            YouTubeUrls.extractVideoId(url)?.let { return YouTube(it) }

            // Live ingest endpoints (screen sharing / casting) are not HTTP(S) media: recognize
            // them before the http-only normalization below so they keep their scheme.
            val trimmed = url.trim()
            if (CustomMediaUrls.isIngest(trimmed)) return Ingest(trimmed)

            val parsed = MediaHttpUrl.parse(url) ?: MediaHttpUrl.parse("https://${url.trim()}")
            val host = parsed?.uri?.host?.lowercase(Locale.ROOT)
            if (parsed != null && (host == "twitch.tv" || host?.endsWith(".twitch.tv") == true)) {
                // Store the scheme-normalized URL, never the raw paste: a scheme-less "twitch.tv/x"
                // would otherwise be rejected by the server's http(s)-only URL policy.
                val twitchUrl = parsed.value
                val segments = parsed.uri.path?.split('/')?.filter { it.isNotBlank() } ?: emptyList()
                when {
                    host == "clips.twitch.tv" && segments.isNotEmpty() ->
                        return Twitch(twitchUrl, clipSlug = segments[0])

                    segments.getOrNull(0) == "videos" && segments.size > 1 ->
                        return Twitch(twitchUrl, videoId = segments[1])

                    segments.isNotEmpty() -> return Twitch(twitchUrl, channel = segments[0])
                }
            }

            VimeoUrls.parse(url)?.let { return it }
            KickUrls.parse(url)?.let { return it }
            BilibiliUrls.parse(url)?.let { return it }

            val normalized = CustomMediaUrls.normalize(url)
            if (normalized != null) {
                val kind = CustomMediaUrls.classify(normalized)
                if (kind.isDirect) return DirectStream(normalized, kind)
                return Remote(normalized)
            }

            return Remote(url)
        }
    }
}
