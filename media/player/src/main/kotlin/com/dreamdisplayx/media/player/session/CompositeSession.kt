package com.dreamdisplayx.media.player.session

import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.player.GpuTextureRef
import com.dreamdisplayx.media.player.managers.WarmTrack
import com.dreamdisplayx.media.player.stream.ActiveStreams
import com.dreamdisplayx.util.log.DreamLog
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把视频与音频拆给两个后端承担的 [PlaybackSession]。
 *
 * ## 为什么需要组合而不是继承
 *
 * FFmpeg 接管视频后，"这个显示器的会话"仍然是**一个**对象：`MediaPlayer` 有 50 多处调用，
 * 它需要的是一个能回答全部问题的会话，而不是两个各自只答一半的会话。把拆分逻辑关在这里，
 * `MediaPlayer` 一行都不用改，也就不存在"漏改某个调用点于是问错了后端"的机会。
 *
 * ## 分派规则
 *
 * 每个成员只问一个后端，判据来自 [FfmpegVideoOwnership] 的 `Plan`，而不是在这里做 `is`
 * 判断：
 *
 *  - 视频（帧交付、FPS、`textureFilled`/`updateFrame`/`clearFrame`、画质切换）→ 视频后端
 *  - 音频（音量、暖轨道、PCM 抓取、`restartAudio`）→ 音频后端
 *  - 播放位置与状态（`currentPacingNanos`、`isPlaying`、park/resume）→ **音频后端**
 *
 * 最后一条是这套编排里最容易写错的地方：`isPlaying` 与时钟必须来自同一个后端，否则
 * "视频在不在播"和"播到第几秒"会由两条互不知情的进度条回答，进度条就会一步步漂走。
 *
 * ## 生命周期
 *
 * [start] / [stop] 等是**两边都要通知**的：两条后端各自持有 native 资源，只启一个就会得到
 * "有声无画"或"有画无声"。
 */
