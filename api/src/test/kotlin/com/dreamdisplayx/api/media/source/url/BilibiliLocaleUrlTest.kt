package com.dreamdisplayx.api.media.source.url

import com.dreamdisplayx.api.media.source.model.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers Bilibili share links that carry a locale prefix in the path.
 *
 * The Android share sheet emits `https://www.bilibili.com/zh-Hans/video/<BV...>?share_source=...`,
 * so the path segments are `["zh-Hans", "video", "BV..."]`. The parser used to read the route from
 * segment 0, saw `zh-Hans`, rejected the whole URL, and the caller fell back to `MediaSource.Remote`,
 * handing the raw HTML page to libvlc (the log showed a MPEG-PS demuxer chewing on `text/html`).
 */
class BilibiliLocaleUrlTest {

    @Test
    fun `zh-Hans prefixed video url parses the bvid`() {
        val source = assertIs<MediaSource.Bilibili>(
            MediaSource.from("https://www.bilibili.com/zh-Hans/video/BV1UY97BwEZu/?share_source=copy_web"),
        )
        assertEquals("BV1UY97BwEZu", source.bvid)
    }

    @Test
    fun `every locale variant is stripped before route parsing`() {
        for (locale in listOf("zh-Hans", "zh-CN", "zh-TW", "en", "ja")) {
            val source = assertIs<MediaSource.Bilibili>(
                MediaSource.from("https://www.bilibili.com/$locale/video/BV1xx411c7mD"),
                "locale $locale was not recognized",
            )
            assertEquals("BV1xx411c7mD", source.bvid, "locale $locale lost the bvid")
        }
    }

    @Test
    fun `locale prefixed avid url parses the avid`() {
        val source = assertIs<MediaSource.Bilibili>(
            MediaSource.from("https://www.bilibili.com/zh-Hans/video/av170001"),
        )
        assertEquals(170001L, source.avid)
    }

    @Test
    fun `locale prefixed bangumi url parses the episode id`() {
        val source = assertIs<MediaSource.Bilibili>(
            MediaSource.from("https://www.bilibili.com/zh-Hans/bangumi/play/ep123456"),
        )
        assertEquals(123456L, source.epId)
    }

    @Test
    fun `share query string does not disturb the parse`() {
        val source = assertIs<MediaSource.Bilibili>(
            MediaSource.from(
                "https://www.bilibili.com/zh-Hans/video/BV1UY97BwEZu/?share_source=copy_web" +
                    "&vd_source=abc123&spm_id_from=333.1007.tianma.1-1-1.click",
            ),
        )
        assertEquals("BV1UY97BwEZu", source.bvid)
    }

    @Test
    fun `a plain route is never mistaken for a locale prefix`() {
        // "/video/..." and "/bangumi/..." must keep their first segment even though a bare
        // two-letter locale would otherwise match the prefix pattern.
        val video = assertIs<MediaSource.Bilibili>(
            MediaSource.from("https://www.bilibili.com/video/BV1xx411c7mD"),
        )
        assertEquals("BV1xx411c7mD", video.bvid)
        assertEquals(null, BilibiliUrls.parse("https://www.bilibili.com/zh-Hans/channel/123"))
    }
}