package com.dreamdisplayx.platform.client.net

import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.utils.identifier
//? if >=1.20.5 {
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//?} else
/*import com.dreamdisplayx.platform.client.net.compat.RegistryFriendlyByteBuf
import com.dreamdisplayx.platform.client.net.compat.StreamCodec
import com.dreamdisplayx.platform.client.net.compat.CustomPacketPayload*/

/** Opaque generation-3 batch envelope on the `dreamdisplayx:v3` channel. */
data class V3Payload(val bytes: ByteArray) : CustomPacketPayload {
    /** The payload type used by Minecraft's networking. */
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    /** Content-based equality over the envelope [bytes]. */
    override fun equals(other: Any?): Boolean = other is V3Payload && bytes.contentEquals(other.bytes)

    /** Content-based hash over the envelope [bytes]. */
    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        /** The `dreamdisplayx:v3` channel name. */
        const val CHANNEL: String = "${Initializer.MOD_ID}:v3"

        /** Registered payload type for the v3 channel. */
        val TYPE: CustomPacketPayload.Type<V3Payload> =
            CustomPacketPayload.Type(identifier(Initializer.MOD_ID, "v3"))

        /** Stream codec that reads / writes the raw envelope bytes verbatim. */
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, V3Payload> = StreamCodec.of(
            { buf, payload -> buf.writeBytes(payload.bytes) },
            { buf -> V3Payload(ByteArray(buf.readableBytes()).also(buf::readBytes)) },
        )
    }
}