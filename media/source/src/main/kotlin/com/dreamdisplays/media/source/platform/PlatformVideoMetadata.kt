package com.dreamdisplays.media.source.platform

import com.dreamdisplays.api.media.source.model.MediaMetadata

/**
 * Lightweight, provider-agnostic video metadata: the facts the menu needs to draw a card and the preview overlay.
 */
data class PlatformVideoMetadata(
    val title: String?,
    val uploader: String?,
    val thumbnailUrl: String?,
    val uploaderAvatarUrl: String? = null,
    val viewCount: Long? = null,
    val durationSec: Long? = null,
    val isLive: Boolean = false,
) {
    /** Converts to the resolver-facing [MediaMetadata], carrying [durationMs] through unchanged. */
    fun toMediaMetadata(durationMs: Long? = durationSec?.takeIf { it > 0 }?.times(1000L)): MediaMetadata =
        MediaMetadata(
            title = title,
            uploader = uploader,
            durationMs = durationMs,
            thumbnailUrl = thumbnailUrl,
            viewCount = viewCount,
            likeCount = null,
            uploadDate = null,
        )
}
