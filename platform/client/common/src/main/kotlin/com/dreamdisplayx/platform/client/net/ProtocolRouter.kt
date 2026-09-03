package com.dreamdisplayx.platform.client.net

import com.dreamdisplayx.api.protocol.model.PacketDirection
import com.dreamdisplayx.core.protocol.common.PacketRegistry
import com.dreamdisplayx.core.protocol.common.packets.DreamPacket
import com.dreamdisplayx.core.protocol.common.packets.ServerHello
import com.dreamdisplayx.platform.client.managers.ClientPacketManager
import org.slf4j.LoggerFactory

/** Protocol routing seam for V2 now and future V3 compatibility. */
object ProtocolRouter {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ProtocolRouter")

    /** Sends [packet] through the v2 envelope. */
    fun send(packet: DreamPacket) {
        ClientPacketManager.send(V2Payload(PacketRegistry.encode(packet)))
    }

    /** Decodes and dispatches a v2 server packet. */
    fun onV2Received(bytes: ByteArray) {
        val packet = runCatching { PacketRegistry.decode(bytes, PacketDirection.SERVER_TO_CLIENT) }
            .onFailure { logger.warn("Failed to decode protocol-v2 packet", it) }
            .getOrNull() ?: return
        if (packet is ServerHello) logger.info("Protocol v2 active (server protocol ${packet.protocolVersion}).")
        ClientPacketManager.handle(packet)
    }

    /** Clears per-connection state on disconnect. */
    fun reset() = Unit
}
