package com.dreamdisplayx.api.media.source.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.stream.model.MediaStream

/**
 * Fully resolved media: candidate streams plus metadata and timeline capabilities.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
data class ResolvedMedia(
    /** All playable streams returned by the resolver. */
    val streams: List<MediaStream>,

    /** Best metadata known for the resolved source. */
    val metadata: MediaMetadata,

    /** True for live streams where duration may be unknown and seeking may be restricted. */
    val isLive: Boolean,

    /** True when playback may seek within the media timeline. */
    val isSeekable: Boolean,

    /** Subtitle renditions discovered by a resolver, keyed by a stable track identity. */
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
) {
    /** Streams that contain video. */
    val videoStreams: List<MediaStream> get() = streams.filter { it.type.hasVideo }

    /** Streams that contain audio. */
    val audioStreams: List<MediaStream> get() = streams.filter { it.type.hasAudio }

    /** True when any stream contains video. */
    val hasVideo: Boolean get() = videoStreams.isNotEmpty()

    /** True when any stream contains audio. */
    val hasAudio: Boolean get() = audioStreams.isNotEmpty()

}

/** One selectable subtitle rendition; the player may render it or combine two tracks. */
@DreamDisplaysXUnstableApi
data class SubtitleTrack(
    val url: String,
    val language: String? = null,
    val name: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
) {
    /** Stable across signed-URL refreshes and distinct for same-language named/forced renditions. */
    val selectionKey: String
        get() = if (!language.isNullOrBlank() || !name.isNullOrBlank())
            "track:${language.orEmpty().lowercase(java.util.Locale.ROOT)}|${name.orEmpty()}|$isForced"
        else "url:" + url.substringBefore('?').substringBefore('#')

    /** Accepts old language-only preferences for backwards-compatible settings restores. */
    fun matchesPreference(value: String): Boolean = selectionKey == value ||
        language?.equals(value, ignoreCase = true) == true || name?.equals(value, ignoreCase = true) == true
}
