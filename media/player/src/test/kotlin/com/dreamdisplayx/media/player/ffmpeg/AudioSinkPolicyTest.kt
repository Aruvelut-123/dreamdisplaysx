package com.dreamdisplayx.media.player.ffmpeg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins [AudioSinkPolicy], the pacing arithmetic every FFmpeg audio write goes through.
 *
 * The device calls (Java Sound / AudioTrack) cannot be unit-tested, but the numbers
 * that decide *when* and *how much* to write can — and they are where the audible
 * failures live. A wrong [AudioSinkPolicy.MAX_LEAD_NANOS] is "the sound lags the
 * picture by a second after a seek"; a wrong frame granularity is "the channels are
 * swapped for the rest of the session"; a wrong clock anchor is "the progress bar
 * drifts and the picture stutters".
 */
class AudioSinkPolicyTest {

    private companion object {
        /** Bytes in one second of 44.1 kHz stereo S16. */
        const val ONE_SECOND = 44_100L * 4L
    }

    @Test
    fun `one second of stereo s16 is one second of content`() {
        assertEquals(1_000_000_000L, AudioSinkPolicy.bytesToNanos(ONE_SECOND))
        assertEquals(ONE_SECOND, AudioSinkPolicy.nanosToBytes(1_000_000_000L))
    }

    @Test
    fun `frames and bytes agree`() {
        // 44100 frames is one second, and framesToNanos must say so.
        assertEquals(1_000_000_000L, AudioSinkPolicy.framesToNanos(44_100L))
        assertEquals(1_000_000_000L, AudioSinkPolicy.framesToNanos(44_100L))
        // 735 frames is one video frame at 60 fps (16.6 ms), a useful landmark.
        assertEquals(16_666_666L, AudioSinkPolicy.framesToNanos(735L))
    }

    @Test
    fun `the byte conversion keeps sub-frame precision`() {
        // This is the regression that a naive `bytes / BYTES_PER_FRAME` first
        // implementation had: it dropped the remainder before scaling, so a
        // 400 ms queue read as 399 ms and a long session's clock drifted.
        val justUnderOneSecond = ONE_SECOND - AudioSinkPolicy.BYTES_PER_FRAME
        val nanos = AudioSinkPolicy.bytesToNanos(justUnderOneSecond)
        assertTrue(nanos > 999_000_000L, "the remainder must carry through, not truncate to 999 ms")
        assertTrue(nanos < 1_000_000_000L)
    }

    @Test
    fun `a non-positive byte count is zero, not a division by zero`() {
        assertEquals(0L, AudioSinkPolicy.bytesToNanos(0L))
        assertEquals(0L, AudioSinkPolicy.bytesToNanos(-4L))
        assertEquals(0L, AudioSinkPolicy.nanosToBytes(0L))
        assertEquals(0L, AudioSinkPolicy.framesToNanos(0L))
    }

    @Test
    fun `no wait is needed while the queue is under the ceiling`() {
        // Nothing written yet.
        assertEquals(0L, AudioSinkPolicy.waitNanos(0L, 0L, 0L))
        // Half the ceiling queued: room remains.
        val halfCeiling = AudioSinkPolicy.nanosToBytes(AudioSinkPolicy.MAX_LEAD_NANOS / 2)
        assertEquals(0L, AudioSinkPolicy.waitNanos(halfCeiling, 0L, 0L))
    }

    @Test
    fun `wait is the excess over the ceiling, capped by the elapsed time`() {
        val oneSecondQueued = ONE_SECOND
        val excess = AudioSinkPolicy.bytesToNanos(oneSecondQueued) - AudioSinkPolicy.MAX_LEAD_NANOS
        assertTrue(excess > 0L)

        // Elapsed time long enough to cover the whole excess: the exact excess is returned.
        assertEquals(excess, AudioSinkPolicy.waitNanos(oneSecondQueued, 0L, excess))

        // Elapsed time shorter: the wait is capped, so a device that never reports
        // progress cannot park the writer forever.
        assertEquals(10_000_000L, AudioSinkPolicy.waitNanos(oneSecondQueued, 0L, 10_000_000L))
    }

    @Test
    fun `a device that played more than was written needs no wait`() {
        // Played beyond written is a counter reset; waitNanos must not underflow.
        assertEquals(0L, AudioSinkPolicy.waitNanos(100L, 200L, 5_000_000L))
    }

