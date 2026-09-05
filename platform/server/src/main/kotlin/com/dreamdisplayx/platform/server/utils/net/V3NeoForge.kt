package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.api.protocol.model.PacketDirection
import com.dreamdisplayx.core.protocol.common.PacketRegistry
import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.net.V3Payload
import com.dreamdisplayx.platform.server.utils.RegionUtil
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import org.slf4j.LoggerFactory

/**
 * Generation-3 batch receiver for the `NeoForge` flavor; mirrors [FabricV3Networking]. Without this
 * registration the v3 payload id is unknown to NeoForge, so [NeoForgeV2Networking.send] silently
 * drops every clientbound v3 frame (including the handshake reply) for negotiated peers.
 */
@NeoForgeOnly
object NeoForgeV3Networking {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/NeoForgeV3Networking")

    /**
     * Registers the single v3 batch envelope receiver against [registrar]. Must be called exactly
     * once total for the whole mod, alongside [NeoForgeV2Networking.registerReceivers] on the same
     * `registrar`. Serverbound frames decode into the shared [NeoForgeV2Networking.dispatch]; the
     * clientbound handler is wrapped in [clientHandler] so a dedicated server never loads the
     * client-only [Initializer].
     */
    fun registerReceivers(registrar: PayloadRegistrar) {
        registrar.playBidirectionalCompat(
            V3Payload.TYPE, V3Payload.CODEC,
            serverHandler = { payload, context ->
                runCatching {
                    val player = context.player() as ServerPlayer
                    PacketRegistry.decodeV3(payload.bytes, PacketDirection.CLIENT_TO_SERVER).forEach {
                        NeoForgeV2Networking.dispatch(player, RegionUtil.playerServer(player), it)
                    }
                }.onFailure { e -> logger.warn("Failed to handle v3 packet", e) }
            },
            clientHandler = clientHandler { payload, _ -> Initializer.onV3Packet(payload.bytes) },
        )
    }
}
