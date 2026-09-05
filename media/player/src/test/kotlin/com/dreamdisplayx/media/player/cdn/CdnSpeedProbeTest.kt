package com.dreamdisplayx.media.player.cdn

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the CDN early-failure penalty: a host that dropped a live stream
 * mid-play is excluded from ranking for a cooldown window, and the score-cache entry is
 * dropped so the next probe re-ranks it. No network or libvlc involved.
 */
class CdnSpeedProbeTest {

    @Test
    fun `penalized host is not usable until cooldown elapses`() {
        val url = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1.m4s"
        assertTrue(CdnSpeedProbe.isHostUsable(url), "host should start usable")

        CdnSpeedProbe.penalizeHost(url)
        assertFalse(CdnSpeedProbe.isHostUsable(url), "penalized host must be excluded")
    }

    @Test
    fun `non-bilibili or unknown hosts are never penalized`() {
        CdnSpeedProbe.penalizeHost(null)
        CdnSpeedProbe.penalizeHost("not a url")
        assertTrue(CdnSpeedProbe.isHostUsable(null))
        assertTrue(CdnSpeedProbe.isHostUsable("not a url"))
        assertTrue(CdnSpeedProbe.isHostUsable("https://example.com/video.mp4"))
    }

    @Test
    fun `penalizeHost is idempotent`() {
        val url = "https://upos-tf-all-hw.bilivideo.com/upgcxcode/2.m4s"
        CdnSpeedProbe.penalizeHost(url)
        CdnSpeedProbe.penalizeHost(url)
        assertFalse(CdnSpeedProbe.isHostUsable(url))
    }
}
