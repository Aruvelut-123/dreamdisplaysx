package com.dreamdisplays.core.services.keys

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.core.services.PlaybackService
import com.dreamdisplays.core.runtime.ServiceKey

/** Playback service keys. */
@Unstable
object PlaybackServices {
    /** Public display playback command surface. */
    val PLAYBACK: ServiceKey<PlaybackService> =
        ServiceKey("dreamdisplays:playback", PlaybackService::class.java)
}
