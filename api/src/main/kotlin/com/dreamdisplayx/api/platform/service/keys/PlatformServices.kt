package com.dreamdisplayx.api.platform.service.keys

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.platform.identity.Platform
import com.dreamdisplayx.api.runtime.registry.model.ServiceKey

/**
 * Platform service keys.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
object PlatformServices {
    /** Platform service. */
    val PLATFORM: ServiceKey<Platform> =
        ServiceKey("dreamdisplayx:platform", Platform::class.java)
}
