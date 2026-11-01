package com.dreamdisplayx.media.source.bilibili

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the Bilibili panorama (360/VR) detection rules.
 *
 * The cost of a wrong "yes" is high — a video that would have played fine gets refused — so the
 * shape test alone must never be enough and the tag test must not fire on incidental words like
 * "vrchat" or "360p".
 */
class BilibiliPanoramaTest {

    @Test
    fun `a 2 to 1 frame is treated as equirectangular`() {
        assertTrue(BilibiliApi.isEquirectangularFrame(1920, 960))
        assertTrue(BilibiliApi.isEquirectangularFrame(3840, 1920))
        assertTrue(BilibiliApi.isEquirectangularFrame(2160, 1080))
    }

    @Test
    fun `a portrait panorama is recognised by its long edge`() {
        assertTrue(BilibiliApi.isEquirectangularFrame(960, 1920))
    }

    @Test
    fun `ordinary video frames are not equirectangular`() {
        assertFalse(BilibiliApi.isEquirectangularFrame(1920, 1080), "16:9 must not look like a panorama")
        assertFalse(BilibiliApi.isEquirectangularFrame(1080, 1920), "9:16 portrait must not either")
        assertFalse(BilibiliApi.isEquirectangularFrame(2560, 1440), "16:9 1440p")
        assertFalse(BilibiliApi.isEquirectangularFrame(1920, 1036), "2.39:1 cinema scope is not 2:1")
    }

    @Test
    fun `degenerate dimensions are never equirectangular`() {
        assertFalse(BilibiliApi.isEquirectangularFrame(null, 1080))
        assertFalse(BilibiliApi.isEquirectangularFrame(1920, null))
        assertFalse(BilibiliApi.isEquirectangularFrame(0, 960))
        assertFalse(BilibiliApi.isEquirectangularFrame(1920, 0))
    }

    @Test
    fun `panorama tags are recognised in both languages`() {
        assertTrue(BilibiliApi.hasPanoramicTag(listOf("VR")))
        assertTrue(BilibiliApi.hasPanoramicTag(listOf("vr")))
        assertTrue(BilibiliApi.hasPanoramicTag(listOf("  VR  ")))
        assertTrue(BilibiliApi.hasPanoramicTag(listOf("vr 视频")))
        assertTrue(BilibiliApi.hasPanoramicTag(listOf("全景视频")))
        assertTrue(BilibiliApi.hasPanoramicTag(listOf("Panorama")))
        assertTrue(BilibiliApi.hasPanoramicTag(listOf("360°全景")))
        assertTrue(BilibiliApi.hasPanoramicTag(listOf("360度")))
    }

    @Test
    fun `incidental tags do not count as panoramas`() {
        assertFalse(BilibiliApi.hasPanoramicTag(emptyList()))
        assertFalse(BilibiliApi.hasPanoramicTag(listOf("VRChat")), "'vrchat' is not a panorama marker")
        assertFalse(BilibiliApi.hasPanoramicTag(listOf("Vroid")))
        assertFalse(BilibiliApi.hasPanoramicTag(listOf("360p")), "'360p' is a resolution, not a projection")
        assertFalse(BilibiliApi.hasPanoramicTag(listOf("日常", "音乐")))
    }
}