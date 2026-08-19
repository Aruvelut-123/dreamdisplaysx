package com.dreamdisplayx.platform.client.core

import com.dreamdisplayx.api.platform.identity.Platform
import com.dreamdisplayx.api.runtime.registry.service.ServiceRegistry
import com.dreamdisplayx.platform.client.managers.ClientStateManager

/**
 * Default [ClientContext]: the process-wide [DreamServices.registry] and [ClientStateManager],
 * bound to the loader-specific [Platform] the entrypoint registered.
 */
class DefaultClientContext(override val platform: Platform) : ClientContext {
    /** The [ClientMutableState] instance for this context. */
    override val state: ClientMutableState = ClientStateManager

    /** The [ServiceRegistry] instance for this context. */
    override val services: ServiceRegistry = DreamServices.registry
}
