package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.core.protocol.common.packets.DreamPacket
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer

/** Loader-specific seam for the protocol-v2 envelope. */
interface VanillaNetworkingAdapter {
    /** Sends a v2 envelope [packet] to [players]. */
    fun sendV2(players: List<ServerPlayer>, packet: DreamPacket)

    /**
     * Sends [packets] as one generation-3 batch envelope to every negotiated-v3 player, falling back
     * to per-packet v2 frames for the rest. Used by the join-time display stream to cut framing.
     */
    fun sendV3Batch(players: List<ServerPlayer>, packets: List<DreamPacket>)
    fun sendProxy(player: ServerPlayer, packet: CustomPacketPayload)
}

/** Holds the active loader adapter. */
object VanillaNetworking {
    lateinit var adapter: VanillaNetworkingAdapter
}

/** Fabric v2 networking adapter. */
@FabricOnly
object FabricNetworkingAdapter : VanillaNetworkingAdapter {
    override fun sendV2(players: List<ServerPlayer>, packet: DreamPacket) = FabricV2Networking.send(players, packet)
    override fun sendV3Batch(players: List<ServerPlayer>, packets: List<DreamPacket>) = FabricV2Networking.sendBatch(players, packets)
    override fun sendProxy(player: ServerPlayer, packet: CustomPacketPayload) = net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, packet)
}

/** NeoForge v2 networking adapter. */
@NeoForgeOnly
object NeoForgeNetworkingAdapter : VanillaNetworkingAdapter {
    override fun sendV2(players: List<ServerPlayer>, packet: DreamPacket) = NeoForgeV2Networking.send(players, packet)
    override fun sendV3Batch(players: List<ServerPlayer>, packets: List<DreamPacket>) = NeoForgeV2Networking.sendBatch(players, packets)
    override fun sendProxy(player: ServerPlayer, packet: CustomPacketPayload) = net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, packet)
}
