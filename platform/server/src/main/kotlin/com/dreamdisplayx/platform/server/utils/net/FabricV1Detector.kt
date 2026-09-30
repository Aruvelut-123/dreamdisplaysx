package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.platform.client.net.LegacyProbe
import com.dreamdisplayx.platform.server.utils.MessageUtil
import io.github.arnodoelinger.platformweaver.FabricOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives the frozen v1 `version` probe on Fabric. Every client sends it after its v2 hello, so one
 * arriving from a player who never negotiated v2 means a v1-only client that must update.
 */
@FabricOnly
object FabricV1Detector {
    private val notified = ConcurrentHashMap.newKeySet<java.util.UUID>()

    fun register() {
        ServerPlayNetworking.registerGlobalReceiver(LegacyProbe.Version.PACKET_ID) { _, context ->
            val player = context.player()
            if (V2PlayerTracker.isV2(player.uuid)) return@registerGlobalReceiver
            if (notified.add(player.uuid)) {
                MessageUtil.sendMessage(player, "outdatedClient")
            }
        }
    }
}
