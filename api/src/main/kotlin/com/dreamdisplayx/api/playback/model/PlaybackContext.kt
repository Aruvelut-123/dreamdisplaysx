package com.dreamdisplayx.api.playback.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Inputs the permission rules need.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
data class PlaybackContext(
    val mode: PlaybackMode,
    val isOwner: Boolean,
    val isAdmin: Boolean,
    val isLocked: Boolean,
    val hasActiveParty: Boolean = false,
    val isPartyHost: Boolean = false,
)
