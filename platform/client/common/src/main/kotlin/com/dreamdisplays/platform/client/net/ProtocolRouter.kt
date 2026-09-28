package com.dreamdisplays.platform.client.net

import com.dreamdisplays.api.protocol.model.PacketDirection
import com.dreamdisplays.core.protocol.common.PacketRegistry
import com.dreamdisplays.core.protocol.common.packets.*
import com.dreamdisplays.platform.client.managers.ClientPacketManager
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/**
 * Client-side protocol negotiation: speaks v2 only after the server has proven v2 support by
 * answering the blind [ClientHello] with a [ServerHello].
 */
object ProtocolRouter {
    /** Logger for negotiation and decode diagnostics. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** True once the server has proven v2 support by answering with a [ServerHello]. */
    @Volatile
    var v2Negotiated: Boolean = false
        private set

    /** True once the player was told this server only speaks protocol v1. */
    @Volatile
    private var outdatedServerReported: Boolean = false

    /** Sends [packet] over v2; dropped until the server has negotiated v2. */
    fun send(packet: DreamPacket) {
        if (v2Negotiated) sendV2(packet)
        else logger.debug("Dropping {}: protocol v2 not negotiated.", packet::class.simpleName)
    }

    /** Sends [packet] over the v2 channel unconditionally; used for the blind hello bootstrap. */
    fun sendV2(packet: DreamPacket) {
        ClientPacketManager.send(V2Payload(PacketRegistry.encode(packet)))
    }

    /** Sends the v1 `version` probe; only v1-only servers answer it (see [onLegacyServerDetected]). */
    fun sendLegacyProbe(modVersion: String) {
        ClientPacketManager.send(LegacyProbe.Version(modVersion))
    }

    /** Decodes and dispatches v2 envelope bytes; the first [ServerHello] flips the v2 switch. */
    fun onV2Received(bytes: ByteArray) {
        val packet = runCatching { PacketRegistry.decode(bytes, PacketDirection.SERVER_TO_CLIENT) }
            .onFailure { logger.warn("Failed to decode v2 packet", it) }
            .getOrNull() ?: return
        if (packet is ServerHello && !v2Negotiated) {
            v2Negotiated = true
            logger.info("Protocol v2 negotiated (server protocol ${packet.protocolVersion}).")
        }
        ClientPacketManager.handle(packet)
    }

    /** A v1 reply arrived: the server's `Dream Displays` is too old, so tell the player once. */
    fun onLegacyServerDetected() {
        if (v2Negotiated || outdatedServerReported) return
        outdatedServerReported = true
        logger.warn("Server runs a Dream Displays version that only supports protocol v1; displays are disabled.")
        val mc = Minecraft.getInstance()
        mc.execute {
            mc.gui.chat.addMessage(
                Component.translatable("dreamdisplays.message.outdated_server")
                    .withStyle(ChatFormatting.RED)
            )
        }
    }

    /** Resets negotiation state on disconnect. */
    fun reset() {
        v2Negotiated = false
        outdatedServerReported = false
    }
}