    @Test
    fun `writes are trimmed to the remaining room`() {
        // Empty queue: the ceiling worth of bytes is allowed, but never more than pending.
        val room = AudioSinkPolicy.nanosToBytes(AudioSinkPolicy.MAX_LEAD_NANOS)
        assertEquals(room.toInt(), AudioSinkPolicy.bytesAllowedNow(0L, 0L, room.toInt()))
        assertEquals(
            1000,
            AudioSinkPolicy.bytesAllowedNow(0L, 0L, 1000),
            "a small block must never be inflated",
        )
    }

    @Test
    fun `a full queue allows nothing`() {
        val full = AudioSinkPolicy.nanosToBytes(AudioSinkPolicy.MAX_LEAD_NANOS)
        assertEquals(0, AudioSinkPolicy.bytesAllowedNow(full, 0L, 4096))
        assertEquals(0, AudioSinkPolicy.bytesAllowedNow(full + 4096, 0L, 4096))
    }

    @Test
    fun `allowed writes never split a frame`() {
        // 3 bytes is not a frame; allowing it would swap the channels permanently.
        assertEquals(0, AudioSinkPolicy.bytesAllowedNow(0L, 0L, 3))
        // 5 bytes -> one frame (4 bytes), one byte withheld.
        assertEquals(4, AudioSinkPolicy.bytesAllowedNow(0L, 0L, 5))
        // 7 bytes -> still only one frame.
        assertEquals(4, AudioSinkPolicy.bytesAllowedNow(0L, 0L, 7))
    }

    @Test
    fun `a negative or zero pending count allows nothing`() {
        assertEquals(0, AudioSinkPolicy.bytesAllowedNow(0L, 0L, 0))
        assertEquals(0, AudioSinkPolicy.bytesAllowedNow(0L, 0L, -100))
    }

    @Test
    fun `content position is anchored on the played counter`() {
        // Nothing played: the position is the session's start, which is what makes a
        // freshly opened display report its seek target instead of zero.
        assertEquals(0L, AudioSinkPolicy.contentNanos(0L, 0L))
        assertEquals(5_000_000_000L, AudioSinkPolicy.contentNanos(5_000_000_000L, 0L))

        // One second played from a 10 s start.
        assertEquals(
            11_000_000_000L,
            AudioSinkPolicy.contentNanos(10_000_000_000L, ONE_SECOND),
        )
    }

    @Test
    fun `a played counter beyond what was written is implausible`() {
        assertTrue(AudioSinkPolicy.isPlayedPlausible(0L, 0L), "nothing written, nothing played")
        assertTrue(AudioSinkPolicy.isPlayedPlausible(100L, 100L), "exactly drained")
        assertTrue(AudioSinkPolicy.isPlayedPlausible(50L, 100L), "partially drained")
        assertFalse(AudioSinkPolicy.isPlayedPlausible(101L, 100L), "played more than written")
        assertFalse(AudioSinkPolicy.isPlayedPlausible(-1L, 100L), "negative counter")
    }

    @Test
    fun `the lead bounds are sane relative to a hardware period`() {
        // A ceiling under one hardware period (~10-25 ms) would underrun on every
        // chunk; a floor far above it would make a seek leave audible residue.
        assertTrue(AudioSinkPolicy.MIN_LEAD_NANOS >= 10_000_000L, "floor must exceed one period")
        assertTrue(AudioSinkPolicy.MAX_LEAD_NANOS > AudioSinkPolicy.MIN_LEAD_NANOS)
        assertTrue(AudioSinkPolicy.MAX_LEAD_NANOS <= 500_000_000L, "ceiling must stay seek-friendly")
        // The ceiling must be under the master clock's stall threshold (750 ms), or a
        // healthy queue could look like a stall.
        assertTrue(AudioSinkPolicy.MAX_LEAD_NANOS < 750_000_000L)
    }

    @Test
    fun `the fixed pcm shape matches the decoder and the sinks`() {
        assertEquals(FfmpegAudioDecoder.TARGET_RATE, AudioSinkPolicy.SAMPLE_RATE)
        assertEquals(44100, AudioSinkPolicy.SAMPLE_RATE)
        assertEquals(4, AudioSinkPolicy.BYTES_PER_FRAME)
    }
}