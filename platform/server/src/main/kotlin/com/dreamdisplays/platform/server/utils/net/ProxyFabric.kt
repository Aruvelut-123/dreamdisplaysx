package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.platform.client.net.ProxyPayload
import com.dreamdisplays.platform.server.proxy.VanillaProxyBridge
import io.github.arnodoelinger.platformweaver.FabricOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import org.slf4j.LoggerFactory

/**
 * `dreamdisplays:proxy` networking for the `Fabric` flavor. Mirrors [FabricV2Networking]'s shape,
 * but for the backend <-> proxy channel instead of the player-facing v2 one.
 */
@FabricOnly
object FabricProxyNetworking {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Registers the single proxy envelope receiver. */
    fun registerReceivers() {
        //? if >=1.20.5 {
        ServerPlayNetworking.registerGlobalReceiver(ProxyPayload.TYPE) { payload, context ->
            runCatching {
                VanillaProxyBridge.onMessage(context.player(), context.server(), payload.bytes)
            }.onFailure { e -> logger.warn("Failed to handle proxy packet.", e) }
        }
        //?} else
        /*ServerPlayNetworking.registerGlobalReceiver(ProxyPayload.TYPE.id()) { server, player, _, buf, _ ->
            val bytes = ByteArray(buf.readableBytes()).also(buf::readBytes)
            server.execute {
                runCatching { VanillaProxyBridge.onMessage(player, server, bytes) }
                    .onFailure { e -> logger.warn("Failed to handle proxy packet.", e) }
            }
        }*/
    }
}
