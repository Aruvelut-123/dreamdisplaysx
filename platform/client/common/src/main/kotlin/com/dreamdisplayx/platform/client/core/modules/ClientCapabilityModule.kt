package com.dreamdisplayx.platform.client.core.modules

import com.dreamdisplayx.api.runtime.module.DreamDisplaysXModule
import com.dreamdisplayx.api.runtime.module.ModuleContext
import com.dreamdisplayx.api.runtime.registry.service.register
import com.dreamdisplayx.platform.client.capabilities.CapabilityNegotiationService
import com.dreamdisplayx.platform.client.capabilities.ClientCapabilityDetector
import com.dreamdisplayx.platform.client.capabilities.DefaultCapabilityNegotiationService
import com.dreamdisplayx.platform.client.capabilities.MinecraftClientCapabilityDetector

/** Installs local capability detection and server capability negotiation services. */
object ClientCapabilityModule : DreamDisplaysXModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplayx:client_capability"

    /** Installs the capability detector and negotiation service. */
    override fun install(context: ModuleContext) {
        val detector = MinecraftClientCapabilityDetector
        val services = context.services
        services.register<ClientCapabilityDetector>(detector)
        services.register<CapabilityNegotiationService>(DefaultCapabilityNegotiationService(detector))
    }
}
