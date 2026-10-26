package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.core.protocol.common.packets.DreamPacket
import com.dreamdisplayx.platform.client.net.ProxyPayload
import io.github.arnodoelinger.platformweaver.FabricOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerPlayer

/**
 * Loader-specific seam for the protocol-v2 envelope.
 *
 * The only difference from the modern source is [VanillaNetworkingAdapter.sendProxy]'s parameter:
 * Minecraft 1.20.1 predates `CustomPacketPayload`, so its payloads are Fabric's `FabricPacket`
 * (`ProxyPayload` here) and go out through `ServerPlayNetworking.send(ServerPlayer, FabricPacket)`.
 * The NeoForge adapter is not declared at all: 1.20.1 is a Fabric-only target, and its body needs
 * `net.neoforged.neoforge.network.PacketDistributor`, which is not on this classpath.
 */
interface VanillaNetworkingAdapter {
    /** Sends a v2 envelope [packet] to [players]. */
    fun sendV2(players: List<ServerPlayer>, packet: DreamPacket)

    /**
     * Sends [packets] as one generation-3 batch envelope to every negotiated-v3 player, falling back
     * to per-packet v2 frames for the rest. Used by the join-time display stream to cut framing.
     */
    fun sendV3Batch(players: List<ServerPlayer>, packets: List<DreamPacket>)

    /** Sends the proxy channel payload [packet] to [player]. */
    fun sendProxy(player: ServerPlayer, packet: ProxyPayload)
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
    override fun sendProxy(player: ServerPlayer, packet: ProxyPayload) = ServerPlayNetworking.send(player, packet)
}