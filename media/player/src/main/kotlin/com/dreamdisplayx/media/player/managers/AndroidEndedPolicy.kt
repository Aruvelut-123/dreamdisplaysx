/*
 * Dream DisplaysX - ENDED-vs-fragment-boundary classification.
 *
 * A field `latest.log` from 1.10.8.2 (f6803356) on a 1.20.1 Forge / Android launcher showed the
 * AudioTrack budget bleeding at a metronomic rate while a single display played one video:
 *
 *   retirements: 2/14  3s after the first frame
 *   retirements: 4/14  +12s
 *   retirements: 6/14  +20s
 *   retirements: 8/14  +23s
 *   retirements: 10/14 +23s        -> only 8 AudioTrack slots left after ~80 seconds
 *
 * Two facts pinned the trigger to [beginSeek]'s `ENDED` branch:
 *   1. `startStreams() cold start` appears exactly once (it is an unconditional INFO line), and
 *      neither `fell back to a full stream restart` nor `dead session; restarting at target` was
 *      logged, so the restart path and the seek-fallback path never ran.
 *   2. `libvlc.log` pairs every `creating audio output` (12 total in this log, 0 `destroying audio
 *      output`) with a fragment seek, and shows `end of stream` 26 times at the DASH segment
 *      boundaries while the demuxer reports `ENDED` far short of the media length.
 *
 * A Bilibili DASH rendition is a playlist of `moof` fragments; libvlc reports ENDED at some
 * fragment boundaries even though the render position is nowhere near the media end. Treating that
 * as a real end of stream retires both native players, and each retirement permanently holds an
 * AudioTrack slot on this build (players can never be released), so a few minutes of playback
 * exhaust the process-wide budget and the display goes silent for good.
 *
 * The classification is pure arithmetic on purpose: [LibVlcSessionManager] owns the JNA calls, so
 * this file stays free of libvlc and `AndroidEndedDispositionTest` can pin the policy.
 */
package com.dreamdisplayx.media.player.managers

/**
 * What [LibVlcSessionManager.beginSeek] should do with a player that reports `ENDED`.
 */
internal enum class EndedDisposition {
    /**
     * The position is at (or past) the end of the media: a genuine end of stream. The caller may
     * take the expensive restart path — for Android that means retiring the native players.
     */
    REAL_END,

    /**
     * `ENDED` arrived at a fragment boundary. The demuxer simply ran out of buffered fragments, not
     * out of media, so the caller must resume the SAME players instead of retiring them.
     */
    FRAGMENT_BOUNDARY,

    /**
     * There is no usable duration to compare against, so "near the end" cannot be decided. Resuming
     * in place is still the safe choice: a wrong in-place resume costs at most a position reset,
     * whereas a wrong retirement permanently burns two AudioTrack slots.
     */
    UNKNOWN_DURATION,
}

/**
 * Maps an `ENDED` state plus the playhead and the media length onto an [EndedDisposition].
 *
 * Kept as a pure object so the arithmetic — the part that silently destroyed the AudioTrack budget
 * — is testable without a native player.
 */
internal object AndroidEndedPolicy {
    /**
     * Fraction of the media length that counts as "at the end".
     *
     * A real end of stream lands within a frame or two of the duration, and libvlc reports `POSITION`
     * with sub-second granularity, so 1 % of a 47-second clip is ~470 ms — comfortably larger than
     * the reporting error and far smaller than a fragment boundary that arrives at 30 % of the
     * media. The absolute floor covers very short clips, where 1 % would be only a few milliseconds.
     */
    const val END_FRACTION: Double = 0.01

    /** Floor on the "near the end" slack, in milliseconds, for short media. */
    const val MIN_END_SLACK_MS: Long = 1_500L

    /**
     * Slack subtracted from the media length when deciding, in milliseconds.
     *
     * The larger of [END_FRACTION] of the duration and [MIN_END_SLACK_MS], so both a 40-second clip
     * and a 3-hour stream get a comparable window.
     */
    fun endSlackMs(durationMs: Long): Long =
        (durationMs * END_FRACTION).toLong().coerceAtLeast(MIN_END_SLACK_MS)

    /**
     * Classifies a reported `ENDED`.
     *
     * @param positionMs `libvlc_media_player_get_time` at the moment the state read `ENDED`, or a
     *   negative value when the read failed.
     * @param durationMs `libvlc_media_player_get_length`, or a non-positive value for a live stream
     *   or an unknown length.
     */
    fun classify(positionMs: Long, durationMs: Long): EndedDisposition {
        // A live stream has no length, and a failed length read is indistinguishable from one: both
        // leave nothing to compare against. Resuming in place cannot hurt, retiring can.
        if (durationMs <= 0L) return EndedDisposition.UNKNOWN_DURATION
        // A failed position read (`get_time` answers -1 while the demuxer is mid-flush) must not be
        // read as "position 0 = far from the end": the caller resumes in place either way, and this
        // keeps the enum honest about why.
        if (positionMs < 0L) return EndedDisposition.UNKNOWN_DURATION
        val endStart = durationMs - endSlackMs(durationMs)
        return if (positionMs >= endStart) EndedDisposition.REAL_END else EndedDisposition.FRAGMENT_BOUNDARY
    }

    /**
     * True when the caller must reuse the live native players instead of retiring them.
     *
     * Only [EndedDisposition.REAL_END] licenses a retirement; every other answer resumes in place.
     */
    fun shouldResumeInPlace(disposition: EndedDisposition): Boolean =
        disposition != EndedDisposition.REAL_END
}
