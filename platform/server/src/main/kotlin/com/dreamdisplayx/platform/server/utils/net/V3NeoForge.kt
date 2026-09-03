package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.api.protocol.model.PacketDirection
import com.dreamdisplayx.core.protocol.common.PacketRegistry
import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.net.V3Payload
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.registration.PayloadRegistrar

/** Generation-3 batch receiver for NeoForge servers and clients. */
@NeoForgeOnly
object NeoForgeV3Networking {
    fun registerReceivers(registrar: PayloadRegistrar) {
        //? if >=1.21.11 {
        registrar.playBidirectionalCompat(
            V3Payload.TYPE, V3Payload.CODEC,
            { payload, context ->
                runCatching {
                    val player = context.player() as ServerPlayer
                    PacketRegistry.decodeV3(payload.bytes, PacketDirection.CLIENT_TO_SERVER).forEach {
                        NeoForgeV2Networking.dispatch(player, com.dreamdisplayx.platform.server.utils.RegionUtil.playerServer(player), it)
                    }
                }
            },
            clientHandler { payload, _ -> Initializer.onV3Packet(payload.bytes) },
        )
        //?} else
        /*registrar.playBidirectional(V3Payload.TYPE, V3Payload.CODEC) { payload, context ->
            if (context.flow() == net.minecraft.network.protocol.PacketFlow.SERVERBOUND) {
                val player = context.player() as ServerPlayer
                PacketRegistry.decodeV3(payload.bytes, PacketDirection.CLIENT_TO_SERVER).forEach {
                    NeoForgeV2Networking.dispatch(player, RegionUtil.playerServer(player), it)
                }
            } else if (isClientDist) Initializer.onV3Packet(payload.bytes)
        }*/
    }
}
