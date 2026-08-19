package com.dreamdisplayx.api.platform.service.keys

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.platform.identity.Platform
import com.dreamdisplayx.api.runtime.registry.model.ServiceKey
import com.dreamdisplayx.api.runtime.registry.model.serviceKey

/**
 * Platform service keys.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
object PlatformServices {
    /** Platform service. */
    val PLATFORM: ServiceKey<Platform> = serviceKey("dreamdisplayx:platform")
}
