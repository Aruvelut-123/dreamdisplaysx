package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.net.LegacyProbe
import com.dreamdisplayx.platform.server.utils.MessageUtil
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import java.util.concurrent.ConcurrentHashMap

/**
 * Registers the frozen v1 probe payloads on NeoForge: the serverbound `version` (a v1-only client
 * that must update) and the clientbound `premium` (only v1-only servers send it, which the receiving
 * client reports as an outdated server).
 */
@NeoForgeOnly
object NeoForgeV1Detector {
    private val notified = ConcurrentHashMap.newKeySet<java.util.UUID>()

    fun registerReceivers(registrar: PayloadRegistrar) {
        // The serverbound v1 `version` probe: a player who never negotiated v2 is a v1-only client.
        registrar.playBidirectionalCompat(
            LegacyProbe.Version.PACKET_ID,
            LegacyProbe.Version.PACKET_CODEC,
            { _, context ->
                (context.player() as? ServerPlayer)?.let { player ->
                    if (V2PlayerTracker.isV2(player.uuid)) return@let
                    if (notified.add(player.uuid)) {
                        MessageUtil.sendMessage(player, "outdatedClient")
                    }
                }
            },
            { _, _ -> },
        )
        // Clientbound `premium`: only v1-only servers send it, so its arrival means the server is old.
        registrar.playBidirectionalCompat(
            LegacyProbe.Premium.PACKET_ID,
            LegacyProbe.Premium.PACKET_CODEC,
            { _, _ -> },
            clientHandler { _, _ -> Initializer.onLegacyServerDetected() },
        )
    }
}
