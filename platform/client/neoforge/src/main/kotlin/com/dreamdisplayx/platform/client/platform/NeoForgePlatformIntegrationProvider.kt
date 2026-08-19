package com.dreamdisplayx.platform.client.platform

import com.dreamdisplayx.api.platform.identity.Platform
import com.dreamdisplayx.api.platform.service.PlatformIntegrationService

/** Supplies the `NeoForge` [Platform] adapter. */
object NeoForgePlatformIntegrationProvider : PlatformIntegrationService {
    override fun create(): Platform = NeoForgePlatform
}
