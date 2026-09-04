package com.dreamdisplayx.core.protocol.common

import com.dreamdisplayx.api.protocol.model.PacketDirection
import com.dreamdisplayx.core.protocol.common.packets.RemoteControlOpen
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteControlRoundTripTest {
    @Test
    fun remoteControlOpenRoundTripsThroughV3() {
        val packet = RemoteControlOpen(
            displayId = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
            displayName = "Lobby",
        )
        assertEquals(
            listOf(packet),
            PacketRegistry.decodeV3(
                PacketRegistry.encodeV3(packet),
                PacketDirection.SERVER_TO_CLIENT,
            ),
        )
    }
}
