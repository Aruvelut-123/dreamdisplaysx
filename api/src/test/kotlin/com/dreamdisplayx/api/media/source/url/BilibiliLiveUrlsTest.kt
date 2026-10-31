package com.dreamdisplayx.api.media.source.url

import com.dreamdisplayx.api.media.source.model.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Covers the Bilibili live-room route prefixes that mobile share sheets produce.
 *
 * `live.bilibili.com/h5/<room>` is the URL the Android app's share button emits, and the parser used
 * to read only the first path segment: `h5` is not a number, so the whole link was rejected and
 * pasting it never resolved to a playable room.
 */
class BilibiliLiveUrlsTest {
    @Test
    fun `canonical live room url parses the room id`() {
        val source = assertIs<MediaSource.Bilibili>(MediaSource.from("https://live.bilibili.com/27841685"))
        assertEquals(27841685L, source.roomId)
    }

    @Test
    fun `h5 share link parses the room id behind the route`() {
        val url = "https://live.bilibili.com/h5/27841685?broadcast_type=0&is_room_feed=1&plat_id=365" +
            "&share_from=live&share_medium=android&share_plat=android"
        val source = assertIs<MediaSource.Bilibili>(MediaSource.from(url))
        assertEquals(27841685L, source.roomId)
    }

    @Test
    fun `blanc share link parses the room id behind the route`() {
        val source = assertIs<MediaSource.Bilibili>(MediaSource.from("https://live.bilibili.com/blanc/27841685"))
        assertEquals(27841685L, source.roomId)
    }

    @Test
    fun `live url without any numeric segment is rejected`() {
        assertEquals(null, BilibiliUrls.parse("https://live.bilibili.com/h5/room-not-a-number"))
    }

    @Test
    fun `vod url still parses after the live change`() {
        val source = assertNotNull(BilibiliUrls.parse("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertEquals("BV1xx411c7mD", source.bvid)
    }
}