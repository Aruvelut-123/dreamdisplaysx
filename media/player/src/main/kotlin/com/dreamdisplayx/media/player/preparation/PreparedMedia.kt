package com.dreamdisplayx.media.player.preparation

import com.dreamdisplayx.media.player.stream.ActiveStreams

/**
 * Result returned by [MediaPreparationService.prepare] on success.
 * Contains everything needed to start playback.
 */
data class PreparedMedia(
    val streamSet: ActiveStreams,
    val isLive: Boolean,
    val isSeekable: Boolean,
    val durationNanos: Long,
)
