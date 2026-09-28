package com.dreamdisplays.platform.client.net

import com.dreamdisplays.platform.client.Initializer
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/**
 * The only protocol-v1 wire formats left, kept purely to detect outdated peers. Their wire format
 * must never change: v1-only servers answer [Version] with [Premium], v1-only clients send [Version].
 */
object LegacyProbe {
    private fun <T : CustomPacketPayload> createType(path: String): CustomPacketPayload.Type<T> =
        CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, path))

    /** Serverbound v1 handshake; sent by every client so v1-only servers reveal themselves. */
    data class Version(val version: String) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<Version> = createType("version")
            val PACKET_CODEC: StreamCodec<RegistryFriendlyByteBuf, Version> = StreamCodec.of(
                { buf, packet -> buf.writeUtf(packet.version) },
                { buf -> Version(buf.readUtf()) }
            )
        }
    }

    /** Clientbound v1 reply that only v1-only servers still send; its arrival marks the server outdated. */
    data class Premium(val premium: Boolean) : CustomPacketPayload {
        /** The packet type. */
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PACKET_ID

        companion object {
            val PACKET_ID: CustomPacketPayload.Type<Premium> = createType("premium")
            val PACKET_CODEC: StreamCodec<RegistryFriendlyByteBuf, Premium> = StreamCodec.of(
                { buf, packet -> buf.writeBoolean(packet.premium) },
                { buf -> Premium(buf.readBoolean()) }
            )
        }
    }
}
