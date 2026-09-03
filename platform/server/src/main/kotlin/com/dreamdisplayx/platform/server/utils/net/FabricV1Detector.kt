package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.platform.server.utils.MessageUtil
import io.github.arnodoelinger.platformweaver.FabricOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import java.util.concurrent.ConcurrentHashMap

/** Detects legacy v1 payloads on Fabric without decoding or handling them. */
@FabricOnly
object FabricV1Detector {
    private val notified = ConcurrentHashMap.newKeySet<java.util.UUID>()

    fun register() {
        LegacyV1Payload.CHANNELS.forEach { path ->
            val type = LegacyV1Payload.type(path)
            ServerPlayNetworking.registerGlobalReceiver(type) { _, context ->
                if (notified.add(context.player().uuid)) {
                    MessageUtil.sendColoredMessage(context.player(), "V1 protocol is not supported anymore. Please update your client.")
                }
            }
        }
    }
}
