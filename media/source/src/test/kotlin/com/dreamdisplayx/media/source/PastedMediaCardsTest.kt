@file:OptIn(DreamDisplaysXUnstableApi::class)

package com.dreamdisplayx.media.source

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.source.model.MediaPlatform
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
