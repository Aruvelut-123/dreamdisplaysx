package com.dreamdisplays.platform.client.net

import com.dreamdisplays.platform.client.utils.identifier
import com.dreamdisplays.core.protocol.common.PacketRegistry
import com.dreamdisplays.platform.client.Initializer
//? if >=1.20.5 {
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//?} else
/*import com.dreamdisplays.platform.client.net.compat.RegistryFriendlyByteBuf
import com.dreamdisplays.platform.client.net.compat.StreamCodec
import com.dreamdisplays.platform.client.net.compat.CustomPacketPayload*/
//? if >=1.21.11 {
import net.minecraft.resources.Identifier

//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/**
 * The single Minecraft payload wrapper for protocol v2: opaque [PacketRegistry]
 * envelope bytes on the `dreamdisplays:v2` channel. All structure lives in the platform-free
 * `:protocol` module.
 */
data class V2Payload(val bytes: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    override fun equals(other: Any?): Boolean = other is V2Payload && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        /** The `dreamdisplays:v2` channel name. */
        const val CHANNEL: String = "${Initializer.MOD_ID}:v2"

        /** Registered payload type for the v2 channel. */
        val TYPE: CustomPacketPayload.Type<V2Payload> =
            CustomPacketPayload.Type(identifier(Initializer.MOD_ID, "v2"))

        /** Stream codec that reads / writes the raw envelope bytes verbatim. */
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, V2Payload> = StreamCodec.of(
            { buf, payload -> buf.writeBytes(payload.bytes) },
            { buf -> V2Payload(ByteArray(buf.readableBytes()).also(buf::readBytes)) },
        )
    }
}
