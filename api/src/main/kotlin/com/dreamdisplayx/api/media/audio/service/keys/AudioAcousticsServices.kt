package com.dreamdisplayx.api.media.audio.service.keys

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.audio.service.AudioAcousticsService
import com.dreamdisplayx.api.runtime.registry.model.ServiceKey
import com.dreamdisplayx.api.runtime.registry.model.serviceKey

/**
 * Acoustics service keys.
 *
 * @since 1.9.x
 */
@DreamDisplaysXUnstableApi
object AudioAcousticsServices {
    /** The single acoustics engine instance for the client. */
    val ACOUSTICS: ServiceKey<AudioAcousticsService> = serviceKey("dreamdisplayx:audio_acoustics")
}
