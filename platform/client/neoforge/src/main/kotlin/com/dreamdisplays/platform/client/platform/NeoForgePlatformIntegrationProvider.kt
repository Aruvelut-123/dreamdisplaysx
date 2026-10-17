package com.dreamdisplays.platform.client.platform

import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.shared.platform.PlatformService

/** Supplies the `NeoForge` [Platform] adapter. */
object NeoForgePlatformIntegrationProvider : PlatformService {
    override fun create(): Platform = NeoForgePlatform
}
