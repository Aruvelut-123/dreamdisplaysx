@file:OptIn(DreamDisplaysXUnstableApi::class)

package com.dreamdisplayx.media.source

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.source.model.MediaPlatform
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.media.source.platform.PlatformVideoMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the search-box "paste a link, play it" mapping ([PastedMediaCards.fromQuery]).
 *
 * Regression guard for the reported bug where pasting a custom http(s) video-file URL (e.g. an
 * mp4) produced a Bilibili text-search result instead of a single card for that file: the card
 * must keep the exact URL as its id and watch target and must be flagged custom.
 */
class PastedMediaCardsTest {

    @Test
    fun `direct mp4 url becomes a custom card keyed by that url`() {
        val url = "https://cdn.example.com/clips/demo.mp4"
        val card = PastedMediaCards.fromQuery(url)!!
        assertTrue(card.isCustom, "pasted direct link must be a custom card, got platform=${card.platform}")
        assertEquals(url, card.id)
        assertEquals(url, card.watchUrlOverride)
        assertEquals("demo", card.title, "file name (extension stripped) is the card title")
        assertEquals("cdn.example.com", card.uploader)
    }

    @Test
    fun `direct hls url becomes a custom card keyed by that url`() {
        val url = "https://cdn.example.com/live/index.m3u8"
        val card = PastedMediaCards.fromQuery(url)!!
        assertTrue(card.isCustom)
        assertEquals(url, card.id)
        assertEquals(url, card.watchUrlOverride)
    }

    @Test
    fun `youtube watch url becomes a youTube card with extracted id`() {
        val card = PastedMediaCards.fromQuery("https://www.youtube.com/watch?v=dQw4w9WgXcQ")!!
        assertTrue(card.isYouTubeResult)
        assertEquals("dQw4w9WgXcQ", card.id)
        // The minimal YouTube card carries the watch URL as its title; the player resolves the id.
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", card.title)
    }

    @Test
    fun `bare youtube id becomes a youTube card`() {
        val card = PastedMediaCards.fromQuery("dQw4w9WgXcQ")!!
        assertTrue(card.isYouTubeResult)
        assertEquals("dQw4w9WgXcQ", card.id)
    }

    @Test
    fun `bilibili url keeps the bilibili platform badge`() {
        val url = "https://www.bilibili.com/video/BV1xx411c7mD"
        val card = PastedMediaCards.fromQuery(url)!!
        assertEquals(MediaPlatform.BILIBILI, card.platform)
        assertEquals(url, card.watchUrlOverride)
    }

    @Test
    fun `bilibili card without metadata falls back to the url display name`() {
        val url = "https://www.bilibili.com/video/BV1xx411c7mD"
        val card = PastedMediaCards.fromQuery(url)!!
        assertEquals("BV1xx411c7mD", card.title, "no cached metadata means the last path segment shows until it resolves")
        assertNull(card.thumbnailUrlOverride)
        assertFalse(card.isLive)
    }

    @Test
    fun `bilibili card with metadata carries the real title uploader thumbnail and live state`() {
        val source = MediaSource.from("https://www.bilibili.com/video/BV1xx411c7mD") as MediaSource.Bilibili
        val meta = PlatformVideoMetadata(
            title = "Some Real Title",
            uploader = "Some Up",
            thumbnailUrl = "https://i0.hdslb.com/bfs/archive/abc.jpg",
            viewCount = 1234L,
            durationSec = 3600L,
            isLive = false,
        )
        val card = PastedMediaCards.bilibiliResult(source, meta)
        assertEquals("Some Real Title", card.title)
        assertEquals("Some Up", card.uploader)
        assertEquals("https://i0.hdslb.com/bfs/archive/abc.jpg", card.thumbnailUrlOverride)
        assertEquals(1234L, card.viewCount)
        assertEquals(3600L, card.durationSec)
        assertEquals(source.url, card.watchUrlOverride)
        assertEquals(MediaPlatform.BILIBILI, card.platform)
    }

    @Test
    fun `bare bilibili bvid becomes a bilibili card`() {
        val card = PastedMediaCards.fromQuery("BV1xx411c7mD")!!
        assertEquals(MediaPlatform.BILIBILI, card.platform)
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", card.watchUrlOverride)
    }

    @Test
    fun `bare bilibili avid becomes a bilibili card`() {
        val card = PastedMediaCards.fromQuery("av170001")!!
        assertEquals(MediaPlatform.BILIBILI, card.platform)
        assertEquals("https://www.bilibili.com/video/av170001", card.watchUrlOverride)
    }

    @Test
    fun `bare bilibili episode id becomes a bilibili card`() {
        val card = PastedMediaCards.fromQuery("ep123456")!!
        assertEquals(MediaPlatform.BILIBILI, card.platform)
        assertEquals("https://www.bilibili.com/bangumi/play/ep123456", card.watchUrlOverride)
    }

    @Test
    fun `bare bilibili season id becomes a bilibili card`() {
        val card = PastedMediaCards.fromQuery("ss12345")!!
        assertEquals(MediaPlatform.BILIBILI, card.platform)
        assertEquals("https://www.bilibili.com/bangumi/play/ss12345", card.watchUrlOverride)
    }

    @Test
    fun `twitch channel url becomes a twitch card`() {
        val url = "https://www.twitch.tv/someone"
        val card = PastedMediaCards.fromQuery(url)!!
        assertTrue(card.isTwitch)
        assertEquals("channel:someone", card.id)
        assertEquals(url, card.watchUrlOverride)
    }

    @Test
    fun `vimeo url becomes a vimeo card`() {
        val url = "https://vimeo.com/123456789"
        val card = PastedMediaCards.fromQuery(url)!!
        assertEquals(MediaPlatform.VIMEO, card.platform)
        assertEquals(url, card.id)
        assertEquals(url, card.watchUrlOverride)
    }

    @Test
    fun `kick url becomes a kick card`() {
        val url = "https://kick.com/someone"
        val card = PastedMediaCards.fromQuery(url)!!
        assertEquals(MediaPlatform.KICK, card.platform)
        assertEquals(url, card.id)
        assertEquals(url, card.watchUrlOverride)
    }

    @Test
    fun `rtmp ingest url becomes a custom card`() {
        val url = "rtmp://live.example.com/app/stream"
        val card = PastedMediaCards.fromQuery(url)!!
        assertTrue(card.isCustom)
        assertEquals(url, card.id)
        assertEquals(url, card.watchUrlOverride)
    }

    @Test
    fun `plain search phrase stays a search - not a card`() {
        assertNull(PastedMediaCards.fromQuery("minecraft 生存 教程"))
    }

    @Test
    fun `url-looking search phrase with spaces stays a search`() {
        assertNull(PastedMediaCards.fromQuery("how to build https:// example com house"))
    }

    @Test
    fun `unsafe scheme is rejected and stays a search`() {
        assertNull(PastedMediaCards.fromQuery("javascript:alert(1)"))
    }

    @Test
    fun `empty query stays a search`() {
        assertNull(PastedMediaCards.fromQuery(""))
    }

    @Test
    fun `whitespace-only query stays a search`() {
        assertNull(PastedMediaCards.fromQuery("   "))
    }
}
