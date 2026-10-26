package com.dreamdisplayx.platform.client.net

import com.dreamdisplayx.platform.client.DreamIdentifiers
import com.dreamdisplayx.platform.client.Initializer
import net.fabricmc.fabric.api.networking.v1.FabricPacket
import net.fabricmc.fabric.api.networking.v1.PacketType
import net.minecraft.network.FriendlyByteBuf

/**
 * The single Minecraft payload wrapper for protocol v2: opaque `PacketRegistry` envelope bytes on
 * the `dreamdisplayx:v2` channel.
 *
 * Minecraft 1.20.1 predates `CustomPacketPayload` / `StreamCodec` entirely, so this target uses the
 * Fabric API's object-based `FabricPacket` + `PacketType` pair instead. The wire layout is
 * identical - the envelope bytes verbatim - so a 1.20.1 client interoperates with a server built
 * from any other version in this repository.
 */
data class V2Payload(val bytes: ByteArray) : FabricPacket {
    /** Content-based equality over the envelope [bytes]. */
    override fun equals(other: Any?): Boolean = other is V2Payload && bytes.contentEquals(other.bytes)

    /** Content-based hash over the envelope [bytes]. */
    override fun hashCode(): Int = bytes.contentHashCode()

    /** Writes the envelope bytes verbatim. */
    override fun write(buf: FriendlyByteBuf) {
        buf.writeBytes(bytes)
    }

    /** The registered packet type for the v2 channel. */
    override fun getType(): PacketType<V2Payload> = TYPE

    companion object {
        /** The `dreamdisplayx:v2` channel name. */
        const val CHANNEL: String = "${Initializer.MOD_ID}:v2"

        /** Registered packet type for the v2 channel. */
        val TYPE: PacketType<V2Payload> = PacketType.create(
            DreamIdentifiers.of(Initializer.MOD_ID, "v2"),
        ) { buf -> V2Payload(ByteArray(buf.readableBytes()).also(buf::readBytes)) }
    }
}