package com.dreamdisplays.platform.client.net

import com.dreamdisplays.platform.client.utils.identifier
import com.dreamdisplays.core.protocol.proxy.ProxyPacketRegistry
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
 * Minecraft payload wrapper for the backend <-> proxy `dreamdisplays:proxy` channel (`Fabric` / `NeoForge` on either end);
 * carries the opaque envelope [bytes].
 */
data class ProxyPayload(val bytes: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    override fun equals(other: Any?): Boolean = other is ProxyPayload && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        /** The `dreamdisplays:proxy` channel name. */
        const val CHANNEL: String = "${Initializer.MOD_ID}:proxy"

        /** Registered payload type for the proxy channel. */
        val TYPE: CustomPacketPayload.Type<ProxyPayload> =
            CustomPacketPayload.Type(identifier(Initializer.MOD_ID, "proxy"))

        /** Stream codec that reads / writes the raw envelope bytes verbatim. */
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ProxyPayload> = StreamCodec.of(
            { buf, payload -> buf.writeBytes(payload.bytes) },
            { buf -> ProxyPayload(ByteArray(buf.readableBytes()).also(buf::readBytes)) },
        )
    }
}
