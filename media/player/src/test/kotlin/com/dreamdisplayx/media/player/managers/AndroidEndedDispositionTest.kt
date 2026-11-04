package com.dreamdisplayx.media.player.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the ENDED-vs-fragment-boundary arithmetic that stopped the AudioTrack budget from bleeding.
 *
 * The 1.10.8.2 field log showed a single display burning retirements at a metronomic rate while one
 * video played: `2/14 -> 4/14 -> 6/14 -> 8/14 -> 10/14` over ~80 seconds, i.e. two AudioTrack slots
 * every ~22 s. `startStreams()` ran exactly once and neither restart warning was logged, so the only
 * reachable trigger was [LibVlcSessionManager.beginSeek]'s `ENDED` branch firing at DASH fragment
 * boundaries. Only a real end of stream may take that path; everything else resumes in place.
 */
class AndroidEndedDispositionTest {

    // ── endSlackMs ────────────────────────────────────────────────────────────

    @Test
    fun short_media_uses_the_absolute_floor() {
        // 1 % of 40 s is 400 ms, well under the 1500 ms floor.
        assertEquals(AndroidEndedPolicy.MIN_END_SLACK_MS, AndroidEndedPolicy.endSlackMs(40_000L))
    }

    @Test
    fun long_media_uses_the_proportional_window() {
        // 1 % of a 3-hour stream is 108 s, far above the floor.
        assertEquals(108_000L, AndroidEndedPolicy.endSlackMs(10_800_000L))
    }

    @Test
    fun slack_is_never_below_the_floor_for_any_duration() {
        listOf(0L, 1L, 100L, 1_000L, 150_000L, 999_999L).forEach { duration ->
            assertTrue(
                AndroidEndedPolicy.endSlackMs(duration) >= AndroidEndedPolicy.MIN_END_SLACK_MS,
                "duration=$duration produced a slack below the floor",
            )
        }
    }

    // ── classify: real end of stream ──────────────────────────────────────────

    @Test
    fun position_at_the_duration_is_a_real_end() {
        assertEquals(EndedDisposition.REAL_END, AndroidEndedPolicy.classify(47_000L, 47_000L))
    }

    @Test
    fun position_past_the_duration_is_a_real_end() {
        assertEquals(EndedDisposition.REAL_END, AndroidEndedPolicy.classify(48_500L, 47_000L))
    }

    @Test
    fun position_just_inside_the_slack_is_a_real_end() {
        // A real EOS lands within the slack window; 46.9 s of 47 s is inside it.
        assertEquals(EndedDisposition.REAL_END, AndroidEndedPolicy.classify(46_900L, 47_000L))
    }

    @Test
    fun position_exactly_on_the_slack_boundary_is_a_real_end() {
        // Boundaries are inclusive: duration - slack counts as "at the end".
        val duration = 100_000L
        val boundary = duration - AndroidEndedPolicy.endSlackMs(duration)
        assertEquals(EndedDisposition.REAL_END, AndroidEndedPolicy.classify(boundary, duration))
    }

    // ── classify: fragment boundary (the bug) ────────────────────────────────

    @Test
    fun position_well_before_the_end_is_a_fragment_boundary() {
        // The reported scene: ENDED reported with the playhead at a fraction of the media length.
        assertEquals(EndedDisposition.FRAGMENT_BOUNDARY, AndroidEndedPolicy.classify(14_000L, 47_000L))
        assertEquals(EndedDisposition.FRAGMENT_BOUNDARY, AndroidEndedPolicy.classify(20_000L, 47_000L))
        assertEquals(EndedDisposition.FRAGMENT_BOUNDARY, AndroidEndedPolicy.classify(30_000L, 47_000L))
    }

    @Test
    fun position_one_millisecond_short_of_the_boundary_is_a_fragment_boundary() {
        val duration = 100_000L
        val boundary = duration - AndroidEndedPolicy.endSlackMs(duration)
        assertEquals(EndedDisposition.FRAGMENT_BOUNDARY, AndroidEndedPolicy.classify(boundary - 1L, duration))
    }

