package com.dreamdisplayx.media.player.ffmpeg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the PCM duration arithmetic used by [FfmpegAudioDecoder.DecodedPcm].
 *
 * The native decode path cannot be exercised without a device and a real
 * stream, but the byte-to-duration conversion is where a wrong constant shows up
 * as audio that is audibly the wrong length — or as a master clock that runs at
 * half speed and makes the picture stutter. Those two failures are cheap to
 * prevent here and expensive to diagnose in the field.
 */
class FfmpegAudioDecoderPcmTest {

    private val rate = FfmpegAudioDecoder.TARGET_RATE
    private val channels = FfmpegAudioDecoder.TARGET_CHANNELS

    @Test
    fun `one second of stereo s16 at 44100 hz is 176400 bytes`() {
        // 44100 frames * 2 channels * 2 bytes.
        assertEquals(176_400, oneSecondBytes())

        assertEquals(
            1_000_000_000L,
            FfmpegAudioDecoder.DecodedPcm.nanosOf(oneSecondBytes(), rate, channels),
        )
    }

    @Test
    fun `a chunk is scaled linearly from its byte count`() {
        val halfSecond = oneSecondBytes() / 2
        assertEquals(
            500_000_000L,
            FfmpegAudioDecoder.DecodedPcm.nanosOf(halfSecond, rate, channels),
        )

        val twentyMs = oneSecondBytes() / 50
        assertEquals(
            20_000_000L,
            FfmpegAudioDecoder.DecodedPcm.nanosOf(twentyMs, rate, channels),
        )
    }

    @Test
    fun `a partial frame is truncated, not rounded up`() {
        // 3 stray bytes are not a frame; counting them would make the clock drift
        // forward against the audio every chunk.
        assertEquals(0L, FfmpegAudioDecoder.DecodedPcm.nanosOf(3, rate, channels))
        // One full frame is exactly one sample period.
        assertEquals(
            1_000_000_000L / rate,
            FfmpegAudioDecoder.DecodedPcm.nanosOf(4, rate, channels),
        )
    }

    @Test
    fun `a non-positive sample rate yields zero instead of dividing by zero`() {
        assertEquals(0L, FfmpegAudioDecoder.DecodedPcm.nanosOf(176_400, 0, channels))
        assertEquals(0L, FfmpegAudioDecoder.DecodedPcm.nanosOf(176_400, -44100, channels))
    }

    @Test
    fun `the decoder reports the shape every consumer expects`() {
        // The whole point of the fixed output format: the Java Sound line, the
        // AudioTrack budget and the DSP stages are all written for 44.1k stereo.
        assertEquals(44100, FfmpegAudioDecoder.TARGET_RATE)
        assertEquals(2, FfmpegAudioDecoder.TARGET_CHANNELS)
    }

    private fun oneSecondBytes(): Int =
        FfmpegAudioDecoder.TARGET_RATE * FfmpegAudioDecoder.TARGET_CHANNELS * 2
}