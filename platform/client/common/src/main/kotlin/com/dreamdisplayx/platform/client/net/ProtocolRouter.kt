package com.dreamdisplayx.platform.client.net

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.protocol.model.PacketDirection
import com.dreamdisplayx.core.protocol.common.PacketRegistry
import com.dreamdisplayx.core.protocol.common.packets.DreamPacket
import com.dreamdisplayx.core.protocol.common.packets.ServerHello
import com.dreamdisplayx.platform.client.managers.ClientPacketManager
import org.slf4j.LoggerFactory

/** Experimental protocol routing seam for the negotiated V2/V3 envelope transports. */
@DreamDisplaysXUnstableApi
object ProtocolRouter {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ProtocolRouter")

    @Volatile
    private var v3Active = false

    /** Sends [packet] through the negotiated envelope, retaining V2 as the fallback. */
    fun send(packet: DreamPacket) {
        if (v3Active) sendV3(packet) else sendV2(packet)
    }

    /** Sends [packet] through the V2 envelope. */
    private fun sendV2(packet: DreamPacket) {
        ClientPacketManager.send(V2Payload(PacketRegistry.encode(packet)))
    }

    /** Sends [packet] through the V3 batch-capable envelope. */
    fun sendV3(packet: DreamPacket) {
        ClientPacketManager.send(V3Payload(PacketRegistry.encodeV3(packet)))
    }

    /** Decodes and dispatches a v2 server packet. */
    fun onV2Received(bytes: ByteArray) {
        val packet = runCatching { PacketRegistry.decode(bytes, PacketDirection.SERVER_TO_CLIENT) }
            .onFailure { logger.warn("Failed to decode protocol-v2 packet", it) }
            .getOrNull() ?: return
        if (packet is ServerHello) logger.info("Protocol v2 active (server protocol ${packet.protocolVersion}).")
        ClientPacketManager.handle(packet)
    }

    /** Decodes and dispatches a V3 batch from the `dreamdisplayx:v3` channel. */
    fun onV3Received(bytes: ByteArray) {
        val packets = runCatching { PacketRegistry.decodeV3(bytes, PacketDirection.SERVER_TO_CLIENT) }
            .onFailure { logger.warn("Failed to decode protocol-v3 packet", it) }
            .getOrNull() ?: return
        v3Active = true
        packets.forEach { packet ->
            if (packet is ServerHello) {
                logger.info("Protocol v3 active (server protocol ${packet.protocolVersion}).")
            }
            ClientPacketManager.handle(packet)
        }
    }

    /** Clears per-connection state on disconnect. */
    fun reset() {
        v3Active = false
    }
}
