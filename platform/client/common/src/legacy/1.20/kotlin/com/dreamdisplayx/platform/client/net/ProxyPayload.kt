package com.dreamdisplayx.platform.client.net

import com.dreamdisplayx.platform.client.DreamIdentifiers
import com.dreamdisplayx.platform.client.Initializer
import net.fabricmc.fabric.api.networking.v1.FabricPacket
import net.fabricmc.fabric.api.networking.v1.PacketType
import net.minecraft.network.FriendlyByteBuf

/**
 * Minecraft payload wrapper for the backend <-> proxy `dreamdisplayx:proxy` channel; carries the
 * opaque envelope bytes.
 *
 * 1.20.1 predates `CustomPacketPayload`, so this uses `FabricPacket`; the wire layout is the
 * envelope bytes verbatim, identical to every other target.
 */
data class ProxyPayload(val bytes: ByteArray) : FabricPacket {
    /** Content-based equality over the envelope [bytes]. */
    override fun equals(other: Any?): Boolean = other is ProxyPayload && bytes.contentEquals(other.bytes)

    /** Content-based hash over the envelope [bytes]. */
    override fun hashCode(): Int = bytes.contentHashCode()

    /** Writes the envelope bytes verbatim. */
    override fun write(buf: FriendlyByteBuf) {
        buf.writeBytes(bytes)
    }

    /** The registered packet type for the proxy channel. */
    override fun getType(): PacketType<ProxyPayload> = TYPE

    companion object {
        /** The `dreamdisplayx:proxy` channel name. */
        const val CHANNEL: String = "${Initializer.MOD_ID}:proxy"

        /** Registered packet type for the proxy channel. */
        val TYPE: PacketType<ProxyPayload> = PacketType.create(
            DreamIdentifiers.of(Initializer.MOD_ID, "proxy"),
        ) { buf -> ProxyPayload(ByteArray(buf.readableBytes()).also(buf::readBytes)) }
    }
}