package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.core.runtime.DreamDisplaysModule
import com.dreamdisplays.core.runtime.ModuleContext
import com.dreamdisplays.core.runtime.register
import com.dreamdisplays.platform.client.capabilities.CapabilityNegotiationService
import com.dreamdisplays.platform.client.capabilities.ClientCapabilityDetector
import com.dreamdisplays.platform.client.capabilities.DefaultCapabilityNegotiationService
import com.dreamdisplays.platform.client.capabilities.MinecraftClientCapabilityDetector

/** Installs local capability detection and server capability negotiation services. */
object ClientCapabilityModule : DreamDisplaysModule {
    override val id: String = "dreamdisplays:client_capability"

    override fun install(context: ModuleContext) {
        val detector = MinecraftClientCapabilityDetector
        val services = context.services
        services.register<ClientCapabilityDetector>(detector)
        services.register<CapabilityNegotiationService>(DefaultCapabilityNegotiationService(detector))
    }
}
