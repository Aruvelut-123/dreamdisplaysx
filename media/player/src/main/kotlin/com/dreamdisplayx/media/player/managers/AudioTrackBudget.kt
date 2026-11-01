/*
 * Dream DisplaysX - Android AudioTrack budget.
 *
 * VLC-Android's OpenSL ES audio output allocates one AudioTrack per media player, and Android
 * caps a process at 32 AudioTrack instances. This fork cannot release a retired libvlc player
 * (releasing trips VLC's JNI TLS destructor and crashes the process; see AGENTS.md), so every
 * player retirement permanently holds its AudioTrack slot for the rest of the session.
 *
 * A tester's `libvlc.log` (issue #4, 1.20.1 on an Android launcher) showed the exact wall:
 * `creating audio output` 3354 times, only 25 of them reaching `using audio output module
 * "opensles_android"`, then a single `FindClass(android/media/AudioTrack) failed` followed by
 * 3329 consecutive `AudioTrack jni init failed` lines with no recovery. Sound therefore worked
 * for the first stretch of the session and then vanished permanently - exactly the reported
 * "it starts fine, then there is no sound at all".
 *
 * The accounting here is pure arithmetic so the policy can be unit-tested without libvlc.
 */
package com.dreamdisplayx.media.player.managers

/**
 * Pure accounting for the Android AudioTrack slots a session is allowed to burn.
 *
 * Kept free of libvlc/JNA so [AudioTrackBudgetTest] can pin the policy. The manager asks
 * [retireBudget] once for its cap and [allowsRetire] before every native player retirement.
 */
internal object AudioTrackBudget {
    /** Android's hard per-process limit on live AudioTrack instances. */
    const val ANDROID_AUDIO_TRACK_CAP = 32

    /**
     * Slots kept free for AudioTrack users outside this session: the game's own sound engine, a
     * second display, and one spare so a transient allocation never lands exactly on the cap.
     */
    const val RESERVED_SLOTS = 4

    /**
     * AudioTrack slots one retirement can cost. A session owns a video player and, for Bilibili
     * DASH, a separate audio-only player; retiring both consumes two slots.
     */
    const val SLOTS_PER_RETIRE = 2

    /**
     * How many retirements are affordable while leaving [RESERVED_SLOTS] free.
     *
     * 32 cap - 4 reserved = 28 usable slots / 2 per retirement = 14, floored at 1 so a session
     * can always replace a broken player at least once.
     */
    val retireBudget: Int
        get() = ((ANDROID_AUDIO_TRACK_CAP - RESERVED_SLOTS) / SLOTS_PER_RETIRE).coerceAtLeast(1)

    /**
     * Whether another retirement fits the budget.
     *
     * [retired] counts retirements already performed, so `allowsRetire(retireBudget - 1)` is the
     * last call that still fits.
     */
    fun allowsRetire(retired: Int, budget: Int = retireBudget): Boolean = retired < budget

    /**
     * AudioTrack slots still available to this session after [retired] retirements.
     *
     * Never negative: once the budget is spent the answer is zero, which is what makes the
     * manager fall back to in-place reuse instead of asking for a slot that is gone.
     */
    fun remainingSlots(retired: Int, budget: Int = retireBudget): Int =
        ((budget - retired) * SLOTS_PER_RETIRE).coerceAtLeast(0)
}
