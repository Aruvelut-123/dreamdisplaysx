package com.dreamdisplays.platform.client.platform

import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.shared.platform.PlatformService

/** Supplies the `Fabric` [Platform] adapter. */
object FabricPlatformIntegrationProvider : PlatformService {
    override fun create(): Platform = FabricPlatform
}
