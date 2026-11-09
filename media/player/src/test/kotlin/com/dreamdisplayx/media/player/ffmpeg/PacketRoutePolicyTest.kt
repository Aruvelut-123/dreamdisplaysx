package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.media.player.ffmpeg.PacketRoutePolicy.Route
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins how a demuxed packet is routed to a consumer.
 *
 * The case that matters is the sentinel: "no audio stream" is expressed as
 * `audioStreamIndex = -1`, and a malformed packet also reports
 * `stream_index = -1`. Comparing them without a guard either feeds garbage to
 * the audio decoder or skips every packet of a source and loops until the read
 * fails — neither throws, so only a test catches it.
 */
class PacketRoutePolicyTest {

    @Test
    fun `a video packet goes to the video decoder`() {
        assertEquals(
            Route.VIDEO,
            PacketRoutePolicy.classify(packetStreamIndex = 0, videoStreamIndex = 0, audioStreamIndex = 1),
        )
    }

    @Test
    fun `an audio packet goes to the audio decoder`() {
        assertEquals(
            Route.AUDIO,
            PacketRoutePolicy.classify(packetStreamIndex = 1, videoStreamIndex = 0, audioStreamIndex = 1),
        )
    }

    @Test
    fun `a source with no audio never routes to the audio decoder`() {
        // audioStreamIndex = -1 is the "absent" sentinel, not a stream number.
        assertEquals(
            Route.VIDEO,
            PacketRoutePolicy.classify(packetStreamIndex = 0, videoStreamIndex = 0, audioStreamIndex = -1),
        )
        assertEquals(
            Route.SKIP,
            PacketRoutePolicy.classify(packetStreamIndex = 5, videoStreamIndex = 0, audioStreamIndex = -1),
        )
    }

    @Test
    fun `a packet with a negative stream index is always skipped`() {
        // Without this guard a malformed packet on an audio-less source matches
        // the -1 sentinel and is handed to the audio decoder.
        assertEquals(
            Route.SKIP,
            PacketRoutePolicy.classify(packetStreamIndex = -1, videoStreamIndex = 0, audioStreamIndex = -1),
        )
        assertEquals(
            Route.SKIP,
            PacketRoutePolicy.classify(packetStreamIndex = -1, videoStreamIndex = 0, audioStreamIndex = 1),
        )
        assertEquals(
            Route.SKIP,
            PacketRoutePolicy.classify(packetStreamIndex = -1, videoStreamIndex = -1, audioStreamIndex = -1),
        )
    }

    @Test
    fun `subtitle and data streams are skipped`() {
        assertEquals(
            Route.SKIP,
            PacketRoutePolicy.classify(packetStreamIndex = 2, videoStreamIndex = 0, audioStreamIndex = 1),
        )
        assertEquals(
            Route.SKIP,
            PacketRoutePolicy.classify(packetStreamIndex = 99, videoStreamIndex = 0, audioStreamIndex = 1),
        )
    }

    @Test
    fun `video wins when the container reports the same index for both`() {
        // A broken container can list one stream as both; taking video keeps the
        // picture alive, which is the failure a viewer notices second.
        assertEquals(
            Route.VIDEO,
            PacketRoutePolicy.classify(packetStreamIndex = 0, videoStreamIndex = 0, audioStreamIndex = 0),
        )
    }

    @Test
    fun `an audio-only source still routes its packets`() {
        // DASH renditions are opened separately, so a session may legitimately
        // have no video stream at all (videoStreamIndex = -1).
        assertEquals(
            Route.AUDIO,
            PacketRoutePolicy.classify(packetStreamIndex = 0, videoStreamIndex = -1, audioStreamIndex = 0),
        )
    }

    @Test
    fun `a source with neither stream skips everything instead of looping on a match`() {
        assertEquals(
            Route.SKIP,
            PacketRoutePolicy.classify(packetStreamIndex = 0, videoStreamIndex = -1, audioStreamIndex = -1),
        )
    }
}