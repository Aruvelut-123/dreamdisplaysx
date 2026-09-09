package com.dreamdisplayx.media.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [MediaPlayer.isEarlyEos], the classifier that decides whether a normal-EOS
 * stream end is an early death (retry / re-resolve) or a genuine completion (ended-pause /
 * playlist advance).
 *
 * Regression context: a session that died a few hundred milliseconds into a fresh video with its
 * duration still unresolved fell through to the "genuine completion" branch, firing the ended-pause
 * ~0.5 s after every video switch ("new video plays 0.5 s then pauses itself").
 */
class MediaPlayerEarlyEosTest {
    private val g = 1_000_000_000L

    @Test
    fun `duration unresolved with a young clock is an early end`() {
        // 0.5 s into a video whose duration the resolver never reported (0.5 s auto-pause bug).
        assertTrue(MediaPlayer.isEarlyEos(positionNanos = g / 2, durationNanos = 0L))
        // A session that died before the first frame (clock at the origin).
        assertTrue(MediaPlayer.isEarlyEos(positionNanos = 0L, durationNanos = 0L))
        // 14.9 s is still inside the unresolved-duration guard window.
        assertTrue(MediaPlayer.isEarlyEos(positionNanos = (15L * g) - (100L * 1_000_000L), durationNanos = 0L))
    }

    @Test
    fun `duration unresolved with a mature clock is a genuine completion`() {
        // If the duration never resolved but the session genuinely ran 20 minutes, EOS is a
        // completion — retrying would loop the tail forever.
        assertFalse(MediaPlayer.isEarlyEos(positionNanos = 1_200L * g, durationNanos = 0L))
        assertFalse(MediaPlayer.isEarlyEos(positionNanos = 15_000L * g, durationNanos = 0L))
    }

    @Test
    fun `known duration keeps the original tail and ratio rules`() {
        val duration = 600L * g // 10 min
        // Deep inside the media: early.
        assertTrue(MediaPlayer.isEarlyEos(positionNanos = 500L * g, durationNanos = duration))
        // Just at the 90 % ratio boundary (exactly 540 s): 540 * 10 == 600 * 9 → NOT early.
        assertFalse(MediaPlayer.isEarlyEos(positionNanos = 540L * g, durationNanos = duration))
        // Inside the 5 s tail: completion.
        assertFalse(MediaPlayer.isEarlyEos(positionNanos = 597L * g, durationNanos = duration))
        // Very short media (< 15 s) is exempt from the early-EOS machinery entirely.
        assertFalse(MediaPlayer.isEarlyEos(positionNanos = 3L * g, durationNanos = 5L * g))
    }

    @Test
    fun `negative duration is treated as unresolved`() {
        assertTrue(MediaPlayer.isEarlyEos(positionNanos = 1L * g, durationNanos = -1L))
        assertFalse(MediaPlayer.isEarlyEos(positionNanos = 30L * g, durationNanos = -1L))
    }
}
