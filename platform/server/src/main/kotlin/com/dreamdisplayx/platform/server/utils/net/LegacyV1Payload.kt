package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.platform.client.Initializer
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/** Raw payload used only to identify legacy v1 serverbound traffic. */
data class LegacyV1Payload(val bytes: ByteArray, private val payloadType: CustomPacketPayload.Type<LegacyV1Payload>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = payloadType

    companion object {
        val CHANNELS = listOf("version", "sync", "req_sync", "delete", "report", "display_enabled", "set_video", "set_locked")

        fun type(path: String): CustomPacketPayload.Type<LegacyV1Payload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, path))

        fun codec(type: CustomPacketPayload.Type<LegacyV1Payload>): StreamCodec<RegistryFriendlyByteBuf, LegacyV1Payload> =
            StreamCodec.of(
                { buf, payload -> buf.writeBytes(payload.bytes) },
                { buf -> LegacyV1Payload(ByteArray(buf.readableBytes()).also(buf::readBytes), type) },
            )
    }
}
