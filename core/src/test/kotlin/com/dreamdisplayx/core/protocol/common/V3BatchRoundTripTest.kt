@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.dreamdisplayx.core.protocol.common

import com.dreamdisplayx.api.protocol.model.PacketDirection
import com.dreamdisplayx.core.protocol.common.packets.DisplayDelete
import com.dreamdisplayx.core.protocol.common.packets.DisplaySync
import com.dreamdisplayx.core.protocol.common.packets.ServerHello
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.util.UUID

class V3BatchRoundTripTest {
    private val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

    @Test
    fun severalPacketsRoundTripInOneEnvelope() {
        val packets = listOf(
            ServerHello(isPremium = true, maxDisplays = 8),
            DisplayDelete(id),
            DisplaySync(id, isSync = true, isPaused = true, currentTimeMs = 42_000),
        )
        val decoded = PacketRegistry.decodeV3(PacketRegistry.encodeV3(packets), PacketDirection.SERVER_TO_CLIENT)
        assertEquals(packets, decoded)
    }

    @Test
    fun directionValidationRejectsWrongBatch() {
        // ServerHello travels server -> client only; decoding it as an inbound client batch must throw.
        val bytes = PacketRegistry.encodeV3(listOf(ServerHello()))
        assertFailsWith<IllegalArgumentException> {
            PacketRegistry.decodeV3(bytes, PacketDirection.CLIENT_TO_SERVER)
        }
    }

    @Test
    fun unknownFrameIsSkippedWithoutDroppingKnownPackets() {
        val proto = ProtoBuf { }
        val known = PacketRegistry.encodeV3(listOf(DisplayDelete(id)))
        val decoded = proto.decodeFromByteArray(V3Envelope.serializer(), known)
        val withUnknown = proto.encodeToByteArray(
            V3Envelope.serializer(),
            decoded.copy(packets = listOf(V3Envelope.Packet(9999, byteArrayOf(1, 2, 3))) + decoded.packets),
        )
        assertEquals(listOf(DisplayDelete(id)), PacketRegistry.decodeV3(withUnknown))
    }
}
