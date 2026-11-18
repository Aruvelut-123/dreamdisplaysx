package com.dreamdisplayx.media.player.ffmpeg

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins when the session has to open a second input just for audio.
 *
 * Both wrong answers are silent on a device: opening a separate input for a
 * muxed source writes two audio streams into one device, and *not* opening one
 * for a DASH source leaves the picture playing with permanent silence. Neither
 * throws and neither logs anything a user would report, so the rule is pinned
 * here instead.
 */
class FfmpegAudioSourcePolicyTest {

    @Test
    fun `a container that already carries audio needs no second input`() {
        // The muxed case: the audio URL may or may not equal the video URL, but
        // the container already has an audio stream, so a second input would only
        // duplicate it.
        assertFalse(
            FfmpegAudioSourcePolicy.needsSeparateInput(
                videoUrl = "https://cdn.example.com/movie.mp4",
                audioUrl = "https://cdn.example.com/movie.mp4",
                videoHasAudio = true,
            ),
        )
        assertFalse(
            FfmpegAudioSourcePolicy.needsSeparateInput(
                videoUrl = "https://cdn.example.com/movie.mp4",
                audioUrl = "https://cdn.example.com/other-audio.m4s",
                videoHasAudio = true,
            ),
        )
    }

    @Test
    fun `a split DASH source needs a second input`() {
        // The case that produced "picture plays, no sound at all" in FFmpeg mode:
        // the video container has no audio track and the audio lives at another URL.
        assertTrue(
            FfmpegAudioSourcePolicy.needsSeparateInput(
                videoUrl = "https://cdn.example.com/video.m4s",
                audioUrl = "https://cdn.example.com/audio.m4s",
                videoHasAudio = false,
            ),
        )
    }

    @Test
    fun `a muxed source without an audio track does not reopen the same url`() {
        // Opening the same URL a second time yields the same audio-less container,
        // so the retry is pure cost — and a second decoder on a silent source.
        assertFalse(
            FfmpegAudioSourcePolicy.needsSeparateInput(
                videoUrl = "https://cdn.example.com/silent.mp4",
                audioUrl = "https://cdn.example.com/silent.mp4",
                videoHasAudio = false,
            ),
        )
    }

    @Test
    fun `a blank audio url never opens a second input`() {
        // The resolver not naming an audio rendition is normal (silent videos,
        // some live fragments); it is not a reason to call avformat_open_input("").
        assertFalse(
            FfmpegAudioSourcePolicy.needsSeparateInput(
                videoUrl = "https://cdn.example.com/video.mp4",
                audioUrl = "",
                videoHasAudio = false,
            ),
        )
    }

    @Test
    fun `isSeparateUrl treats the video url itself as not separate`() {
        assertFalse(FfmpegAudioSourcePolicy.isSeparateUrl("https://a/x.m4s", "https://a/x.m4s"))
        assertFalse(FfmpegAudioSourcePolicy.isSeparateUrl("https://a/x.m4s", "   "))
        assertTrue(FfmpegAudioSourcePolicy.isSeparateUrl("https://a/x.m4s", "https://a/y.m4s"))
    }
}