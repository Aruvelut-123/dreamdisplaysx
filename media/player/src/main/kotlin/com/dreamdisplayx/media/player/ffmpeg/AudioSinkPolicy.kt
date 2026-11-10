package com.dreamdisplayx.media.player.ffmpeg

/**
 * Pacing rules for feeding decoded PCM into an output device.
 *
 * ## Why this is a pure object
 *
 * A playback line blocks when its internal ring is full, so "how much to write
 * and when to wait" looks trivial — but every wrong answer is audible and none
 * of them throws:
 *
 * - writing everything as fast as the decoder produces it makes the output
 *   device's buffer balloon, and the sound trails the picture by however much
 *   was queued (the classic "audio lags after a seek");
 * - writing only when the line reports perfect room starves on a line whose
 *   `available()` granularity is one hardware buffer, which stutters;
 * - a decoder that produces faster than real time (a file source always does)
 *   needs a bounded lead, or the ring never drains and pause/seek feels late.
 *
 * So the numbers live here, take plain longs, and are unit-tested. The actual
 * `SourceDataLine` / `AudioTrack` call stays in the platform implementation,
 * which cannot be tested without a device.
 */
internal object AudioSinkPolicy {

    /** Stereo 16-bit: one frame is two samples of two bytes. */
    const val BYTES_PER_FRAME = 4

    /** Output rate every consumer is written for; mirrors [FfmpegAudioDecoder.TARGET_RATE]. */
    const val SAMPLE_RATE = FfmpegAudioDecoder.TARGET_RATE

    /**
     * How much audio the line may hold ahead of real time before the writer
     * waits. ~300 ms: comfortably more than any decoder burst, small enough that
     * a seek does not leave a third of a second of stale sound queued.
     */
    const val MAX_LEAD_NANOS = 300_000_000L

    /**
     * Floor for the lead. Below roughly one hardware period the writer is
     * fighting the device's own granularity and underruns on every chunk.
     */
    const val MIN_LEAD_NANOS = 40_000_000L

    /** Frame count of one stereo S16 chunk, used to size writes. */
    fun framesToNanos(frames: Long): Long =
        if (frames <= 0L) 0L else frames * 1_000_000_000L / SAMPLE_RATE

    /** Byte count of interleaved stereo S16 to a duration. */
    fun bytesToNanos(bytes: Long): Long =
        if (bytes <= 0L) 0L else bytes * 1_000_000_000L / (SAMPLE_RATE.toLong() * BYTES_PER_FRAME)

    /** Interleaved stereo S16 byte count for [nanos] of audio. */
    fun nanosToBytes(nanos: Long): Long =
        if (nanos <= 0L) 0L else nanos * SAMPLE_RATE * BYTES_PER_FRAME / 1_000_000_000L

    /**
     * How long the writer still has to wait before the queued audio reaches
     * [MAX_LEAD_NANOS].
     *
     * @param writtenBytes total bytes handed to the device so far in this session
     * @param playedBytes bytes the device reports it has actually played
     * @param elapsedNanos how long the session has been running; used to keep the
     *   wait bounded when the device's counter has not moved yet
     * @return 0 when the writer may proceed immediately
     */
    fun waitNanos(writtenBytes: Long, playedBytes: Long, elapsedNanos: Long): Long {
        val queuedBytes = writtenBytes - playedBytes
        if (queuedBytes <= 0L) return 0L
        val queuedNanos = bytesToNanos(queuedBytes)
        if (queuedNanos <= MAX_LEAD_NANOS) return 0L
        val excess = queuedNanos - MAX_LEAD_NANOS
        // A device that has opened but not started reporting position yet would
        // otherwise be asked to wait for the whole queued amount, which can be
        // most of a second. Capping the wait at the elapsed time keeps a stalled
        // counter from freezing the writer forever.
        return minOf(excess, elapsedNanos.coerceAtLeast(0L))
    }

    /**
     * Trims a write so the queued audio never exceeds [MAX_LEAD_NANOS].
     *
     * A decoder burst is routinely several hundred milliseconds; writing it in
     * one call puts all of it in the device at once and the excess becomes
     * latency that only a seek can clear.
     *
     * @return the number of bytes that may be written now, possibly 0
     */
    fun bytesAllowedNow(writtenBytes: Long, playedBytes: Long, pendingBytes: Int): Int {
        if (pendingBytes <= 0) return 0
        val queuedNanos = bytesToNanos((writtenBytes - playedBytes).coerceAtLeast(0L))
        if (queuedNanos >= MAX_LEAD_NANOS) return 0
        val roomNanos = MAX_LEAD_NANOS - queuedNanos
        val roomBytes = nanosToBytes(roomNanos)
        // Never return more than is pending, and never a partial frame: a
        // half-frame write desynchronises the channels permanently.
        val allowed = minOf(roomBytes, pendingBytes.toLong())
        return (allowed / BYTES_PER_FRAME * BYTES_PER_FRAME).toInt()
    }

    /**
     * Position of the audio clock, in content nanos.
     *
     * Anchored on the device's own played-byte counter rather than on wall time:
     * the device can drift against the wall clock, and the picture is paced by
     * this number, so anchoring on wall time would make the drift visible as
     * judder.
     *
     * @param contentStartNanos where this session began on the content timeline
     * @param playedBytes bytes the device has actually played
     * @return the content position, or [contentStartNanos] when nothing has played
     */
    fun contentNanos(contentStartNanos: Long, playedBytes: Long): Long =
        contentStartNanos + bytesToNanos(playedBytes)

    /**
     * Whether a reported playback position is plausible against what was written.
     *
     * A device that reports having played more than was ever handed to it has
     * wrapped or reset its counter; trusting that number would make the clock
     * jump forward and the picture skip. Called every poll, so it must be cheap.
     */
    fun isPlayedPlausible(playedBytes: Long, writtenBytes: Long): Boolean =
        playedBytes >= 0L && playedBytes <= writtenBytes
}