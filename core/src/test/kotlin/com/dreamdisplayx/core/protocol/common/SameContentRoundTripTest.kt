package com.dreamdisplayx.core.protocol.common

import com.dreamdisplayx.api.protocol.model.PacketDirection
import com.dreamdisplayx.core.protocol.common.packets.SameContentState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SameContentRoundTripTest {
    @Test
    fun sameContentStateRoundTripsThroughV3Batch() {
        val first = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
        val second = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210")
        val packet = SameContentState(
            groupId = "video-group",
            displayIds = listOf(first, second),
            url = "https://example.invalid/video.mp4",
            lang = "en",
            positionMs = 12_345,
            serverTimeMs = 1_700_000_000_000,
            durationMs = 600_000,
            paused = false,
            loop = true,
            mode = 1,
        )

        assertEquals(
            listOf(packet),
            PacketRegistry.decodeV3(
                PacketRegistry.encodeV3(listOf(packet)),
                PacketDirection.SERVER_TO_CLIENT,
            ),
        )
    }
}
