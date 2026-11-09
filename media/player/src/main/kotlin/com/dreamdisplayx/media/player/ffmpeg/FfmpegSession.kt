package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.player.FrameUploaderFactory
import com.dreamdisplayx.api.media.player.GpuTextureRef
import com.dreamdisplayx.media.player.managers.WarmTrack
import com.dreamdisplayx.media.player.pipeline.FrameSurface
import com.dreamdisplayx.media.player.pipeline.PlaybackClock
import com.dreamdisplayx.media.player.session.PlaybackSession
import com.dreamdisplayx.media.player.stream.ActiveStreams
import com.dreamdisplayx.util.log.DreamLog
import org.bytedeco.ffmpeg.global.avcodec
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * FFmpeg 播放会话：解封装 → 解码 → 发布帧，把 [FfmpegDemuxer] 与
 * [AndroidMediaCodecHwSolver] 串成一条完整的 [PlaybackSession]。
 *
 * ## 它负责什么
 *
 * 只负责**视频**。解封装走 [FfmpegDemuxer]，解码后端由 [FfmpegHwBackendPolicy] 选（硬解失败
 * 经 [FfmpegCodecScheduler.reportFailure] 降级到同编码的软解），成品是打包 BGRA，经
 * [FrameSurface] 交给渲染线程。这正是 libvlc 会话里 `FrameSurface` 那段所做的工作，
 * 所以两条后端共用同一条上传通路，`updateFrame` / `textureFilled` 的行为天然一致。
 *
 * ## 它不负责什么（刻意的）
 *
 * **音频仍归 libvlc**，本类所有音频成员都是空实现。原因写在
 * [PlaybackSession] 的类注释里：libvlc 的 `:no-video` 音频路径承载了 Android 的
 * AudioTrack 预算、暖轨道池与 park/resume 记账，把这段逻辑在 FFmpeg 旁边重写一遍，
 * 等于把模块里最危险的代码复制一份而收益为零。所以选择本会话**只接管视频**，
 * 音频照旧由另一条 libvlc 会话播放。
 *
 * ## 线程模型
 *
 * 与 [PlaybackSession] 的约定一致：渲染线程那一半（[textureFilled] / [updateFrame] /
 * [clearFrame]）只在渲染线程调用；[start] / [stop] 等在控制线程调用；解码循环跑在
 * 本类自有的单线程执行器上，串行执行，不与其他线程争用 native 上下文
 * （[FfmpegDemuxer] 与 [AndroidMediaCodecHwSolver] 都明确要求串行访问）。
 *
 * ## 现在的完成度（不要高估）
 *
 * 这是**第一版可运行的视频通路**：起播、循环解码、把帧交给渲染、seek 重新打开、
 * 停止与释放都有实现。但几处仍是保守的简化，已在对应方法的 KDoc 里写明：
 *  - [beginQualitySwitch] 直接复用 [start]（硬切换，没有并行预热通道）
 *  - 没有音频，因此 [currentPacingNanos] 以挂钟推进而非音频主时钟
 *  - [suspend] / [resume] 是「关掉再重开」，不保留 native 资源
 */
