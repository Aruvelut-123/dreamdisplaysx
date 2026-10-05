package com.dreamdisplayx.api.media.stream.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * One playable media track or muxed stream produced by a resolver.
 *
 * @since 1.6.x
 */
@DreamDisplaysXUnstableApi
data class MediaStream(
    /** Direct URL the player can open. */
    val url: String,

    /** Alternate CDN URLs for the same stream, tried in order when the current one fails. */
    val backupUrls: List<String> = emptyList(),

    /** Whether this stream contains video, audio, or both. */
    val type: MediaStreamType,

    /** Codec name, if the resolver exposed it. */
    val codec: String?,

    /** Video width in pixels, or null for audio-only / unknown streams. */
    val width: Int?,

    /** Video height in pixels, or null for audio-only / unknown streams. */
    val height: Int?,

    /** Video frame rate, or null for audio-only / unknown streams. */
    val fps: Double?,

    /** Stream bitrate in bits per second, if known. */
    val bitrate: Int?,

    /** Human-readable audio track name, if this stream carries audio. */
    val audioTrackName: String?,

    /** Audio language code, if this stream carries audio. */
    val audioTrackLang: String?,

    /** True when the provider marks this stream as the default track. */
    val isDefault: Boolean = false,

    /** True when seeking requires decoding from start instead of seeking via demuxer. */
    val seekByDecoding: Boolean = false,
) {
    /** Legacy language/name identity retained for old settings files and callers. */
    val audioIdentity: String? get() = audioTrackLang ?: audioTrackName

    /** Stable key that distinguishes same-language audio renditions when providers expose a name. */
    val audioSelectionKey: String
        get() = if (!audioTrackLang.isNullOrBlank() || !audioTrackName.isNullOrBlank())
            "track:${audioTrackLang.orEmpty().lowercase(java.util.Locale.ROOT)}|${audioTrackName.orEmpty()}"
        else "url:" + url.substringBefore('?').substringBefore('#')

    /** Matches both the new stable key and the old language/name-only preference. */
    fun matchesAudioPreference(value: String): Boolean = audioSelectionKey == value ||
        audioIdentity?.equals(value, ignoreCase = true) == true
}
