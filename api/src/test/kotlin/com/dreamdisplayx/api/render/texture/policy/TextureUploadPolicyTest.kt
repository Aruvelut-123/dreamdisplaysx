package com.dreamdisplayx.api.render.texture.policy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the two liveness rules of the upload path.
 *
 * Both are unit-testable precisely because they were kept free of OpenGL and Minecraft types; the
 * failures they guard against are otherwise only observable by eye, on a real device, as "the picture
 * stopped but the sound did not".
 */
class TextureUploadPolicyTest {

    private val ms = 1_000_000L
    private val showNow = Long.MIN_VALUE
    private val maxWait = 2_000 * ms

    /** A frame inside the tolerance window is due; one clearly ahead of it is held. */
    @Test
    fun `a frame inside the horizon is due and one ahead of it is held`() {
        assertTrue(
            TextureUploadPolicy.shouldReleaseAheadOfClock(
                pts = 100 * ms, horizon = 100 * ms, waitedNanos = 0L,
                showNowMarker = showNow, maxHeadWaitNanos = maxWait,
            ),
            "A frame exactly at the horizon is due.",
        )
        assertTrue(
            TextureUploadPolicy.shouldReleaseAheadOfClock(
                pts = 50 * ms, horizon = 100 * ms, waitedNanos = 0L,
                showNowMarker = showNow, maxHeadWaitNanos = maxWait,
            ),
            "An overdue frame is due.",
        )
        assertFalse(
            TextureUploadPolicy.shouldReleaseAheadOfClock(
                pts = 900 * ms, horizon = 100 * ms, waitedNanos = 0L,
                showNowMarker = showNow, maxHeadWaitNanos = maxWait,
            ),
            "A frame well ahead of the horizon must wait for its slot.",
        )
    }

    /** A frame stamped "due at once" is never held, whatever the horizon says. */
    @Test
    fun `a show-now frame is always released`() {
        assertTrue(
            TextureUploadPolicy.shouldReleaseAheadOfClock(
                pts = showNow, horizon = 0L, waitedNanos = 0L,
                showNowMarker = showNow, maxHeadWaitNanos = maxWait,
            ),
        )
    }

    /**
     * The bounded-wait escape hatch: a frame the clock never reaches becomes due once it has waited
     * long enough, so a clock/timestamp disagreement cannot freeze the picture permanently.
     */
    @Test
    fun `a frame ahead of a stalled clock is released once its wait is exhausted`() {
        assertFalse(
            TextureUploadPolicy.shouldReleaseAheadOfClock(
                pts = 600 * ms, horizon = 100 * ms, waitedNanos = 1_999 * ms,
                showNowMarker = showNow, maxHeadWaitNanos = maxWait,
            ),
            "One millisecond short of the bound the frame is still held.",
        )
        assertTrue(
            TextureUploadPolicy.shouldReleaseAheadOfClock(
                pts = 600 * ms, horizon = 100 * ms, waitedNanos = 2_000 * ms,
                showNowMarker = showNow, maxHeadWaitNanos = maxWait,
            ),
            "At the bound the frame is released regardless of the clock.",
        )
        assertTrue(
            TextureUploadPolicy.shouldReleaseAheadOfClock(
                pts = 600 * ms, horizon = 100 * ms, waitedNanos = 60_000 * ms,
                showNowMarker = showNow, maxHeadWaitNanos = maxWait,
            ),
            "A long-frozen frame must never stay queued indefinitely.",
        )
    }

    /**
     * A horizon derived from an unavailable clock (`Long.MAX_VALUE`) makes every frame due, which is
     * the pre-display-sync behaviour and must not be turned into a hold by the wait bound.
     */
    @Test
    fun `an unbounded horizon leaves nothing held`() {
        assertTrue(
            TextureUploadPolicy.shouldReleaseAheadOfClock(
                pts = 3_600_000 * ms, horizon = Long.MAX_VALUE, waitedNanos = 0L,
                showNowMarker = showNow, maxHeadWaitNanos = maxWait,
            ),
        )
    }
}