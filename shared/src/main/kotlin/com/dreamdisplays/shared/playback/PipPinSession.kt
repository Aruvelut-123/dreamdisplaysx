package com.dreamdisplays.shared.playback

import kotlinx.serialization.Serializable

/**
 * Persisted record of one player pinning one display to their `Picture-in-Picture` overlay.
 */
@Serializable
data class PipPinSession(
    val playerId: String,
    val displayId: String,
)
