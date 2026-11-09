package com.dreamdisplayx.media.player.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [FfmpegVideoOwnership] 的编排规则。
 *
 * 这些用例存在的意义：接线一旦把"谁产视频/谁出声/谁给时钟"合并回一个布尔值，就会写出
 * "视频在 FFmpeg、却拿 FFmpeg 挂钟去对齐 libvlc 音频"这类错位调用，而在真机上它只表现为
 * "画面微微抖动"——最难归因的那类 bug。所以规则钉在纯 JVM 层。
 */
internal class FfmpegVideoOwnershipTest {

    @Test
    fun `android with hw accel and ffmpeg requested splits video to ffmpeg`() {
        val plan = FfmpegVideoOwnership.plan(
            ffmpegRequested = true,
            isAndroid = true,
            hwAccelEnabled = true,
        )

        assertTrue(plan.videoIsFfmpeg, "video must come from the FFmpeg pipeline")
        assertEquals(FfmpegVideoOwnership.Backend.LIBVLC, plan.audioBackend, "audio stays on libvlc")
        assertEquals(FfmpegVideoOwnership.Backend.LIBVLC, plan.clockBackend, "clock follows audio")
        assertTrue(plan.isSplit, "video and audio are owned by different backends")
        assertFalse(plan.audioIsFfmpeg, "the FFmpeg session has no audio implementation")
    }

    @Test
    fun `clock is owned by the audio backend when the plan is split`() {
        val split = FfmpegVideoOwnership.plan(true, isAndroid = true, hwAccelEnabled = true)

        assertEquals(
            split.audioBackend,
            FfmpegVideoOwnership.clockOwner(split),
            "the audio output is the only continuous clock available",
        )
        // And it must not be the video backend, or FfmpegSession's wall clock would pace the display.
        assertTrue(FfmpegVideoOwnership.clockOwner(split) != split.videoBackend)
    }

    @Test
    fun `desktop never uses the ffmpeg video pipeline`() {
        val plan = FfmpegVideoOwnership.plan(
            ffmpegRequested = true,
            isAndroid = false,
            hwAccelEnabled = true,
        )

        assertFalse(plan.videoIsFfmpeg, "mediacodec backends only exist on Android")
        assertFalse(plan.isSplit, "a single backend owns everything")
        assertEquals(FfmpegVideoOwnership.Backend.LIBVLC, plan.videoBackend)
    }

    @Test
    fun `software-only android stays on libvlc`() {
        val plan = FfmpegVideoOwnership.plan(
            ffmpegRequested = true,
            isAndroid = true,
            hwAccelEnabled = false,
        )

        assertFalse(
            plan.videoIsFfmpeg,
            "software decode through a second pipeline is strictly worse than libvlc",
        )
        assertEquals(FfmpegVideoOwnership.Backend.LIBVLC, plan.videoBackend)
    }

    @Test
    fun `ffmpeg stays off unless the config asks for it`() {
        val plan = FfmpegVideoOwnership.plan(
            ffmpegRequested = false,
            isAndroid = true,
            hwAccelEnabled = true,
        )

        assertFalse(plan.videoIsFfmpeg, "the pipeline is opt-in")
    }

    @Test
    fun `a libvlc-only plan is never split and never claims ffmpeg audio`() {
        val plan = FfmpegVideoOwnership.plan(false, isAndroid = true, hwAccelEnabled = true)

        assertFalse(plan.isSplit)
        assertFalse(plan.videoIsFfmpeg)
        assertFalse(plan.audioIsFfmpeg)
        assertEquals(plan.videoBackend, plan.audioBackend)
        assertEquals(plan.audioBackend, FfmpegVideoOwnership.clockOwner(plan))
    }
}