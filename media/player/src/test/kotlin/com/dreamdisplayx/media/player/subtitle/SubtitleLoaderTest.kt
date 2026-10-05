package com.dreamdisplayx.media.player.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SubtitleLoaderTest {
    @Test
    fun loadsBoundedVodWebVttSegmentsOnTheMediaTimeline() {
        val root = "http://127.0.0.1/cast/test/index.m3u8"
        val first = "http://127.0.0.1/cast/test/one.vtt"
        val second = "http://127.0.0.1/cast/test/two.vtt"
        val documents = mapOf(
            root to """
                #EXTM3U
                #EXT-X-TARGETDURATION:2
                #EXTINF:2.0,
                one.vtt
                #EXTINF:2.0,
                two.vtt
                #EXT-X-ENDLIST
            """.trimIndent(),
            first to """
                WEBVTT
                X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:180000

                00:00:00.500 --> 00:00:01.500
                Hello
            """.trimIndent(),
            second to """
                WEBVTT
                X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:360000

                00:00:02.500 --> 00:00:03.500
                World
            """.trimIndent(),
        )
        val loader = SubtitleLoader(
            fetch = { url -> SubtitleLoader.Document(url, documents.getValue(url)) },
            resolve = { it },
        )
        assertEquals(0L to 2_000_000_000L, loader.timestampMap(documents.getValue(first)))

        val cues = loader.load(root)

        assertEquals(listOf("Hello", "World"), cues.map { it.text })
        assertEquals(500_000_000L, cues[0].startNanos)
        assertEquals(4_500_000_000L, cues[1].startNanos)
    }

    @Test
    fun rejectsLiveAndEncryptedSubtitlePlaylists() {
        fun loader(text: String) = SubtitleLoader(
            fetch = { url -> SubtitleLoader.Document(url, text) },
            resolve = { it },
        )
        val live = "#EXTM3U\n#EXTINF:2,\none.vtt\n"
        val encrypted = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:2,\none.vtt\n#EXT-X-ENDLIST\n"

        assertFailsWith<IllegalArgumentException> { loader(live).load("http://127.0.0.1/cast/test/live.m3u8") }
        assertFailsWith<IllegalArgumentException> { loader(encrypted).load("http://127.0.0.1/cast/test/encrypted.m3u8") }
    }
}
