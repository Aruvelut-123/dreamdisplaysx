package com.dreamdisplays.platform.client.core

import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.core.runtime.ServiceRegistry
import com.dreamdisplays.platform.client.managers.ClientStateManager

/**
 * Default [ClientContext]: the process-wide [DreamServices.registry] and [ClientStateManager],
 * bound to the loader-specific [Platform] the entrypoint registered.
 */
class DefaultClientContext(override val platform: Platform) : ClientContext {
    override val state: ClientMutableState = ClientStateManager

    override val services: ServiceRegistry = DreamServices.registry
}
