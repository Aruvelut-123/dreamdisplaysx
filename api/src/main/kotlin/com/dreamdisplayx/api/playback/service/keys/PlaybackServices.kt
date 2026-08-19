package com.dreamdisplayx.api.playback.service.keys

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.playback.service.PlaybackService
import com.dreamdisplayx.api.runtime.registry.model.ServiceKey
import com.dreamdisplayx.api.runtime.registry.model.serviceKey

/**
 * Playback service keys.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
object PlaybackServices {
    /** Public display playback command surface. */
    val PLAYBACK: ServiceKey<PlaybackService> = serviceKey("dreamdisplayx:playback")
}
