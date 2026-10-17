package com.dreamdisplays.api.playback.service.keys

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.playback.service.PlaybackService
import com.dreamdisplays.api.runtime.registry.model.ServiceKey

/**
 * Playback service keys.
 *
 * @since 1.8.x
 */
@Unstable
object PlaybackServices {
    /** Public display playback command surface. */
    val PLAYBACK: ServiceKey<PlaybackService> =
        ServiceKey("dreamdisplays:playback", PlaybackService::class.java)
}
