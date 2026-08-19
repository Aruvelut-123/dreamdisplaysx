package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.platform.client.net.ProxyPayload
import com.dreamdisplayx.platform.server.proxy.VanillaProxyBridge
import io.github.arnodoelinger.platformweaver.FabricOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import org.slf4j.LoggerFactory

/**
 * `dreamdisplayx:proxy` networking for the `Fabric` flavor. Mirrors [FabricV2Networking]'s shape,
 * but for the backend <-> proxy channel instead of the player-facing v2 one.
 */
@FabricOnly
object FabricProxyNetworking {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/FabricProxyNetworking")

    /** Registers the single proxy envelope receiver. */
    fun registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(ProxyPayload.TYPE) { payload, context ->
            runCatching {
                VanillaProxyBridge.onMessage(context.player(), context.server(), payload.bytes)
            }.onFailure { e -> logger.warn("Failed to handle proxy packet.", e) }
        }
    }
}
