package com.dreamdisplayx.platform.client.net

import com.dreamdisplayx.platform.client.DreamIdentifiers
import com.dreamdisplayx.platform.client.Initializer
import net.fabricmc.fabric.api.networking.v1.FabricPacket
import net.fabricmc.fabric.api.networking.v1.PacketType
import net.minecraft.network.FriendlyByteBuf

/**
 * Opaque generation-3 batch envelope on the `dreamdisplayx:v3` channel.
 *
 * 1.20.1 has no `CustomPacketPayload`, so this uses `FabricPacket`; the envelope bytes are written
 * verbatim, keeping the wire format identical to every other target.
 */
data class V3Payload(val bytes: ByteArray) : FabricPacket {
    /** Content-based equality over the envelope [bytes]. */
    override fun equals(other: Any?): Boolean = other is V3Payload && bytes.contentEquals(other.bytes)

    /** Content-based hash over the envelope [bytes]. */
    override fun hashCode(): Int = bytes.contentHashCode()

    /** Writes the envelope bytes verbatim. */
    override fun write(buf: FriendlyByteBuf) {
        buf.writeBytes(bytes)
    }

    /** The registered packet type for the v3 channel. */
    override fun getType(): PacketType<V3Payload> = TYPE

    companion object {
        /** The `dreamdisplayx:v3` channel name. */
        const val CHANNEL: String = "${Initializer.MOD_ID}:v3"

        /** Registered packet type for the v3 channel. */
        val TYPE: PacketType<V3Payload> = PacketType.create(
            DreamIdentifiers.of(Initializer.MOD_ID, "v3"),
        ) { buf -> V3Payload(ByteArray(buf.readableBytes()).also(buf::readBytes)) }
    }
}