package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
//? if >=1.20.5 {
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//?} else
/*import com.dreamdisplays.platform.client.net.compat.CompatPayloads
import com.dreamdisplays.platform.client.net.compat.CustomPacketPayload*/
import net.minecraft.server.level.ServerPlayer
//? if >=1.21.1 {
import net.neoforged.neoforge.network.PacketDistributor
//?}

/** The one genuinely loader-specific seam left after merging the rest of `Fabric` / `NeoForge` packet handling into shared code. */
interface VanillaNetworkingAdapter {
    /** Sends a v2 envelope [packet] to [players] via this loader's v2 channel. */
    fun sendV2(players: List<ServerPlayer>, packet: DreamPacket)

    /** Sends a raw, non-envelope [packet] (e.g. the proxy channel) to a single [player]. */
    fun sendRaw(player: ServerPlayer, packet: CustomPacketPayload)
}

/** Holds the active [VanillaNetworkingAdapter], set once by whichever vanilla loader is running. */
object VanillaNetworking {
    lateinit var adapter: VanillaNetworkingAdapter
}

// Forge adapter lives in :platform:client:forge

/** `Fabric` [VanillaNetworkingAdapter]: v2 via [FabricV2Networking], raw payloads via `ServerPlayNetworking`. */
@FabricOnly
object FabricNetworkingAdapter : VanillaNetworkingAdapter {
    override fun sendV2(players: List<ServerPlayer>, packet: DreamPacket) {
        FabricV2Networking.send(players, packet)
    }

    override fun sendRaw(player: ServerPlayer, packet: CustomPacketPayload) {
        //? if >=1.20.5 {
        runCatching { ServerPlayNetworking.send(player, packet) }
        //?} else
        /*runCatching { ServerPlayNetworking.send(player, packet.type().id(), CompatPayloads.encode(packet)) }*/
    }
}

//? if >=1.21.1 {
/** `NeoForge` [VanillaNetworkingAdapter]: v2 via [NeoForgeV2Networking], raw payloads via `PacketDistributor`. */
@NeoForgeOnly
object NeoForgeNetworkingAdapter : VanillaNetworkingAdapter {
    override fun sendV2(players: List<ServerPlayer>, packet: DreamPacket) {
        NeoForgeV2Networking.send(players, packet)
    }

    override fun sendRaw(player: ServerPlayer, packet: CustomPacketPayload) {
        runCatching { PacketDistributor.sendToPlayer(player, packet) }
    }
}
//?}
