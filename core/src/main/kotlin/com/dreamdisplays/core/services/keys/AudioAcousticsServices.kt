package com.dreamdisplays.core.services.keys

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.audio.AudioAcousticsService
import com.dreamdisplays.core.runtime.ServiceKey

/** Acoustics service keys. */
@Unstable
object AudioAcousticsServices {
    /** The single acoustics engine instance for the client. */
    val ACOUSTICS: ServiceKey<AudioAcousticsService> =
        ServiceKey("dreamdisplays:audio_acoustics", AudioAcousticsService::class.java)
}