internal class CompositeSession(
    /** 产视频帧的后端。 */
    private val video: PlaybackSession,
    /** 出声音、并提供主时钟的后端。 */
    private val audio: PlaybackSession,
) : PlaybackSession {

    private val logger = DreamLog.of("CompositeSession")

    /** 组合后端对外的名字，形如 `ffmpeg+libvlc`，便于日志一眼看出编排。 */
    override val backendName: String = "${video.backendName}+${audio.backendName}"

    private val released = AtomicBoolean(false)

    // ── 帧交付：只问视频后端 ────────────────────────────────────────────────

    override fun textureFilled(): Boolean = video.textureFilled()

    override fun updateFrame(texture: GpuTextureRef, actualW: Int, actualH: Int): Boolean =
        video.updateFrame(texture, actualW, actualH)

    override fun updateFramePlanar(
        y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, actualW: Int, actualH: Int,
    ): Boolean = video.updateFramePlanar(y, u, v, actualW, actualH)

    override fun clearFrame() = video.clearFrame()

    override fun hasIncoming(): Boolean = video.hasIncoming()

    override fun updateIncomingFrame(texture: GpuTextureRef, actualW: Int, actualH: Int): Boolean =
        video.updateIncomingFrame(texture, actualW, actualH)

    override fun updateIncomingFramePlanar(
        y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, actualW: Int, actualH: Int,
    ): Boolean = video.updateIncomingFramePlanar(y, u, v, actualW, actualH)

    override fun promoteIncoming(): Boolean = video.promoteIncoming()

    override val lastFrameNanos get() = video.lastFrameNanos

    override val currentVideoFps: Double get() = video.currentVideoFps

    override fun frameIntervalInfo(): String? = video.frameIntervalInfo()

    // 三个原始帧 sink 只对视频后端有意义：音频后端不产帧。
    override var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = video.popoutFrameSink
        set(value) {
            video.popoutFrameSink = value
        }

    override var previewFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = video.previewFrameSink
        set(value) {
            video.previewFrameSink = value
        }

    override var ambientLightSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = video.ambientLightSink
        set(value) {
            video.ambientLightSink = value
        }

    // ── 起播 / 停止：两边都要通知 ───────────────────────────────────────────

    /**
     * 起播。
     *
     * 视频先起，音频随后。返回值只看**视频**是否成功：视频起不来画面就是黑的，音频通不通
     * 最多是没声音，不该让整个显示进入错误态（用户至少还能看到画面上的报错提示）。
     */
    override fun start(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean {
        if (released.get()) return false
        if (!video.start(streamSet, offsetNanos, lastQuality)) return false
        // 音频失败不回滚视频：视频侧已经交付首帧，回滚会让界面闪回加载态。
        val audioOk = runCatching { audio.start(streamSet, offsetNanos, lastQuality) }.getOrDefault(false)
        if (!audioOk) logger.warn("{} audio backend failed to start; continuing video-only", backendName)
        return true
    }

    override fun beginSeek(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean {
        if (released.get()) return false
        val videoOk = video.beginSeek(streamSet, offsetNanos, lastQuality)
        runCatching { audio.beginSeek(streamSet, offsetNanos, lastQuality) }
        return videoOk
    }

    override fun beginQualitySwitch(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int) {
        // 画质只影响视频轨道；音频轨道不变，所以只通知视频后端。
        video.beginQualitySwitch(streamSet, offsetNanos, lastQuality)
    }

    override fun stop() {
        runCatching { video.stop() }
        runCatching { audio.stop() }
    }

    override fun stopNow() {
        runCatching { video.stopNow() }
        runCatching { audio.stopNow() }
    }

    override fun pauseNow() {
        runCatching { video.pauseNow() }
        runCatching { audio.pauseNow() }
    }

    // ── 音频：只问音频后端 ──────────────────────────────────────────────────

    override fun setVolume(volume: Double) = audio.setVolume(volume)

    override fun verifyVolume() = audio.verifyVolume()

    override fun setWarmAudioTracks(tracks: List<WarmTrack>) = audio.setWarmAudioTracks(tracks)

    override fun captureAudioPcm(maxNanos: Long): ByteArray? = audio.captureAudioPcm(maxNanos)

    /**
     * 只换音频。
     *
     * 视频后端不参与：它不该为了换一条音轨重新解一遍视频（那会黑屏一次）。
     */
    override fun restartAudio(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean =
        audio.restartAudio(streamSet, offsetNanos, lastQuality)

    // ── 停靠 / 挂起 / 恢复 ──────────────────────────────────────────────────

    override fun canPark(): Boolean = audio.canPark() && video.canPark()

    override fun suspend(allowExternalProcess: Boolean, retainBuffered: Boolean): Boolean {
        val videoOk = runCatching { video.suspend(allowExternalProcess, retainBuffered) }.getOrDefault(false)
        val audioOk = runCatching { audio.suspend(allowExternalProcess, retainBuffered) }.getOrDefault(false)
        // 只要有一边成功就算停靠成功：位置记在音频侧，视频侧只是释放了 native。
        return videoOk || audioOk
    }

    override fun resume() {
        runCatching { video.resume() }
        runCatching { audio.resume() }
    }

    override fun isParked(): Boolean = audio.isParked()

    /**
     * 停靠位置。
     *
     * 取音频侧：它是主时钟的持有者。视频侧在拆分布局里只有挂钟，位置会与音频漂开，
     * 拿它恢复会让每次挂起都丢几秒。
     */
    override fun parkedPositionNanos(): Long? = audio.parkedPositionNanos()

    override fun repositionParked(nanos: Long) {
        audio.repositionParked(nanos)
        // 视频侧也记一下，否则恢复时它会从旧位置重新起播，与音频错开。
        runCatching { video.repositionParked(nanos) }
    }

    // ── 状态与时钟：只问音频后端 ────────────────────────────────────────────

    override val isPlaying: Boolean get() = audio.isPlaying

    override fun currentPacingNanos(): Long = audio.currentPacingNanos()

    override fun activeBridgeEdgeNanos(): Long? = audio.activeBridgeEdgeNanos()

    // ── 回放 ────────────────────────────────────────────────────────────────

    override fun captureVideoCacheSnapshot(): ByteArray? = video.captureVideoCacheSnapshot()

    override fun startReplayVideoOnly(
        snapshot: ByteArray?, resume: Long, positionNanos: Long, audioPcm: ByteArray?,
    ): Boolean = video.startReplayVideoOnly(snapshot, resume, positionNanos, audioPcm)

    override fun attachLiveAfterReplay(
        streamSet: ActiveStreams, liveOffsetNanos: Long, lastQuality: Int,
    ): Boolean {
        val videoOk = video.attachLiveAfterReplay(streamSet, liveOffsetNanos, lastQuality)
        runCatching { audio.attachLiveAfterReplay(streamSet, liveOffsetNanos, lastQuality) }
        return videoOk
    }

    // ── 拆除 ────────────────────────────────────────────────────────────────

    override fun cleanup() {
        if (!released.compareAndSet(false, true)) return
        runCatching { video.cleanup() }
        runCatching { audio.cleanup() }
    }
}