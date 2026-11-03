package com.dreamdisplayx.media.player.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the Android AudioTrack retirement budget.
 *
 * The rule exists because this fork can never release a retired libvlc player (VLC's JNI TLS
 * destructor crashes on Android), so each retirement permanently holds an OpenSL ES AudioTrack
 * slot. A tester's `libvlc.log` on issue #4 lost sound for the rest of the session right after
 * the 25th successful `using audio output module "opensles_android"`; every later attempt failed
 * with `AudioTrack jni init failed` and never recovered. Retiring players without a budget
 * therefore guarantees eventual permanent silence.
 */
class AudioTrackBudgetTest {
    @Test
    fun `the budget stays inside Android's per-process AudioTrack cap`() {
        val cap = AudioTrackBudget.ANDROID_AUDIO_TRACK_CAP
        val reserved = AudioTrackBudget.RESERVED_SLOTS
        val perRetire = AudioTrackBudget.SLOTS_PER_RETIRE
        val worstCase = reserved + AudioTrackBudget.retireBudget * perRetire
        assertTrue(
            worstCase <= cap,
            "spending the whole budget must stay under $cap slots, was $worstCase",
        )
    }

    @Test
    fun `the budget leaves room for other AudioTrack users`() {
        assertTrue(
            AudioTrackBudget.retireBudget * AudioTrackBudget.SLOTS_PER_RETIRE <
                AudioTrackBudget.ANDROID_AUDIO_TRACK_CAP,
            "a budget that consumes the entire cap would silence the game's own sound engine",
        )
    }

    @Test
    fun `the budget is at least one retirement`() {
        assertTrue(
            AudioTrackBudget.retireBudget >= 1,
            "a session must always be able to replace a broken player once",
        )
    }

    @Test
    fun `retiring is allowed until the budget is reached`() {
        val budget = AudioTrackBudget.retireBudget
        assertTrue(AudioTrackBudget.allowsRetire(0, budget))
        assertTrue(AudioTrackBudget.allowsRetire(budget - 1, budget))
        assertFalse(AudioTrackBudget.allowsRetire(budget, budget))
        assertFalse(AudioTrackBudget.allowsRetire(budget + 5, budget))
    }

    @Test
    fun `a spent budget reports no remaining slots`() {
        val budget = AudioTrackBudget.retireBudget
        assertEquals(0, AudioTrackBudget.remainingSlots(budget, budget))
        assertEquals(0, AudioTrackBudget.remainingSlots(budget + 3, budget))
    }

    @Test
    fun `remaining slots shrink by one retirement each step`() {
        val budget = AudioTrackBudget.retireBudget
        val perRetire = AudioTrackBudget.SLOTS_PER_RETIRE
        val start = AudioTrackBudget.remainingSlots(0, budget)
        var previous = start
        for (retired in 1..budget) {
            val current = AudioTrackBudget.remainingSlots(retired, budget)
            assertEquals(previous - perRetire, current, "step $retired must free exactly $perRetire slots")
            previous = current
        }
        assertEquals(0, previous)
    }

    @Test
    fun `a zero budget never allows retiring`() {
        assertFalse(AudioTrackBudget.allowsRetire(0, 0))
    }

    @Test
    fun `the process ledger ignores retirements that spent nothing`() {
        AndroidPlayerLedger.resetForTest()

        assertFalse(AndroidPlayerLedger.exhausted, "a fresh process has the whole budget")

        // The field bug this pins: `start()` retires before it has created anything, so a session
        // with no live player used to burn budget it never spent (`1/14 -> 4/14` jumps).
        assertEquals(0, AndroidPlayerLedger.record(0))
        assertEquals(0, AndroidPlayerLedger.record(-1))
        assertEquals(0, AndroidPlayerLedger.retireCount)
    }

    @Test
    fun `the process ledger accumulates across sessions`() {
        AndroidPlayerLedger.resetForTest()
        val budget = AudioTrackBudget.retireBudget

        // Two "sessions" retiring one player each: the total must add up, because the AudioTrack
        // slots belong to the process, not to the LibVlcSessionManager instance that took them.
        AndroidPlayerLedger.record(1)
        val after = AndroidPlayerLedger.record(1)

        assertEquals(2, after)
        assertEquals(2, AndroidPlayerLedger.retireCount)
        assertFalse(AndroidPlayerLedger.exhausted, "two retirements are far inside a $budget budget")
    }

    @Test
    fun `the process ledger reports exhaustion exactly at the budget`() {
        AndroidPlayerLedger.resetForTest()
        val budget = AudioTrackBudget.retireBudget

        AndroidPlayerLedger.record(budget - 1)
        assertFalse(AndroidPlayerLedger.exhausted, "one retirement must still fit")

        AndroidPlayerLedger.record(1)
        assertTrue(
            AndroidPlayerLedger.exhausted,
            "spending the budget must stop further retirements, or the process hits Android's cap",
        )
        AndroidPlayerLedger.resetForTest()
    }
}