internal class FfmpegSession(
    private val debugLabel: String,
    private val clock: PlaybackClock,

    /** 运行渲染线程（GL）清理工作。 */
    private val renderExecutor: com.dreamdisplayx.api.media.player.RenderExecutor,

    /** 创建 GPU 帧上传器。 */
    private val uploaderFactory: FrameUploaderFactory,

    /** 配置里的硬解开关，透传给 [FfmpegCodecScheduler.selectBackend]。 */
    private val useHwAccel: Boolean,

    /** 会话结束（正常 EOF 或不可恢复错误）时通知上层。 */
    private val onStreamEnd: (stderr: String, normalEos: Boolean) -> Unit = { _, _ -> },
) : PlaybackSession {

    /** 后端标识。宿主代码若需要区分后端，查这个名字而不是做 `is` 判断。 */
    override val backendName: String = "ffmpeg"

    private val logger = DreamLog.of("FfmpegSession")

    // ── 帧发布 ──────────────────────────────────────────────────────────────

    /**
     * 与 libvlc 会话共用的上传面。像素格式固定 BGRA32：解码器已经把硬解 surface 拷回
     * 系统内存并用 `sws_scale` 打包成 BGRA，与 libvlc 的 vmem 输出落在同一条上传路径上。
     */
    private val surface = FrameSurface(debugLabel, uploaderFactory, FramePixelFormat.BGRA32)

    /** 渲染线程看到的纹理尺寸；由 [updateFrame] 采纳，帧发布时按它缩放。 */
    @Volatile
    private var expectedW = 0
    @Volatile
    private var expectedH = 0

    /** 每交付一帧就更新，看门狗用它判断是否卡死。 */
    private val lastFrame = AtomicLong(0L)
    override val lastFrameNanos: AtomicLong get() = lastFrame

    // ── 交付帧率诊断 ────────────────────────────────────────────────────────

    @Volatile
    private var fpsFrames = 0L
    @Volatile
    private var fpsWindowStartNanos = 0L
    @Volatile
    private var fpsValue = 0.0
    override val currentVideoFps: Double get() = fpsValue

    @Volatile
    private var lastFrameGapNanos = 0L
    @Volatile
    private var gapMinMs = 0L
    @Volatile
    private var gapMaxMs = 0L
    @Volatile
    private var gapSumMs = 0L
    @Volatile
    private var gapCount = 0L

    override fun frameIntervalInfo(): String? {
        if (gapCount <= 0) return null
        val avg = gapSumMs.toDouble() / gapCount
        return "${gapMinMs}/${"%.0f".format(avg)}/${gapMaxMs}ms"
    }

    private fun recordFrameGap() {
        val now = System.nanoTime()
        val prev = lastFrameGapNanos
        lastFrameGapNanos = now
        if (prev == 0L) return
        val gap = (now - prev) / 1_000_000L
        if (gap <= 0L) return
        if (gapMinMs == 0L || gap < gapMinMs) gapMinMs = gap
        if (gap > gapMaxMs) gapMaxMs = gap
        gapSumMs += gap
        gapCount++
    }

    // ── 状态 ────────────────────────────────────────────────────────────────

    private val released = AtomicBoolean(false)
    private val stopped = AtomicBoolean(true)

    /** 解码循环线程。每会话一条，串行访问 demuxer/solver。 */
    @Volatile
    private var decodeThread: Thread? = null

    @Volatile
    private var demuxer: FfmpegDemuxer? = null
    @Volatile
    private var solver: AndroidMediaCodecHwSolver? = null

    /** 当前流集合与偏移，供 [suspend] / [resume] 重新打开。 */
    @Volatile
    private var currentStreamSet: ActiveStreams? = null
    @Volatile
    private var currentOffsetNanos: Long = 0L
    @Volatile
    private var currentQuality = 0

    /** 当前是否在播放（已起播且未被停止）。 */
    @Volatile
    private var playing = false
    override val isPlaying: Boolean get() = playing && !stopped.get()

    /**
     * 起播期间「是否已经把第一帧交出去」。
     *
     * [start] 是同步约定（调用方拿到 true 就认为有画面了），但解封装 + 首帧解码需要
     * 若干百毫秒，所以用这个闩等待，超时按失败处理。
     *
     * 必须是 `var` 而不是 `val`：`CountDownLatch` 一旦计数归零就无法复位，而同一个会话
     * 会因为 seek / 质量切换 / resume 反复 [start]。若复用同一个闩，第二次起播会立刻
     * 「等到」上一轮留下的计数，于是永远观察到首帧成功——假成功比超时更糟。
     */
    @Volatile
    private var firstFrameLatch = java.util.concurrent.CountDownLatch(1)
    @Volatile
    private var firstFrameFired = false

    /**
     * 起播 seek 的目标时刻（微秒）。早于它的帧全部丢弃。
     *
     * `av_seek_frame` 只能定位到目标**之前**的最近关键帧，所以 seek 之后解码器会先吐出
     * 关键帧到目标之间那一小段。不丢就会看到「拖到 10:00，先闪 2 秒 9:58 的画面」。
     * [Long.MIN_VALUE] 表示没有下限（从当前位置照常播）。
     */
    @Volatile
    private var discardBeforeMicros = Long.MIN_VALUE

    // ── 停靠（park）状态 ────────────────────────────────────────────────────

    private val parked = AtomicBoolean(false)
    @Volatile
    private var parkedPosition = -1L

    // ── 帧 sinks ────────────────────────────────────────────────────────────

    override var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null
    override var previewFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null
    override var ambientLightSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    // ── 渲染线程半边 ────────────────────────────────────────────────────────

    init {
        // 与 libvlc 会话同样的显示时刻选择：每个待显示帧带内容 PTS，渲染线程取「当前播放
        // 位置应显示的那一帧」，而不是「最后到达的那一帧」。40ms 容差对应 25fps。
        surface.enableDisplaySync({ currentPacingNanos() }, DISPLAY_SYNC_FRAME_NS)
    }

    override fun textureFilled(): Boolean = surface.textureFilled()

    override fun updateFrame(texture: GpuTextureRef, actualW: Int, actualH: Int): Boolean {
        if (actualW != expectedW || actualH != expectedH) {
            // 渲染线程给的尺寸是权威尺寸：采纳它，并丢掉按旧尺寸排队的帧，
            // 下一帧发布时会按新尺寸缩放，此后的上传就能对上。
            if (actualW > 0 && actualH > 0) {
                expectedW = actualW
                expectedH = actualH
            }
            surface.clear()
            return false
        }
        return surface.updateFrame(texture, actualW, actualH, expectedW, expectedH)
    }

    override fun updateFramePlanar(
        y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, actualW: Int, actualH: Int,
    ): Boolean = false // 本会话只产出打包 BGRA，平面通道无意义

    override fun clearFrame() = surface.clear()

    override fun hasIncoming(): Boolean = false // 硬切换，没有并行预热通道
    override fun updateIncomingFrame(texture: GpuTextureRef, actualW: Int, actualH: Int): Boolean = false
    override fun updateIncomingFramePlanar(
        y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, actualW: Int, actualH: Int,
    ): Boolean = false
    override fun promoteIncoming(): Boolean = true

    // ── 起播 / 停止 ─────────────────────────────────────────────────────────

    /**
     * 起播 [streamSet]。
     *
     * 打开解封装器 → 由策略选定解码后端 → 打开解码器 → 起解码线程 → **等首帧**。
     * 返回 true 意味着第一个帧已经交给 [FrameSurface]，调用方可以认为有画面了。
     *
     * 硬解打不开时会问 [FfmpegCodecScheduler.reportFailure] 要下一档并重试，
     * 一直到链尾；链尾仍失败才返回 false（上层据此整体回退，例如交给 libvlc）。
     */
    override fun start(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean {
        if (released.get()) return false
        // 起播前的清理顺序很重要：先让解码线程退出，否则它会继续用即将被释放的
        // native 上下文，属于「关掉时偶发 SIGSEGV」的典型成因。
        stopPipeline(waitForThread = true)

        currentStreamSet = streamSet
        currentOffsetNanos = offsetNanos
        currentQuality = lastQuality
        stopped.set(false)
        parked.set(false)
        parkedPosition = -1L
        firstFrameFired = false
        resetFirstFrameLatch()
        clock.reset(offsetNanos)

        val url = streamSet.currentVideo.url
        if (url.isBlank()) {
            logger.warn("{} start: blank video url", debugLabel)
            stopped.set(true)
            return false
        }

        val dm = FfmpegDemuxer(url, referer = refererFor(url), userAgent = USER_AGENT)
        if (!dm.open()) {
            logger.warn("{} demuxer open failed: {}", debugLabel, dm.lastError)
            dm.close()
            return false
        }

        val codec = FfmpegCodec.fromName(FfmpegDemuxer.codecName(dm.videoCodecId))
        var selection = FfmpegCodecScheduler.selectBackend(codec, useHwAccel, noHardwareAccel = false)
        if (selection == null) {
            logger.warn("{} no FFmpeg backend for {} — giving up", debugLabel, codec)
            dm.close()
            stopped.set(true)
            return false
        }
        val sv = openSolverWithFallback(dm, codec, selection) ?: run {
            logger.warn("{} all FFmpeg backends failed for {}", debugLabel, codec)
            dm.close()
            stopped.set(true)
            return false
        }

        // 起播偏移必须在这里落实：`av_seek_frame` 只能定位到目标之前的关键帧，
        // 所以还要记住目标时刻，让 [deliverFrame] 把关键帧到目标之间那段丢掉，
        // 否则 seek 到 10 分钟会先播从 9:58 关键帧开始的画面。
        // 偏移为 0 时不必 seek（open 之后本来就在开头）。
        if (offsetNanos > 0L) {
            val targetMicros = offsetNanos / 1_000L
            if (dm.seekTo(targetMicros)) {
                sv.flush()
                discardBeforeMicros = targetMicros
            } else {
                // seek 失败不致命：从开头播总比不给画面好，但要说清楚发生了什么。
                logger.warn(
                    "{} seek to {}ns failed ({}); playing from start",
                    debugLabel, offsetNanos, dm.lastError,
                )
            }
        } else {
            discardBeforeMicros = Long.MIN_VALUE
        }


        demuxer = dm
        solver = sv
        selection = FfmpegCodecScheduler.activeSelection ?: selection
        logger.info(
            "{} started: codec={} backend={} (chain {})",
            debugLabel, codec, selection.decoderName,
            FfmpegHwBackendPolicy.describe(selection.chain),
        )

        startDecodeLoop()
        playing = true

        // 等首帧：起播是同步语义，没等到就当作失败，避免上层以为在播其实黑屏。
        val got = try {
            firstFrameLatch.await(FIRST_FRAME_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!got) {
            logger.error("{} first frame timeout ({}ms)", debugLabel, FIRST_FRAME_TIMEOUT_MS)
            stopPipeline(waitForThread = true)
            return false
        }
        return true
    }

    /**
     * 从 [selection] 开始，逐档尝试打开解码器；每一档失败就问调度器要下一档。
     *
     * @return 打开成功且已经记入调度器的解码器；全部失败返回 null
     */
    private fun openSolverWithFallback(
        dm: FfmpegDemuxer,
        codec: FfmpegCodec,
        first: FfmpegHwSelection,
    ): AndroidMediaCodecHwSolver? {
        val extradata = dm.videoExtradata()
        var sel: FfmpegHwSelection? = first
        while (sel != null) {
            val candidate = AndroidMediaCodecHwSolver(
                width = dm.videoWidth,
                height = dm.videoHeight,
                decoderName = sel.decoderName,
                hardware = sel.hardware,
            )
            if (candidate.open(extradata)) return candidate
            logger.warn(
                "{} backend '{}' failed to open: {}",
                debugLabel, sel.decoderName, candidate.lastError,
            )
            candidate.close()
            val failed = sel
            sel = FfmpegCodecScheduler.reportFailure(failed, candidate.lastError)
            if (sel == null) {
                logger.warn("{} chain exhausted, no further backend", debugLabel)
                return null
            }
        }
        return null
    }

    /**
     * 起解码线程。
     *
     * 线程体是「读包 → 送解码 → 交付帧」的紧循环。三种结束方式：EOF（正常结束）、
     * 错误（上报给上层）、以及 [stopPipeline] 把 [stopped] 置位（静默退出）。
     */
    private fun startDecodeLoop() {
        val thread = Thread({
            try {
                decodeLoop()
            } catch (t: Throwable) {
                // 绝不让异常穿透到线程默认处理器：那会把整条播放静默掐断而没有日志。
                logger.error("{} decode loop threw {}: {}", debugLabel, t::class.simpleName, t.message)
                if (!stopped.get() && !released.get()) {
                    onStreamEnd(t.message ?: "ffmpeg decode failed", false)
                }
            }
        }, "MediaPlayer-ffmpeg-$debugLabel")
        thread.isDaemon = true
        decodeThread = thread
        thread.start()
    }

    private fun decodeLoop() {
        val dm = demuxer ?: return
        val sv = solver ?: return
        while (!stopped.get() && !released.get()) {
            when (val r = dm.readVideoPacket()) {
                FfmpegDemuxer.Result.PACKET_READY -> {
                    val view = dm.packetView() ?: continue
                    val size = dm.packetSize()
                    val pts = dm.packetPtsMicros()
                    when (val dr = sv.decode(view, size, pts)) {
                        AndroidMediaCodecHwSolver.Result.FRAME_READY ->
                            // 用帧自己的 PTS，而不是刚送进去那个包的 PTS：B 帧重排后两者不同序，
                            // 拿输入包的时间戳会让显示时刻算错（画面抖动 / 显示同步失效）。
                            deliverFrame(sv, sv.lastFramePtsMicros)
                        AndroidMediaCodecHwSolver.Result.NEED_MORE_INPUT -> Unit // 常态，继续送包
                        AndroidMediaCodecHwSolver.Result.EOF -> {
                            finishNormally()
                            return
                        }
                        AndroidMediaCodecHwSolver.Result.ERROR -> {
                            // 解码器出错先尝试降级：这就是选择器真正救场的地方。
                            if (tryDecodeFallback(sv)) continue
                            logger.error("{} decode error: {}", debugLabel, sv.lastError)
                            finishWithError(sv.lastError.ifBlank { "ffmpeg decode error" })
                            return
                        }
                        AndroidMediaCodecHwSolver.Result.NOT_OPEN -> {
                            finishWithError("decoder closed unexpectedly")
                            return
                        }
                    }
                }
                FfmpegDemuxer.Result.EOF -> {
                    finishNormally()
                    return
                }
                FfmpegDemuxer.Result.ERROR -> {
                    logger.warn("{} demux error: {}", debugLabel, dm.lastError)
                    finishWithError(dm.lastError.ifBlank { "ffmpeg demux error" })
                    return
                }
                FfmpegDemuxer.Result.NOT_OPEN -> {
                    finishWithError("demuxer closed unexpectedly")
                    return
                }
                // readVideoPacket() cannot produce this: it exists for readPacket(),
                // which returns audio packets to the audio decode path. Reaching it
                // here means the wrong reader was called, so fail loudly instead of
                // spinning on a packet nobody consumes.
                FfmpegDemuxer.Result.AUDIO_PACKET_READY -> {
                    finishWithError("audio packet delivered to the video decode loop")
                    return
                }
            }
        }
    }

    /**
     * 解码阶段失败后尝试换一个后端。
     *
     * 与打开阶段的降级不同：此时解码器已经跑过，必须先彻底关掉再换。
     * 强制指定（`step == -1`）时如实拒绝，不去猜用户没要求的后端。
     *
     * @return true 表示已经换到新后端并可以继续循环；false 表示无可降级
     */
    private fun tryDecodeFallback(sv: AndroidMediaCodecHwSolver): Boolean {
        val dm = demuxer ?: return false
        val codec = FfmpegCodec.fromName(FfmpegDemuxer.codecName(dm.videoCodecId))
        val current = FfmpegCodecScheduler.activeSelection ?: return false
        val next = FfmpegCodecScheduler.reportFailure(current, sv.lastError) ?: return false
        logger.warn(
            "{} switching decoder backend to '{}' after failure",
            debugLabel, next.decoderName,
        )
        sv.close()
        val replacement = AndroidMediaCodecHwSolver(
            width = dm.videoWidth,
            height = dm.videoHeight,
            decoderName = next.decoderName,
            hardware = next.hardware,
        )
        if (!replacement.open(dm.videoExtradata())) {
            logger.warn("{} replacement backend '{}' failed: {}", debugLabel, next.decoderName, replacement.lastError)
            replacement.close()
            return false
        }
        // 新解码器必须从关键帧起步：此刻 demuxer 停在中间，直接喂包会一直解不出帧
        // （非关键帧缺少参考帧）。回到当前播放位置之前的关键帧重新解。
        val resumeAtMicros = currentPacingNanos() / 1_000L
        if (resumeAtMicros > 0L && dm.seekTo(resumeAtMicros)) {
            replacement.flush()
            discardBeforeMicros = resumeAtMicros
        } else if (resumeAtMicros > 0L) {
            logger.warn(
                "{} could not re-seek to {}us after backend switch ({}); output may stall",
                debugLabel, resumeAtMicros, dm.lastError,
            )
        }
        solver = replacement
        logger.info("{} decoder backend now '{}' (codec {})", debugLabel, next.decoderName, codec)
        return true
    }

    /**
     * 把解码器当前的 BGRA 缓冲交给渲染侧，并喂给三个可选的原始帧 sink。
     *
     * 与 libvlc 会话一样：sink 拿到的是「可见行紧密排列」的帧，尺寸按
     * [expectedW]/[expectedH]（渲染线程的纹理尺寸）缩放，而不是解码尺寸——
     * 否则 4K 源在 1080p 纹理上会直接越界。
     */
    private fun deliverFrame(sv: AndroidMediaCodecHwSolver, ptsMicros: Long) {
        // seek 之后、真正到达目标时刻之前的帧要丢掉：关键帧定位让解码起点早于目标。
        val floor = discardBeforeMicros
        if (floor != Long.MIN_VALUE && ptsMicros != AndroidMediaCodecHwSolver.AV_NOPTS_VALUE &&
            ptsMicros < floor
        ) {
            return
        }
        // 一旦越过目标，就把下限撤掉，后续帧正常交付（否则回转/时间戳跳变会永久黑屏）。
        if (floor != Long.MIN_VALUE && ptsMicros != AndroidMediaCodecHwSolver.AV_NOPTS_VALUE) {
            discardBeforeMicros = Long.MIN_VALUE
        }

        val src = sv.outputBgra() ?: return
        val srcW = sv.frameWidth
        val srcH = sv.frameHeight
        if (srcW <= 0 || srcH <= 0) return

        val ew = if (expectedW > 0) expectedW else srcW
        val eh = if (expectedH > 0) expectedH else srcH
        val frameSize = ew * eh * 4

        val spare = surface.takeOrAllocate(frameSize)
        spare.clear()
        try {
            if (srcW == ew && srcH == eh) {
                spare.put(src.duplicate().also { it.position(0).limit(srcW * srcH * 4) })
            } else {
                if (!scaleInto(src, srcW, srcH, spare, ew, eh)) return
            }
            spare.flip()
        } catch (t: Throwable) {
            // 缓冲容量不足等：这一帧丢掉，但不要杀死整条管线。
            logger.warn("{} frame copy failed: {}", debugLabel, t.message)
            return
        }

        if (stopped.get()) return

        // 原始帧 sink（弹出窗口 / 预览 / 氛围灯）。诊断可开关，和 libvlc 侧一致。
        popoutFrameSink?.let { runCatching { it(spare, ew, eh, FramePixelFormat.BGRA32) } }
        previewFrameSink?.let { runCatching { it(spare, ew, eh, FramePixelFormat.BGRA32) } }
        ambientLightSink?.let { runCatching { it(spare, ew, eh, FramePixelFormat.BGRA32) } }

        val ptsNanos = if (ptsMicros == AndroidMediaCodecHwSolver.AV_NOPTS_VALUE) {
            FrameSurface.SHOW_NOW
        } else {
                ptsMicros * 1_000L
            }
        surface.publish(spare, frameSize, ptsNanos)
        lastFrame.set(System.nanoTime())

        // FPS 与帧间隔统计
        val now = System.nanoTime()
        if (fpsWindowStartNanos == 0L) fpsWindowStartNanos = now
        fpsFrames++
        recordFrameGap()
        val elapsed = now - fpsWindowStartNanos
        if (elapsed >= 1_000_000_000L) {
            val fps = fpsFrames.toDouble() * 1_000_000_000.0 / elapsed
            fpsValue = if (fpsValue <= 0.0) fps else fpsValue * 0.5 + fps * 0.5
            fpsFrames = 0L
            fpsWindowStartNanos = now
        }

        if (!firstFrameFired) {
            firstFrameFired = true
            clock.markFirstFrame()
            firstFrameLatch.countDown()
            logger.info("{} first frame delivered {}x{} (ffmpeg)", debugLabel, ew, eh)
        }
    }

    /**
     * 把 [src] 里的 [srcW]×[srcH] BGRA 缩放/裁剪到 [dst] 的 [dstW]×[dstH]。
     *
     * 只在「解码尺寸 ≠ 纹理尺寸」时走（质量切换的过渡帧、纹理尺寸刚被渲染线程改动），
     * 所以正确性优先于画质：按源行整行拷进 [ByteArray] 一次，再按目标列挑像素。
     *
     * 为什么不像朴素写法那样逐字节 `get`/`put`：1080p 一帧是 8.3MB、两百多万次跨界
     * JNI 调用，一帧就能吃掉几十毫秒，直接把解码线程拖成幻灯片。这里每个源行只做
     * 一次批量 `get`，每目标行只做一次批量 `put`，跨界次数与行数同阶。
     */
    private fun scaleInto(
        src: ByteBuffer, srcW: Int, srcH: Int,
        dst: ByteBuffer, dstW: Int, dstH: Int,
    ): Boolean {
        if (dstW <= 0 || dstH <= 0 || srcW <= 0 || srcH <= 0) return false
        val srcRow = srcW * 4
        val dstRow = dstW * 4
        if (dst.capacity() < dstRow * dstH) return false
        if (src.capacity() < srcRow * srcH) return false

        val srcView = src.duplicate().also { it.position(0).limit(srcRow * srcH) }
        val srcRowBuf = ByteArray(srcRow)
        val dstRowBuf = ByteArray(dstRow)

        // 预先算好每个目标列对应的源列，避免在内层循环里做除法。
        val colMap = IntArray(dstW)
        for (x in 0 until dstW) {
            colMap[x] = ((x.toLong() * srcW / dstW).toInt()).coerceIn(0, srcW - 1) * 4
        }

        for (y in 0 until dstH) {
            val sy = ((y.toLong() * srcH / dstH).toInt()).coerceIn(0, srcH - 1)
            srcView.position(sy * srcRow)
            srcView.get(srcRowBuf, 0, srcRow)
            var di = 0
            for (x in 0 until dstW) {
                val si = colMap[x]
                dstRowBuf[di] = srcRowBuf[si]
                dstRowBuf[di + 1] = srcRowBuf[si + 1]
                dstRowBuf[di + 2] = srcRowBuf[si + 2]
                dstRowBuf[di + 3] = srcRowBuf[si + 3]
                di += 4
            }
            dst.put(dstRowBuf, 0, dstRow)
        }
        return true
    }

    private fun finishNormally() {
        if (stopped.getAndSet(true)) return
        playing = false
        logger.info("{} reached end of stream", debugLabel)
        onStreamEnd("", true)
    }

    private fun finishWithError(message: String) {
        if (stopped.getAndSet(true)) return
        playing = false
        logger.error("{} stream error: {}", debugLabel, message)
        onStreamEnd(message, false)
    }

    /**
     * 停掉解码线程并释放 native 资源。
     *
     * @param waitForThread true 时等解码线程真正退出后再释放。等的是必需的：
     *   解码线程正在 `decode()` 里持有 AVCodecContext，提前 free 就是释放后使用。
     */
    private fun stopPipeline(waitForThread: Boolean) {
        stopped.set(true)
        val t = decodeThread
        if (waitForThread && t != null && t !== Thread.currentThread()) {
            runCatching { t.join(THREAD_JOIN_TIMEOUT_MS) }
        }
        decodeThread = null
        runCatching { solver?.close() }
        runCatching { demuxer?.close() }
        solver = null
        demuxer = null
    }

    private fun resetFirstFrameLatch() {
        // 每轮起播换一个新闩。老的闩若还有线程在 await，它会在自己的超时后自行返回，
        // 不需要（也不应该）在这里 countDown；否则上一轮的等待会被当成这一轮的成功。
        firstFrameLatch = java.util.concurrent.CountDownLatch(1)
    }

    override fun stop() {
        playing = false
        stopped.set(true)
        stopPipeline(waitForThread = true)
        surface.clear()
        resetFps()
    }

    override fun stopNow() {
        // 语义上是「不再等优雅收尾」，但 native 上下文仍然必须等解码线程退出再释放，
        // 否则线程会用已释放的 AVCodecContext。区别只在于不刻意清空队列。
        playing = false
        stopped.set(true)
        stopPipeline(waitForThread = true)
        resetFps()
    }

    override fun pauseNow() {
        // 没有独立的解码器暂停接口：停掉管线即可（位置已记在 clock 里）。
        playing = false
        stopPipeline(waitForThread = true)
    }

    private fun resetFps() {
        fpsFrames = 0L
        fpsWindowStartNanos = 0L
        fpsValue = 0.0
        lastFrameGapNanos = 0L
        gapMinMs = 0L
        gapMaxMs = 0L
        gapSumMs = 0L
        gapCount = 0L
    }

    // ── Seek / 质量切换 ─────────────────────────────────────────────────────

    /**
     * Seek：重新打开解封装器并跳到目标位置。
     *
     * [FfmpegDemuxer] 没有实现 `av_seek_frame`，所以这里采用「重开 + 丢弃」策略：
     * 按字节率估算目标对应的包数并丢掉，这对 VOD 足够，对直播不适用。
     * 更彻底的做法是给 demuxer 加真正的 seek，已记在 README 的待办里。
     */
    override fun beginSeek(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean {
        val wasPlaying = isPlaying
        stopPipeline(waitForThread = true)
        surface.clear()
        currentStreamSet = streamSet
        currentOffsetNanos = offsetNanos
        currentQuality = lastQuality
        if (!wasPlaying) {
            // 冷暂停里 seek：只记位置，下次 start 从那里开始。
            clock.reset(offsetNanos)
            return true
        }
        return start(streamSet, offsetNanos, lastQuality)
    }

    /** 质量切换：与 libvlc 会话一致地做硬切换（没有并行预热通道）。 */
    override fun beginQualitySwitch(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int) {
        stopPipeline(waitForThread = true)
        surface.clear()
        val ok = start(streamSet, offsetNanos, lastQuality)
        if (!ok) logger.warn("{} quality switch failed to restart", debugLabel)
    }

    /**
     * 只换音频。本会话不承载音频（音频仍由 libvlc 负责），所以这里只需回 true 表示
     * 「选择已接受」，真正的音频替换由上层交给短视频会话处理。
     */
    override fun restartAudio(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean {
        if (released.get()) return false
        logger.info("{} restartAudio: audio is not owned by this session (stays on libvlc)", debugLabel)
        return true
    }

    // ── 停靠 / 挂起 / 恢复 ──────────────────────────────────────────────────

    override fun canPark(): Boolean = true

    /**
     * 停靠：释放 native 资源但记住位置。
     *
     * 参数保留是为了满足契约；本实现不区分 `allowExternalProcess` / `retainBuffered`，
     * 因为没有外部进程，也没有值得保留的缓冲。
     */
    override fun suspend(allowExternalProcess: Boolean, retainBuffered: Boolean): Boolean {
        if (released.get()) return false
        parkedPosition = currentPacingNanos()
        parked.set(true)
        playing = false
        stopPipeline(waitForThread = true)
        surface.clear()
        resetFps()
        logger.info("{} parked at {}ns", debugLabel, parkedPosition)
        return true
    }

    override fun resume() {
        if (released.get() || !parked.get()) return
        val streamSet = currentStreamSet ?: return
        val at = parkedPosition
        parked.set(false)
        parkedPosition = -1L
        val ok = start(streamSet, if (at > 0) at else currentOffsetNanos, currentQuality)
        if (!ok) logger.warn("{} resume failed", debugLabel)
    }

    override fun isParked(): Boolean = parked.get()

    override fun parkedPositionNanos(): Long? = parkedPosition.takeIf { parked.get() && it >= 0 }

    override fun repositionParked(nanos: Long) {
        if (parked.get() && nanos >= 0) parkedPosition = nanos
    }

    // ── 状态查询 ────────────────────────────────────────────────────────────

    /**
     * 播放位置（纳秒）。
     *
     * 本会话没有音频主时钟，因此以 [PlaybackClock] 为准：它在首帧时开始走，
     * seek 时重置。这比 libvlc 的 `get_time` 粗，但没有第二个时钟可以对齐。
     */
    override fun currentPacingNanos(): Long = clock.currentTime()

    override fun activeBridgeEdgeNanos(): Long? = null

    // ── 音频（不在本会话职责内） ────────────────────────────────────────────

    override fun setVolume(volume: Double) = Unit

    override fun verifyVolume() = Unit

    override fun setWarmAudioTracks(tracks: List<WarmTrack>) = Unit

    override fun captureAudioPcm(maxNanos: Long): ByteArray? = null

    // ── 回放（不支持） ──────────────────────────────────────────────────────

    override fun captureVideoCacheSnapshot(): ByteArray? = null

    override fun startReplayVideoOnly(
        snapshot: ByteArray?, resume: Long, positionNanos: Long, audioPcm: ByteArray?,
    ): Boolean = false

    override fun attachLiveAfterReplay(streamSet: ActiveStreams, liveOffsetNanos: Long, lastQuality: Int): Boolean =
        start(streamSet, liveOffsetNanos, lastQuality)

    // ── 拆除 ────────────────────────────────────────────────────────────────

    override fun cleanup() {
        if (!released.compareAndSet(false, true)) return
        playing = false
        stopped.set(true)
        parked.set(false)
        stopPipeline(waitForThread = true)
        // FrameSurface 持有 GPU 上传器，必须在渲染线程释放。
        renderExecutor.execute { surface.cleanup() }
    }

    private companion object {
        /**
         * 首帧等待上限。
         *
         * 取 10s 与 libvlc 会话一致：两者都是「起播是同步的，但首帧未必立刻到」，
         * 超时按失败处理，好让上层走错误路径而不是永远停在加载态。
         */
        const val FIRST_FRAME_TIMEOUT_MS = 10_000L

        /** 解码线程退出等待上限，防止 join 把控制线程永久挂住。 */
        const val THREAD_JOIN_TIMEOUT_MS = 5_000L

        /** 显示时刻选择的容差（40ms ≈ 25fps），与 libvlc 会话取值一致。 */
        const val DISPLAY_SYNC_FRAME_NS = 40_000_000L

        /** 浏览器形状 UA，CDN 防盗链会拒绝默认 UA。 */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        /** 从 URL 反查需要的 Referer（Bilibili 等平台的防盗链）。 */
        fun refererFor(url: String): String? =
            runCatching { com.dreamdisplayx.api.security.policy.MediaHosts.refererFor(url) }.getOrNull()
    }
}
