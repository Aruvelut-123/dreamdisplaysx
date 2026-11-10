package com.dreamdisplayx.media.player.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [FfmpegVideoOwnership] 的编排规则。
 *
 * 这些用例存在的意义：接线一旦把"谁产视频/谁出声/谁给时钟"合并回一个布尔值，就会写出
 * "视频在 FFmpeg、却拿 FFmpeg 挂钟去对齐 libvlc 音频"这类错位调用，而在真机上它只表现为
 * "画面微微抖动"——最难归因的那类 bug。所以规则钉在纯 JVM 层。
 *
 * 阶段 2（FFmpeg 音频）之后，`plan()` 多了一个 `audioSinkAvailable` 入参，两个平台的
 * 编排因此出现实质差异，下面各有对应用例。
 */
internal class FfmpegVideoOwnershipTest {

    @Test
    fun `android with hw accel splits video to ffmpeg and keeps audio on libvlc`() {
        // 阶段 3 之前 Android 没有 FfmpegPcmSink 实现，所以这里必须传 false。
        val plan = FfmpegVideoOwnership.plan(
            ffmpegRequested = true,
            isAndroid = true,
            hwAccelEnabled = true,
            audioSinkAvailable = false,
        )

        assertTrue(plan.videoIsFfmpeg, "video must come from the FFmpeg pipeline")
        assertEquals(FfmpegVideoOwnership.Backend.LIBVLC, plan.audioBackend, "audio stays on libvlc")
        assertEquals(FfmpegVideoOwnership.Backend.LIBVLC, plan.clockBackend, "clock follows audio")
        assertTrue(plan.isSplit, "video and audio are owned by different backends")
        assertFalse(plan.audioIsFfmpeg, "Android has no PCM device implementation yet")
    }

    @Test
    fun `clock is owned by the audio backend when the plan is split`() {
        val split = FfmpegVideoOwnership.plan(
            true, isAndroid = true, hwAccelEnabled = true, audioSinkAvailable = false,
        )

        assertEquals(
            split.audioBackend,
            FfmpegVideoOwnership.clockOwner(split),
            "the audio output is the only continuous clock available",
        )
        // And it must not be the video backend, or FfmpegSession's wall clock would pace the display.
        assertNotEquals(FfmpegVideoOwnership.clockOwner(split), split.videoBackend)
    }

    @Test
    fun `desktop with a pcm sink hands both video and audio to ffmpeg`() {
        // 桌面有 SourceDataLine 实现，所以两边都归 FFmpeg，且不拆分——因此不需要
        // CompositeSession，单个 FfmpegSession 全包。
        val plan = FfmpegVideoOwnership.plan(
            ffmpegRequested = true,
            isAndroid = false,
            hwAccelEnabled = true,
            audioSinkAvailable = true,
        )

        assertTrue(plan.videoIsFfmpeg, "the desktop chain is software-only, which still works")
        assertTrue(plan.audioIsFfmpeg, "the desktop PCM sink is implemented")
        assertFalse(plan.isSplit, "one session owns everything, so no CompositeSession is needed")
        assertEquals(
            plan.audioBackend,
            FfmpegVideoOwnership.clockOwner(plan),
            "the clock must be the audio device's own played-byte position",
        )
    }

    @Test
    fun `desktop never claims ffmpeg video without the config opt-in`() {
        val plan = FfmpegVideoOwnership.plan(
            ffmpegRequested = false,
            isAndroid = false,
            hwAccelEnabled = true,
            audioSinkAvailable = true,
        )

        assertFalse(plan.videoIsFfmpeg, "the pipeline is opt-in on every platform")
        assertFalse(plan.audioIsFfmpeg, "a libvlc-only plan cannot be handed FFmpeg audio")
        assertFalse(plan.isSplit)
    }

    @Test
    fun `desktop does not require hardware acceleration`() {
        // 桌面链只有软件档；要求硬解等于永久关闭桌面 FFmpeg。
        val plan = FfmpegVideoOwnership.plan(
            ffmpegRequested = true,
            isAndroid = false,
            hwAccelEnabled = false,
            audioSinkAvailable = true,
        )

        assertTrue(plan.videoIsFfmpeg, "software decode is the only desktop rung, and it is allowed")
    }

    @Test
    fun `software-only android stays on libvlc`() {
        val plan = FfmpegVideoOwnership.plan(
            ffmpegRequested = true,
            isAndroid = true,
            hwAccelEnabled = false,
            audioSinkAvailable = false,
        )

        assertFalse(
            plan.videoIsFfmpeg,
            "software decode through a second pipeline is strictly worse than libvlc on Android",
        )
        assertEquals(FfmpegVideoOwnership.Backend.LIBVLC, plan.videoBackend)
    }

    @Test
    fun `ffmpeg stays off unless the config asks for it`() {
        val plan = FfmpegVideoOwnership.plan(
            ffmpegRequested = false,
            isAndroid = true,
            hwAccelEnabled = true,
            audioSinkAvailable = false,
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

    @Test
    fun `the default audio sink availability is false`() {
        // 默认值必须保守：漏传这个入参时，宁可让音频留在 libvlc（有声音），
        // 也不能默认成「FFmpeg 有音频」（静音）。这是本项目最容易犯的错。
        val plan = FfmpegVideoOwnership.plan(true, isAndroid = true, hwAccelEnabled = true)

        assertFalse(plan.audioIsFfmpeg, "a missing argument must never produce a silent picture")
        assertTrue(plan.isSplit)
    }

    @Test
    fun `ffmpeg audio is never chosen without ffmpeg video`() {
        // 孤立地「只有音频在 FFmpeg」既没有实现也没有意义：解封装器由视频侧创建。
        val plan = FfmpegVideoOwnership.plan(
            ffmpegRequested = false,
            isAndroid = false,
            hwAccelEnabled = true,
            audioSinkAvailable = true,
        )

        assertFalse(plan.audioIsFfmpeg)
        assertEquals(FfmpegVideoOwnership.Backend.LIBVLC, plan.videoBackend)
        assertEquals(FfmpegVideoOwnership.Backend.LIBVLC, plan.audioBackend)
    }
}