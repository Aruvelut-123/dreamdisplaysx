package com.dreamdisplayx.media.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure-JVM tests for [MediaPlayer.resumeOffsetFor] — the VOD tail-resume guard. */
class MediaPlayerResumeOffsetTest {
    private val second = 1_000_000_000L

    @Test
    fun `normal mid-video offset passes through`() {
        val duration = 10 * 60 * second
        assertEquals(5 * 60 * second, MediaPlayer.resumeOffsetFor(5 * 60 * second, duration))
    }

    @Test
    fun `offset at the very end restarts from zero`() {
        val duration = 10 * 60 * second
        assertEquals(0L, MediaPlayer.resumeOffsetFor(duration, duration))
    }

    @Test
    fun `offset within the tail guard restarts from zero`() {
        val duration = 10 * 60 * second
        // SEEK_END_GUARD_NANOS is 500ms; a position 200ms before the end must restart.
        assertEquals(0L, MediaPlayer.resumeOffsetFor(duration - 200_000_000L, duration))
    }

    @Test
    fun `offset just before the tail guard passes through`() {
        val duration = 10 * 60 * second
        // 600ms before the end is outside the guard -> keep the requested position.
        val kept = duration - 600_000_000L
        assertEquals(kept, MediaPlayer.resumeOffsetFor(kept, duration))
    }

    @Test
    fun `negative duration is treated as unknown`() {
        assertEquals(123L, MediaPlayer.resumeOffsetFor(123L, -1L))
    }

    @Test
    fun `unknown duration never rewinds`() {
        assertEquals(123L, MediaPlayer.resumeOffsetFor(123L, 0L))
        assertEquals(123L, MediaPlayer.resumeOffsetFor(123L, -1L))
    }

    @Test
    fun `tail position is a stale marker`() {
        val duration = 10 * 60 * second
        // SEEK_END_GUARD_NANOS is 500ms; the exact seek-clamp ceiling must be flagged.
        assertTrue(MediaPlayer.isStaleTailPosition(duration - 500_000_000L, duration))
        assertTrue(MediaPlayer.isStaleTailPosition(duration, duration))
    }

    @Test
    fun `position before the tail is not stale`() {
        val duration = 10 * 60 * second
        assertFalse(MediaPlayer.isStaleTailPosition(duration - 600_000_000L, duration))
        assertFalse(MediaPlayer.isStaleTailPosition(0L, duration))
    }

    @Test
    fun `unknown duration is never stale`() {
        assertFalse(MediaPlayer.isStaleTailPosition(123L, 0L))
        assertFalse(MediaPlayer.isStaleTailPosition(123L, -1L))
    }
}