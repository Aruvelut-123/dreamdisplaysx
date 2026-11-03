/*
 * Dream DisplaysX - process-wide Android AudioTrack ledger.
 *
 * VLC-Android's OpenSL ES audio output allocates one AudioTrack per media player and Android caps a
 * process at 32 AudioTrack instances. This fork cannot release a retired libvlc player (releasing
 * trips VLC's JNI TLS destructor and crashes the process; see AGENTS.md), so every retired player
 * permanently holds its AudioTrack slot for the rest of the PROCESS.
 *
 * The budget used to be a per-session field (`LibVlcSessionManager.androidRetireCount`), which made
 * the accounting useless: `DisplayMediaController` builds a brand-new `MediaPlayer` — and therefore a
 * brand-new `LibVlcSessionManager` — for every video change, so the counter restarted at zero each
 * time. A 1.10.8.2 tester log shows 18 retirement lines all reading `1/14`, with the occasional `4/14`
 * inside one session, while the process was leaking two AudioTrack slots per line. The wall arrives
 * exactly as reported: sound works, then goes silent for the rest of the process.
 *
 * This object keeps the counter where the resource actually lives. It is deliberately free of
 * libvlc/JNA so `AudioTrackBudgetTest` can pin the arithmetic and the ledger can be reset per test.
 */
package com.dreamdisplayx.media.player.managers

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide count of Android native player retirements, shared by every session in this JVM.
 *
 * Retiring is a *process-level* budget: a new display inheriting a fresh session must not be handed
 * a fresh allowance, because the AudioTrack slots the previous session burned are still taken.
 */
internal object AndroidPlayerLedger {
    private val retired = AtomicInteger(0)

    /** Retirements performed by this process so far. */
    val retireCount: Int get() = retired.get()

    /** True once no further retirement fits the process-wide budget. */
    val exhausted: Boolean get() = !AudioTrackBudget.allowsRetire(retired.get())

    /**
     * Records [players] retirements and returns the new total.
     *
     * The caller passes how many native players it actually retired, because a session with no live
     * player (e.g. the very first `start()`, which retires before creating anything) must not consume
     * budget it never spent — that miscount is what produced the `1/14 → 4/14` jumps in the field log.
     */
    fun record(players: Int): Int {
        if (players <= 0) return retired.get()
        return retired.addAndGet(players)
    }

    /** Test hook: forget every retirement this process recorded. */
    fun resetForTest() {
        retired.set(0)
    }
}
