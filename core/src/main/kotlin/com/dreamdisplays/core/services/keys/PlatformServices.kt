package com.dreamdisplays.core.services.keys

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.core.runtime.ServiceKey

/** Platform service keys. */
@Unstable
object PlatformServices {
    /** Platform service. */
    val PLATFORM: ServiceKey<Platform> =
        ServiceKey("dreamdisplays:platform", Platform::class.java)
}
