package com.dreamdisplayx.api.runtime.module

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.runtime.registry.service.ServiceRegistry

/**
 * Entry point for services exposed to integrations and modules.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
interface DreamDisplaysXApi {
    /** Contract-typed services available in the current runtime. */
    val services: ServiceRegistry
}
