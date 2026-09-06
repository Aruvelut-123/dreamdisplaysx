package com.dreamdisplayx.core.protocol.common

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.playback.model.PlaylistCommandAction
import com.dreamdisplayx.api.playback.model.PlaylistEndBehavior
import com.dreamdisplayx.api.playback.model.PlaylistEnqueuePolicy
import com.dreamdisplayx.api.protocol.model.PacketDirection
import com.dreamdisplayx.core.protocol.common.packets.PlaylistCommand
import com.dreamdisplayx.core.protocol.common.packets.PlaylistItem
import com.dreamdisplayx.core.protocol.common.packets.PlaylistState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(DreamDisplaysXUnstableApi::class)
class PlaylistRoundTripTest {
    @Test
    fun playlistStateRoundTripsThroughV3Batch() {
        val item = PlaylistItem(
            itemId = UUID.fromString("21234567-89ab-cdef-0123-456789abcdef"),
            url = "https://www.bilibili.com/video/BV1xx411c7mD",
            lang = "",
            title = "Test video",
            pending = true,
            requesterId = UUID.fromString("31234567-89ab-cdef-0123-456789abcdef"),
        )
        val packet = PlaylistState(
            displayId = UUID.fromString("11234567-89ab-cdef-0123-456789abcdef"),
            items = listOf(item),
            currentIndex = 0,
            endBehavior = PlaylistEndBehavior.CONTINUE.wire,
            enqueuePolicy = PlaylistEnqueuePolicy.OWNER_APPROVAL.wire,
        )

        assertEquals(
            listOf(packet),
            PacketRegistry.decodeV3(
                PacketRegistry.encodeV3(listOf(packet)),
                PacketDirection.SERVER_TO_CLIENT,
            ),
        )
    }

    @Test
    fun playlistCommandRoundTripsThroughV3Batch() {
        val packet = PlaylistCommand(
            displayId = UUID.fromString("41234567-89ab-cdef-0123-456789abcdef"),
            action = PlaylistCommandAction.ADD.wire,
            itemId = UUID.fromString("51234567-89ab-cdef-0123-456789abcdef"),
            url = "https://example.invalid/v.mp4",
            lang = "zh",
            title = "hello",
            position = 2,
            endBehavior = PlaylistEndBehavior.LOOP_CURRENT.wire,
            enqueuePolicy = PlaylistEnqueuePolicy.OWNER_ONLY.wire,
        )

        assertEquals(
            listOf(packet),
            PacketRegistry.decodeV3(
                PacketRegistry.encodeV3(listOf(packet)),
                PacketDirection.CLIENT_TO_SERVER,
            ),
        )
    }
}
