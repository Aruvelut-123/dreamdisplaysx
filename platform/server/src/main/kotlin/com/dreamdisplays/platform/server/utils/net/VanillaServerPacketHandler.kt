package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.net.LegacyProbe
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
//? if >=1.21.1 {
import net.neoforged.neoforge.network.registration.PayloadRegistrar
//?}
import net.minecraft.server.level.ServerPlayer

/**
 * Protocol v1 is no longer supported; only its handshake is still registered, to tell outdated
 * peers apart. Every client sends the v1 `version` packet after its v2 hello, so one arriving from a
 * player that never said hello means a v1-only client.
 */
object VanillaServerPacketHandler {
    // Forge registers its channels in :platform:client:forge

    /** Registers the v1 `version` probe receiver for `Fabric` servers. */
    @FabricOnly
    fun registerReceivers() {
        //? if >=1.20.5 {
        ServerPlayNetworking.registerGlobalReceiver(LegacyProbe.Version.PACKET_ID) { _, context ->
            onLegacyVersion(context.player())
        }
        //?} else
        /*ServerPlayNetworking.registerGlobalReceiver(LegacyProbe.Version.PACKET_ID.id()) { server, player, _, _, _ ->
            server.execute { onLegacyVersion(player) }
        }*/
    }

    //? if >=1.21.1 {

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
    //?}

    /** Handles a v1 `version` packet from [player]; shared by every loader's receiver. */
    fun onLegacyVersion(player: ServerPlayer) {
        if (V2PlayerTracker.isV2(player.uuid)) return
        VanillaDisplayActions.notifyOutdatedClient(player)
    }
}
