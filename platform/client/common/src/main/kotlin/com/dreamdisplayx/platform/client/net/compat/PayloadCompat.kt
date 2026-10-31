//? if <1.20.5 {
package com.dreamdisplayx.platform.client.net.compat

import com.dreamdisplayx.platform.client.net.LegacyProbe
import com.dreamdisplayx.platform.client.net.ProxyPayload
import com.dreamdisplayx.platform.client.net.V2Payload
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation

/** Pre-1.20.5 stand-in for vanilla's `RegistryFriendlyByteBuf`. */
typealias RegistryFriendlyByteBuf = FriendlyByteBuf

/** Pre-1.20.5 stand-in for vanilla's `CustomPacketPayload`. */
interface CustomPacketPayload {
    fun type(): Type<out CustomPacketPayload>

    /** Channel a payload travels on. */
    class Type<T : CustomPacketPayload>(private val id: ResourceLocation) {
        fun id(): ResourceLocation = id
    }
}

/** Pre-1.20.5 stand-in for vanilla's `StreamCodec`. */
interface StreamCodec<B, V> {
    fun encode(buf: B, value: V)

    fun decode(buf: B): V

    companion object {
        fun <B, V> of(encoder: (B, V) -> Unit, decoder: (B) -> V): StreamCodec<B, V> = object : StreamCodec<B, V> {
            override fun encode(buf: B, value: V) = encoder(buf, value)

            override fun decode(buf: B): V = decoder(buf)
        }
    }
}

/** Every `Dream Displays` payload's codec by channel, for loaders that put raw channel bytes on the wire. */
object CompatPayloads {
    @Suppress("UNCHECKED_CAST")
    private val codecs: Map<ResourceLocation, StreamCodec<FriendlyByteBuf, CustomPacketPayload>> = mapOf(
        V2Payload.TYPE.id() to V2Payload.CODEC,
        ProxyPayload.TYPE.id() to ProxyPayload.CODEC,
        LegacyProbe.Version.PACKET_ID.id() to LegacyProbe.Version.PACKET_CODEC,
        LegacyProbe.Premium.PACKET_ID.id() to LegacyProbe.Premium.PACKET_CODEC,
    ).mapValues { (_, codec) -> codec as StreamCodec<FriendlyByteBuf, CustomPacketPayload> }

    /** Serializes [payload] with the codec of its channel. */
    fun encode(payload: CustomPacketPayload): FriendlyByteBuf {
        val codec = codecs[payload.type().id()] ?: error("No channel codec for ${payload.type().id()}.")
        return FriendlyByteBuf(Unpooled.buffer()).also { codec.encode(it, payload) }
    }
}
//?}
