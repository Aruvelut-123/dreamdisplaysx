package com.dreamdisplayx.media.player.managers

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [classifySeekLanding], the two-sample clock classifier that decides whether a
 * seek landed, was dropped by libvlc (old timeline still playing), or is legitimately flushing.
 *
 * Regression context: the old verifier re-applied `set_time` whenever the video clock was far from
 * the target after a fixed delay. A legitimate far-seek flush also leaves the clock far from the
 * target (the demuxer is still refilling), so the re-assertion restarted the ongoing flush and
 * made landing slower. Classifying by clock MOTION (advancing vs stalled) separates the two.
 */
class SeekLandingClassifierTest {
    private val tol = 1_200L
    private val advance = 300L

    @Test
    fun `clock at the target is landed`() {
        // Sample already exactly at the target.
        assertEquals(SeekLanding.LANDED, classifySeekLanding(60_000, 60_000, 60_000, tol, advance))
        // Within tolerance on either side.
        assertEquals(SeekLanding.LANDED, classifySeekLanding(59_900, 60_100, 60_000, tol, advance))
        assertEquals(SeekLanding.LANDED, classifySeekLanding(60_500, 60_200, 60_000, tol, advance))
        assertEquals(SeekLanding.LANDED, classifySeekLanding(59_500, 59_800, 60_000, tol, advance))
    }

    @Test
    fun `clock that reaches the target between samples is landed`() {
        // First sample far away, second sample on the target: seek landed during the sample gap.
        assertEquals(SeekLanding.LANDED, classifySeekLanding(55_000, 60_000, 60_000, tol, advance))
        assertEquals(SeekLanding.LANDED, classifySeekLanding(60_000, 55_000, 60_000, tol, advance))
    }

    @Test
    fun `advancing clock far from target is dropped`() {
        // Old timeline still playing at ~1x: 30s -> 30.5s while target is 463s.
        assertEquals(SeekLanding.DROPPED, classifySeekLanding(30_000, 30_500, 463_000, tol, advance))
        // Backwards seek dropped: clock ahead of the target and still advancing forward.
        assertEquals(SeekLanding.DROPPED, classifySeekLanding(200_000, 200_600, 60_000, tol, advance))
        // Exactly at the advance threshold is a drop (>=).
        assertEquals(SeekLanding.DROPPED, classifySeekLanding(30_000, 30_300, 463_000, tol, advance))
    }

    @Test
    fun `stalled clock far from target is flushing`() {
        // Clock essentially frozen while the demuxer refills: re-asserting would restart the flush.
        assertEquals(SeekLanding.FLUSHING, classifySeekLanding(30_000, 30_050, 463_000, tol, advance))
        assertEquals(SeekLanding.FLUSHING, classifySeekLanding(30_000, 30_000, 463_000, tol, advance))
        // Slow crawl (buffering jitter) is still a flush, not a dropped seek.
        assertEquals(SeekLanding.FLUSHING, classifySeekLanding(30_000, 30_120, 463_000, tol, advance))
        // Clock moving backwards (seek applied but decoder rewinding) is a flush.
        assertEquals(SeekLanding.FLUSHING, classifySeekLanding(30_000, 29_900, 463_000, tol, advance))
    }

    @Test
    fun `failed clock reads never trigger a reassertion`() {
        assertEquals(SeekLanding.FLUSHING, classifySeekLanding(-1L, 30_500, 463_000, tol, advance))
        assertEquals(SeekLanding.FLUSHING, classifySeekLanding(30_000, -1L, 463_000, tol, advance))
        assertEquals(SeekLanding.FLUSHING, classifySeekLanding(-1L, -1L, 463_000, tol, advance))
        assertEquals(SeekLanding.FLUSHING, classifySeekLanding(30_000, 30_500, -1L, tol, advance))
    }
}