    @Test
    fun position_at_zero_is_a_fragment_boundary() {
        // DASH re-seeks land near the start of a following fragment; that is not a real end.
        assertEquals(EndedDisposition.FRAGMENT_BOUNDARY, AndroidEndedPolicy.classify(0L, 47_000L))
    }

    // ── classify: unknown duration ───────────────────────────────────────────

    @Test
    fun live_stream_without_a_length_is_unknown() {
        // `libvlc_media_player_get_length` answers 0 for a live stream.
        assertEquals(EndedDisposition.UNKNOWN_DURATION, AndroidEndedPolicy.classify(5_000L, 0L))
    }

    @Test
    fun negative_duration_is_unknown() {
        assertEquals(EndedDisposition.UNKNOWN_DURATION, AndroidEndedPolicy.classify(5_000L, -1L))
    }

    @Test
    fun unreadable_position_is_unknown_even_with_a_known_duration() {
        // `get_time` answers -1 while the demuxer is mid-flush; that must not read as "position 0".
        assertEquals(EndedDisposition.UNKNOWN_DURATION, AndroidEndedPolicy.classify(-1L, 47_000L))
    }

    @Test
    fun both_reads_failing_is_unknown() {
        assertEquals(EndedDisposition.UNKNOWN_DURATION, AndroidEndedPolicy.classify(-1L, -1L))
    }

    // ── shouldResumeInPlace: the decision that protects the budget ───────────

    @Test
    fun only_a_real_end_licenses_a_retirement() {
        assertFalse(AndroidEndedPolicy.shouldResumeInPlace(EndedDisposition.REAL_END))
        assertTrue(AndroidEndedPolicy.shouldResumeInPlace(EndedDisposition.FRAGMENT_BOUNDARY))
        assertTrue(AndroidEndedPolicy.shouldResumeInPlace(EndedDisposition.UNKNOWN_DURATION))
    }

    @Test
    fun every_non_real_end_disposition_resumes_in_place() {
        // Guards against a future enum member defaulting to the destructive path: a new member must
        // opt in to retirement explicitly, and this test forces that decision to be deliberate.
        EndedDisposition.entries
            .filter { it != EndedDisposition.REAL_END }
            .forEach { assertTrue(AndroidEndedPolicy.shouldResumeInPlace(it), "$it must resume in place") }
    }

    // ── budget interaction: proves the fix actually matters ─────────────────

    @Test
    fun fragment_boundaries_no_longer_exhaust_the_budget() {
        // Before the fix every ENDED retired two players. The field log burned 10 of 14 retirements
        // in ~80 seconds; at that rate a 4-minute video would have spent the entire budget.
        AndroidPlayerLedger.resetForTest()
        try {
            val duration = 240_000L // 4 minutes
            // A fragment boundary every ~22 s, exactly the observed cadence.
            var position = 22_000L
            var retirements = 0
            while (position < duration) {
                val disposition = AndroidEndedPolicy.classify(position, duration)
                if (AndroidEndedPolicy.shouldResumeInPlace(disposition)) {
                    // Resumed in place: no AudioTrack slot consumed.
                } else {
                    retirements += 2
                    AndroidPlayerLedger.record(2)
                }
                position += 22_000L
            }
            assertEquals(0, retirements, "no fragment boundary may be treated as a real end")
            assertFalse(AndroidPlayerLedger.exhausted, "the budget must survive a whole video")
        } finally {
            AndroidPlayerLedger.resetForTest()
        }
    }

    @Test
    fun a_real_end_still_restarts_and_costs_its_slots() {
        // The genuine loop/replay path must keep working: it is allowed to retire.
        AndroidPlayerLedger.resetForTest()
        try {
            val disposition = AndroidEndedPolicy.classify(240_000L, 240_000L)
            assertFalse(AndroidEndedPolicy.shouldResumeInPlace(disposition))
            AndroidPlayerLedger.record(2)
            assertEquals(2, AndroidPlayerLedger.retireCount)
        } finally {
            AndroidPlayerLedger.resetForTest()
        }
    }
}