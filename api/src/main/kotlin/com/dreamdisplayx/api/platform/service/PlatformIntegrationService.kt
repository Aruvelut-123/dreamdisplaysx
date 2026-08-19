package com.dreamdisplayx.api.platform.service

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.platform.identity.Platform

/**
 * Supplies the [Platform] adapter.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
fun interface PlatformIntegrationService {
    /** Creates the platform adapter instance. */
    fun create(): Platform
}
