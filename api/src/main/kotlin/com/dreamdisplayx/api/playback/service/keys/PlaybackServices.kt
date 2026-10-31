package com.dreamdisplayx.api.playback.service.keys

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.playback.service.PlaybackService
import com.dreamdisplayx.api.runtime.registry.model.ServiceKey

/**
 * Playback service keys.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
object PlaybackServices {
    /** Public display playback command surface. */
    val PLAYBACK: ServiceKey<PlaybackService> =
        ServiceKey("dreamdisplayx:playback", PlaybackService::class.java)
}
