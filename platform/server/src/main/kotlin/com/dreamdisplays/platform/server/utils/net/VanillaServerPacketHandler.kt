package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.net.LegacyProbe
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.registration.PayloadRegistrar

/**
 * Protocol v1 is no longer supported; only its handshake is still registered, to tell outdated
 * peers apart. Every client sends the v1 `version` packet after its v2 hello, so one arriving from a
 * player that never said hello means a v1-only client.
 */
object VanillaServerPacketHandler {
    /** Registers the v1 `version` probe receiver for `Fabric` servers. */
    @FabricOnly
    fun registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(LegacyProbe.Version.PACKET_ID) { _, context ->
            onLegacyVersion(context.player())
        }
    }

    /**
     * Registers the v1 probe payloads against [registrar]: the serverbound `version` and the
     * clientbound `premium` (only v1-only servers send it, which the client reports as outdated).
     * Must be called exactly once total for the whole mod (`NeoForge`'s payload registry rejects a
     * second registration of the same id).
     */
    @NeoForgeOnly
    fun registerReceivers(registrar: PayloadRegistrar) {
        registrar.playToServer(LegacyProbe.Version.PACKET_ID, LegacyProbe.Version.PACKET_CODEC) { _, context ->
            onLegacyVersion(context.player() as ServerPlayer)
        }
        registrar.playToClient(LegacyProbe.Premium.PACKET_ID, LegacyProbe.Premium.PACKET_CODEC) { _, _ ->
            Initializer.onLegacyServerDetected()
        }
    }

    /** Asks a v1-only client to update; v2 players already negotiated and are ignored. */
    private fun onLegacyVersion(player: ServerPlayer) {
        if (V2PlayerTracker.isV2(player.uuid)) return
        VanillaDisplayActions.notifyOutdatedClient(player)
    }
}
