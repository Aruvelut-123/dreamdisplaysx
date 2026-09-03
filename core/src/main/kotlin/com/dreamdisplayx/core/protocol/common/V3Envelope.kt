@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.dreamdisplayx.core.protocol.common

import com.dreamdisplayx.api.protocol.ProtocolGeneration
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * The generation-3 envelope. Unlike the v2 [Envelope], it carries an explicit generation marker
 * and supports **batching**: one wire frame can hold several packets, reducing per-packet framing
 * overhead. Decoding is a superset of v2 — a v3 peer can carry every packet type v2 could.
 */
@Serializable
data class V3Envelope(
    @ProtoNumber(1) val generation: Int = ProtocolGeneration.V3,
    @ProtoNumber(2) val packets: List<Packet> = emptyList(),
) {
    @Serializable
    data class Packet(
        @ProtoNumber(1) val type: Int = 0,
        @ProtoNumber(2) val payload: ByteArray = ByteArray(0),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Packet) return false
            return type == other.type && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * type + payload.contentHashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is V3Envelope) return false
        return generation == other.generation && packets == other.packets
    }

    override fun hashCode(): Int = 31 * generation + packets.hashCode()
}
