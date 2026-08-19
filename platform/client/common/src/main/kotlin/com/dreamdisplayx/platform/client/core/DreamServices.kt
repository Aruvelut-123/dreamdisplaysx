package com.dreamdisplayx.platform.client.core

import com.dreamdisplayx.api.runtime.registry.service.ServiceRegistry
import com.dreamdisplayx.core.runtime.DefaultServiceRegistry

/**
 * Process-wide [ServiceRegistry] holder.
 */
object DreamServices {
    /** The shared registry. Services are populated once the client application installs its modules. */
    val registry: ServiceRegistry = DefaultServiceRegistry()
}
