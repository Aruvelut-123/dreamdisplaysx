package com.dreamdisplayx.media.player.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the arithmetic behind the Android `AudioTrack` sink.
 *
 * The sink itself is reflection-only and cannot be built without a device, but the two
 * things that go wrong *silently* — a buffer sized from the wrong number and a playback
 * head counter that is not wrapped correctly — are pure numbers, so they are tested here.
 */
class AudioTrackSpecTest {

    private val bytesPerFrame = 4

    @Test
    fun bufferHonoursTheTargetDurationWhenItExceedsThePlatformMinimum() {
        // 44.1 kHz stereo S16 for 150 ms is 44100 * 4 * 0.15 = 26460 bytes; a platform
        // minimum of 4096 must not shrink it.
        val bytes = AudioTrackSpec.bufferBytes(44100, bytesPerFrame, platformMinBytes = 4096)
        assertTrue(bytes >= 26_000, "expected ~150ms of audio, got $bytes")
        assertEquals(0, bytes % bytesPerFrame, "buffer must be a whole number of frames")
    }

    @Test
    fun bufferIsRaisedToThePlatformMinimumWhenThatIsLarger() {
        // Some devices report a minimum well above the duration target; using less than
        // the platform asks for is how underruns start.
        val bytes = AudioTrackSpec.bufferBytes(44100, bytesPerFrame, platformMinBytes = 65_536)
        assertTrue(bytes >= 65_536, "platform minimum must win, got $bytes")
        assertEquals(0, bytes % bytesPerFrame)
    }

    @Test
    fun failedPlatformQueryStillProducesAUsableBuffer() {
        // getMinBufferSize returns ERROR_BAD_VALUE / ERROR (-1 / -2) on a device with no
        // usable output; the request must still be positive so open() can be attempted.
        val bytes = AudioTrackSpec.bufferBytes(44100, bytesPerFrame, platformMinBytes = -2)
        assertTrue(bytes > 0, "a failed platform query must not yield 0 bytes")
        assertEquals(0, bytes % bytesPerFrame)
    }

    @Test
    fun bufferMsIsClampedIntoTheSafeRange() {
        // A 1 ms request is clamped up to the 40 ms floor (44100*4*0.040 = 7056 bytes),
        // a 5000 ms one down to the 500 ms ceiling (44100*4*0.500 = 88200 bytes).
        val expectedFloor = 44100L * bytesPerFrame * AudioTrackSpec.MIN_BUFFER_MS / 1000L
        val expectedCeiling = 44100L * bytesPerFrame * AudioTrackSpec.MAX_BUFFER_MS / 1000L
        val tooSmall = AudioTrackSpec.bufferBytes(44100, bytesPerFrame, 0, bufferMs = 1)
        val tooLarge = AudioTrackSpec.bufferBytes(44100, bytesPerFrame, 0, bufferMs = 5000)
        assertEquals(expectedFloor.toInt(), tooSmall, "a 1ms request must be clamped up to the floor")
        assertEquals(expectedCeiling.toInt(), tooLarge, "a 5000ms request must be clamped down to the ceiling")
        assertTrue(tooLarge > tooSmall, "the clamp must preserve ordering")
    }

    @Test
    fun invalidShapeYieldsZeroSoTheCallerBailsOutInsteadOfOpeningGarbage() {
        assertEquals(0, AudioTrackSpec.bufferBytes(0, bytesPerFrame, 4096))
        assertEquals(0, AudioTrackSpec.bufferBytes(44100, 0, 4096))
    }

    @Test
    fun unsignedTreatsTheHeadCounterAs32Bit() {
        // getPlaybackHeadPosition() returns an unsigned 32-bit frame count; Java hands
        // it over as a signed int, so 0xFFFFFFFF reads as -1.
        assertEquals(0xFFFFFFFFL, AudioTrackSpec.unsigned(-1))
        assertEquals(0x80000000L, AudioTrackSpec.unsigned(Int.MIN_VALUE))
        assertEquals(1234L, AudioTrackSpec.unsigned(1234))
    }

    @Test
    fun deltaSurvivesTheWrapAround() {
        // The counter goes 0xFFFFFFFF -> 0 (i.e. -1 -> 0 in Java ints). That is one frame
        // forward, not a 4-billion-frame jump or a rewind.
        val delta = AudioTrackSpec.deltaFrames(0, AudioTrackSpec.unsigned(-1))
        assertEquals(1L, delta)
    }

    @Test
    fun deltaIsZeroWhenTheCounterDidNotMove() {
        assertEquals(0L, AudioTrackSpec.deltaFrames(5000, 5000L))
    }

    @Test
    fun deltaCountsPlainAdvance() {
        assertEquals(441L, AudioTrackSpec.deltaFrames(1323, 882L))
    }

    @Test
    fun anImplausiblyLargeDeltaIsRejectedRatherThanJumpingTheClock() {
        // A device reset (or an uninitialised baseline) makes the raw difference enormous.
        // Reporting it would move the master clock by hours; 0 is the safe answer.
        val delta = AudioTrackSpec.deltaFrames(100, AudioTrackSpec.unsigned(-100))
        assertTrue(delta == 0L || delta <= AudioTrackSpec.MAX_PLAUSIBLE_DELTA, "delta=$delta")
    }

    @Test
    fun framesConvertToBytes() {
        assertEquals(4000L, AudioTrackSpec.framesToBytes(1000, 4))
        assertEquals(0L, AudioTrackSpec.framesToBytes(0, 4))
        assertEquals(0L, AudioTrackSpec.framesToBytes(-5, 4))
        assertEquals(0L, AudioTrackSpec.framesToBytes(10, 0))
    }

    @Test
    fun queuedBytesNeverGoesNegative() {
        // A device that reports having played more than was written has wrapped its
        // counter; the lead must read as 0 rather than as a negative buffer.
        assertEquals(0L, AudioTrackSpec.queuedBytes(1000, 1200))
        assertEquals(500L, AudioTrackSpec.queuedBytes(1500, 1000))
    }
}