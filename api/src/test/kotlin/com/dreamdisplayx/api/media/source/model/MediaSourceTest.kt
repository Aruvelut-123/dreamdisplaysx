@file:OptIn(DreamDisplaysXUnstableApi::class)

package com.dreamdisplayx.api.media.source.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MediaSourceTest {
    @Test
    fun twitchChannelUrlParsesChannel() {
        val url = "https://www.twitch.tv/somechannel"
        val source = assertIs<MediaSource.Twitch>(MediaSource.from(url))
        assertEquals("somechannel", source.channel)
        assertNull(source.videoId)
        assertNull(source.clipSlug)
        assertEquals(url, source.toResolvableUrl())
    }

    @Test
    fun twitchVodUrlParsesVideoId() {
        val url = "https://www.twitch.tv/videos/123456789"
        val source = assertIs<MediaSource.Twitch>(MediaSource.from(url))
        assertNull(source.channel)
        assertEquals("123456789", source.videoId)
        assertNull(source.clipSlug)
        assertEquals(url, source.toResolvableUrl())
    }

    @Test
    fun twitchClipUrlParsesClipSlug() {
        val url = "https://clips.twitch.tv/AwesomeClipSlug"
        val source = assertIs<MediaSource.Twitch>(MediaSource.from(url))
        assertNull(source.channel)
        assertNull(source.videoId)
        assertEquals("AwesomeClipSlug", source.clipSlug)
        assertEquals(url, source.toResolvableUrl())
    }

    @Test
    fun youTubeUrlStillParsesAsYouTube() {
        val source = assertIs<MediaSource.YouTube>(MediaSource.from("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", source.videoId)
    }

    @Test
    fun bilibiliBangumiEpisodeUrlParsesEpId() {
        val url = "https://www.bilibili.com/bangumi/play/ep374717"
        val source = assertIs<MediaSource.Bilibili>(MediaSource.from(url))
        assertEquals(374717L, source.epId)
        assertNull(source.seasonId)
        assertNull(source.bvid)
        assertNull(source.roomId)
    }

    @Test
    fun bilibiliBangumiSeasonUrlParsesSeasonId() {
        val url = "https://www.bilibili.com/bangumi/play/ss40007"
        val source = assertIs<MediaSource.Bilibili>(MediaSource.from(url))
        assertEquals(40007L, source.seasonId)
        assertNull(source.epId)
        assertNull(source.bvid)
    }

    @Test
    fun rtmpIngestUrlParsesAsIngest() {
        val url = "rtmp://media.example.com/live/cast"
        val source = assertIs<MediaSource.Ingest>(MediaSource.from(url))
        assertEquals(url, source.url)
        assertEquals(url, source.toResolvableUrl())
    }

    @Test
    fun srtIngestUrlParsesAsIngest() {
        val url = "srt://media.example.com:9000?streamid=cast"
        val source = assertIs<MediaSource.Ingest>(MediaSource.from(url))
        assertEquals(url, source.url)
    }

    /** An unrecognized page still goes to the extractor chain via [MediaSource.Remote]. */
    @Test
    fun unknownHostFallsBackToRemote() {
        val url = "https://example.com/watch/some-video"
        val source = assertIs<MediaSource.Remote>(MediaSource.from(url))
        assertEquals(url, source.url)
    }

    /** A URL that names a media file skips the extractors entirely (see [com.dreamdisplayx.api.media.source.url.CustomMediaUrls]). */
    @Test
    fun unknownHostWithMediaFileParsesAsDirectStream() {
        val url = "https://example.com/video.mp4"
        val source = assertIs<MediaSource.DirectStream>(MediaSource.from(url))
        assertEquals(url, source.streamUrl)
        assertEquals(CustomMediaKind.PROGRESSIVE, source.kind)
        assertEquals(url, source.toResolvableUrl())
    }
}
