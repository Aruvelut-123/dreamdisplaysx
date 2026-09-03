package com.dreamdisplayx.platform.client.net

import com.dreamdisplayx.platform.client.Initializer
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/** Opaque generation-3 batch envelope on the `dreamdisplayx:v3` channel. */
data class V3Payload(val bytes: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    override fun equals(other: Any?): Boolean = other is V3Payload && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        const val CHANNEL: String = "${Initializer.MOD_ID}:v3"
        val TYPE: CustomPacketPayload.Type<V3Payload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "v3"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, V3Payload> = StreamCodec.of(
            { buf, payload -> buf.writeBytes(payload.bytes) },
            { buf -> V3Payload(ByteArray(buf.readableBytes()).also(buf::readBytes)) },
        )
    }
}
