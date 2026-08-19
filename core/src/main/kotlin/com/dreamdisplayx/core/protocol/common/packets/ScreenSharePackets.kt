@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplayx.core.protocol.common.packets

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Screen-sharing cast over the mod's own v2 protocol — no external RTMP server involved. The
 * sharing client pushes an encoded MPEG-TS stream in [ScreenShareData] chunks; the server relays it
 * to viewers through an internal HTTP endpoint and hands the watch URL back via [ScreenShareAck].
 * Works only on modded servers (v2 peers).
 */

/** Client announces a new screen-sharing cast; the server replies with [ScreenShareAck]. */
@Serializable
data class ScreenShareStart(
    @ProtoNumber(1) val castId: String = "",
    @ProtoNumber(2) val width: Int = 0,
    @ProtoNumber(3) val height: Int = 0,
) : DreamPacket

/** One chunk of the encoded cast (MPEG-TS) from the sharing client, in [sequence] order. */
@Serializable
data class ScreenShareData(
    @ProtoNumber(1) val castId: String = "",
    @ProtoNumber(2) val sequence: Int = 0,
    @ProtoNumber(3) val payload: ByteArray = ByteArray(0),
) : DreamPacket {
    override fun equals(other: Any?): Boolean =
        other is ScreenShareData && castId == other.castId && sequence == other.sequence &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * castId.hashCode() + sequence) + payload.contentHashCode()
}

/** Client ends the cast; the server drops its buffer and closes the watch URL. */
@Serializable
data class ScreenShareStop(
    @ProtoNumber(1) val castId: String = "",
) : DreamPacket

/** Server confirms the cast started and tells the sharer the URL viewers will watch. */
@Serializable
data class ScreenShareAck(
    @ProtoNumber(1) val castId: String = "",
    @ProtoNumber(2) val watchUrl: String = "",
) : DreamPacket
