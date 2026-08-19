package com.dreamdisplayx.platform.client.platform

import com.dreamdisplayx.api.platform.identity.Platform
import com.dreamdisplayx.api.platform.service.PlatformIntegrationService

/** Supplies the `Fabric` [Platform] adapter. */
object FabricPlatformIntegrationProvider : PlatformIntegrationService {
    override fun create(): Platform = FabricPlatform
}
