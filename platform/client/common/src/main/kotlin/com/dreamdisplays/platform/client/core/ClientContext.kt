package com.dreamdisplays.platform.client.core

import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.core.runtime.ModuleContext
import com.dreamdisplays.core.runtime.ServiceRegistry

/**
 * Context for a client application.
 */
interface ClientContext : ModuleContext {
    /** Mutable state of the client, which can be updated by modules and observed by other modules. */
    val state: ClientMutableState

    override val services: ServiceRegistry

    /** Platform-specific APIs and information, which modules can use to interact with the underlying platform. */
    val platform: Platform
}
