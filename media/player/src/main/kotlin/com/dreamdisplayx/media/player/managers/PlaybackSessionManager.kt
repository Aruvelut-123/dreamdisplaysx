package com.dreamdisplayx.media.player.managers

import com.dreamdisplayx.api.media.model.DreamMediaException
import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.audio.service.AudioDspStage
import com.dreamdisplayx.api.media.model.StretchMode
import com.dreamdisplayx.api.media.player.FrameUploaderFactory
import com.dreamdisplayx.api.media.player.GpuTextureRef
import com.dreamdisplayx.api.media.player.RenderExecutor
import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.media.player.events.PlayerEvents
import com.dreamdisplayx.media.player.pipeline.*
import com.dreamdisplayx.media.player.stream.ActiveStreams
import com.dreamdisplayx.media.player.stream.MediaStreamSelector
import com.dreamdisplayx.media.player.util.MediaUtil
import com.dreamdisplayx.media.player.util.daemon
import com.dreamdisplayx.media.player.util.joinSafely
import com.dreamdisplayx.media.runtime.security.MediaHostGuard
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages `FFmpeg` session (processes, threads, pipes). Quality switch runs parallel video channel.
 */
internal class PlaybackSessionManager(
    private val debugLabel: String,
    private val clock: PlaybackClock,
    private val events: PlayerEvents,
    private val terminated: AtomicBoolean,

    /** Returns the current GPU texture dimensions (width to height). */
    private val getTextureSize: () -> Pair<Int, Int>,
    private val getBrightness: () -> Double,
    private val getStretchMode: () -> StretchMode = { StretchMode.LETTERBOX },

    /** Invoked by the live video channel when the stream ends or errors. Called on the reader thread. */
    private val onStreamEnd: (stderr: String, normalEos: Boolean) -> Unit,

    /** Invoked when quality switch fails before promotion (can drop staged texture) */
    private val onQualitySwitchAborted: (appliedAnyway: Boolean) -> Unit = {},

    /** Invoked when live audio process ends unexpectedly (called on audio reader thread). */
    private val onAudioFailure: (stderr: String) -> Unit = {},

    /** Invoked once an in-flight [beginAudioTrackSwitch] settles, either way (promoted or gave up). */
    private val onAudioTrackSwitchSettled: () -> Unit = {},

    /** Runs render-thread (GL) cleanup work. */
    private val renderExecutor: RenderExecutor,

    /** Creates per-channel GPU frame uploaders. */
    private val uploaderFactory: FrameUploaderFactory,

    /** Whether the GPU-side planar (I420) render path is active. */
    private val gpuYuvActive: Boolean,

    /** Optional per-display acoustics DSP stage; null keeps the legacy distance-gain-only pipeline. */
    audioStage: AudioDspStage? = null,
) {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/PlaybackSession")

    private companion object {
        /** Pacing cadence for replay-only video; PTS still drives pacing, this is only the fallback. */
        const val REPLAY_FPS = 30.0

        /** Frame rate assumed when the source reports none, or reports something implausible. */
        private fun outputFps(sourceFps: Double?): Double =
            sourceFps?.takeIf { it.isFinite() && it > 1.0 && it <= 240.0 } ?: REPLAY_FPS

        /** How many silent-source verdicts to remember; well past any one player's stream ladder. */
        const val SILENT_MEMO_LIMIT = 64

        /** Audio URLs proven to carry no audio track (process-wide, insertion-bounded). */
        val SILENT_SOURCES: MutableSet<String> = Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean =
                    size > SILENT_MEMO_LIMIT
            }.let { Collections.synchronizedMap(it) },
        )
    }

    /** Audio half of session: process, thread, stop flag. */
    private class AudioHalf(val process: Process?, val thread: Thread, val stop: AtomicBoolean)

    /**
     * One decode channel: JavaCPP-based video pipe (replaces the old native + process pipelines).
     */
    private inner class VideoChannel {
        val javaCppPipe: JavaCppVideoPipe = JavaCppVideoPipe(debugLabel, uploaderFactory, gpuYuvActive)
        val pipe: FramePipe = javaCppPipe

        @Volatile
        var thread: Thread? = null

        val stop = AtomicBoolean()

        /**
         * Launches video decode into this channel's JavaCPP-based pipe.
         */
        fun launch(
            streamSet: ActiveStreams, w: Int, h: Int, offsetNanos: Long,
            onFirstFrame: () -> Unit, onEos: (String, Boolean) -> Unit,
            getAudioClock: () -> Long = ::pacingClockNanos, parkFlag: AtomicBoolean? = null,
            presentPreview: Boolean = true, tolerateLateness: Boolean = true,
            onDriftResync: (() -> Unit)? = null,
        ) {
            val safeUrl = MediaHostGuard.resolveSafeUrl(streamSet.currentVideo.url)
            val fps = outputFps(streamSet.currentVideo.fps)
            val vt = javaCppPipe.start(
                url = safeUrl, w = w, h = h, seekOffsetNanos = offsetNanos,
                sourceFps = fps, stopFlag = stop, terminated = terminated,
                getAudioClock = getAudioClock, onFirstFrame = onFirstFrame,
                getBrightness = getBrightness, getStretchMode = getStretchMode,
                onEos = onEos, parkFlag = parkFlag,
                presentPreview = presentPreview, tolerateLateness = tolerateLateness,
                onDriftResync = onDriftResync,
            ) ?: throw IOException("JavaCPP video session failed to start")
            thread = vt
        }

        /** Stops the decode and joins the reader thread (blocking). Must not run on the render thread. */
        fun teardownProcess() {
            stop.set(true)
            javaCppPipe.kill()
            thread?.let { joinSafely(it) }
            javaCppPipe.release()
        }
    }

    private val audio = AudioSink(debugLabel)

    /**
     * True while the current session plays a live stream; set by [start], reused by every audio
     * (re)launch in the same session to pick the transport for the audio process.
     */
    @Volatile
    private var liveSession = false

    private var audioDecoders = mutableListOf<JavaCppAudioDecoder>()

    /** Spawns audio decoder (JavaCPP) — returns a Process wrapper or null for silent sources. */
    @Throws(IOException::class)
    private fun buildAudioDecoder(
        streamSet: ActiveStreams, offsetNanos: Long, stopFlag: AtomicBoolean,
    ): Process? {
        val url = streamSet.currentAudio.url
        if (url in SILENT_SOURCES) {
            silentSession = true
            return null
        }
        val decoder = JavaCppAudioDecoder(debugLabel)
        val inputStream = decoder.start(
            url = url,
            seekOffsetNanos = offsetNanos,
            stopFlag = stopFlag,
            terminated = terminated,
            parkFlag = parkFlag,
        ) ?: throw IOException("Failed to start audio decoder for $url")
        val proc = JavaCppAudioProcess(decoder, inputStream as java.io.PipedInputStream)
        synchronized(audioDecoders) { audioDecoders.add(decoder) }
        return proc
    }

    /** True if audio starts at known position (always true with JavaCPP decoder). */
    private fun audioOriginKnown(): Boolean = true

    /** Audio source is always present with JavaCPP decoder (no HLS feed to track). */
    fun audioSourceGone(): Boolean = false

    /** True once source has no audio (separate from transient gap between processes). */
    @Volatile
    private var silentSession = false

    /** Marks URL as silent source (no process spawned, returns true on first mark). */
    fun markSourceSilent(audioUrl: String): Boolean {
        silentSession = true
        return SILENT_SOURCES.add(audioUrl)
    }

    /** Single-line bridge audio session while prelude plays (before live process attached) */
    @Volatile
    private var bridgeAudio: AudioHalf? = null

    /** When true, threads idle in place keeping decoder / line open for instant resume. */
    private val parkFlag = AtomicBoolean(false)

    /** Pre-warmed shadow processes for the audio tracks that are not playing; see [AudioTrackWarmPool]. */
    private val audioWarmPool = AudioTrackWarmPool(
        debugLabel = debugLabel,
        terminated = terminated,
        positionNanos = { clock.currentTime() },
        eligible = { isPlaying && !terminated.get() && !parkFlag.get() && audioOriginKnown() },
    )

    /** Declares the audio tracks to keep pre-warmed; the one currently playing must not be among them. */
    fun setWarmAudioTracks(tracks: List<WarmTrack>) =
        audioWarmPool.setTracks(tracks.filter { it.url !in SILENT_SOURCES })

    init {
        audio.setParkFlag(parkFlag)
        audio.setDspStage(audioStage)
    }

    /** Guards the live/incoming channel transitions across the control, render, and reader threads. */
    private val switchLock = Any()

    @Volatile
    private var active: VideoChannel? = null

    @Volatile
    private var incoming: VideoChannel? = null

    @Volatile
    private var incomingGeneration: Long = 0L

    @Volatile
    private var audioHalf: AudioHalf? = null

    /** Fallback timestamp source when no channel is live (the watchdog guards against reading it then). */
    private val noFrames = AtomicLong(0)

    /** Upper bound the shared wall clock is clamped to while a replay -> live bridge is in flight (the live edge the replay is catching up to). */
    @Volatile
    private var bridgeCeilingNanos: Long = Long.MAX_VALUE

    @Volatile
    var isPlaying = false; private set

    /** True once the first decoded frame of the live channel is ready for GPU upload. */
    fun textureFilled(): Boolean = active?.pipe?.textureFilled() == true

    /** Uploads the latest live frame to [texture]. Returns true if a frame was uploaded. Render thread only. */
    fun updateFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean = active?.pipe?.updateFrame(texture, w, h) == true

    /** Uploads the latest live planar I420 frame into the three plane textures. Returns true if uploaded. */
    fun updateFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        active?.pipe?.updateFramePlanar(y, u, v, w, h) == true

    /** True while an incoming (quality-switch) channel is warming up in parallel. */
    fun hasIncoming(): Boolean = incoming != null

    /** Uploads the latest incoming-channel frame to [texture] (the staged texture). Returns true if uploaded. */
    fun updateIncomingFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean =
        incoming?.pipe?.updateFrame(texture, w, h) == true

    /** Uploads the latest incoming-channel planar I420 frame into the staged plane textures. */
    fun updateIncomingFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean =
        incoming?.pipe?.updateFramePlanar(y, u, v, w, h) == true

    /** Discards the live channel's ready frame. Call when stopping or seeking. */
    fun clearFrame() = active?.pipe?.clear() ?: Unit

    /** Sets the effective volume (user volume * distance attenuation). */
    fun setVolume(volume: Double) {
        audio.currentVolume = volume
    }

    /** Timestamp of the live channel's last decoded video frame; read by [StreamWatchdog]. */
    val lastFrameNanos: AtomicLong get() = active?.pipe?.lastFrameReceivedNanos ?: noFrames

    @Volatile
    private var popoutSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    @Volatile
    private var previewSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    /** Routes raw frames to the popout window. Null = no popout active. */
    var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = popoutSink
        set(value) {
            popoutSink = value
            updateRawFrameSink()
        }

    /** Routes raw frames to the display menu preview when the main texture is GPU-YUV only. */
    var previewFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = previewSink
        set(value) {
            previewSink = value
            updateRawFrameSink()
        }

    private fun updateRawFrameSink() {
        val popout = popoutSink
        val preview = previewSink
        val sink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? =
            if (popout == null && preview == null) null else { buf, w, h, format ->
                val pos = buf.position()
                val limit = buf.limit()
                popout?.invoke(buf, w, h, format)
                buf.position(pos).limit(limit)
                preview?.invoke(buf, w, h, format)
                buf.position(pos).limit(limit)
            }
        // The raw sink follows the live channel; it is re-applied to the new live channel on promotion
        active?.pipe?.popoutFrameSink = sink
    }

    /** Stops any running session, then launches new FFmpeg processes for [streamSet] starting at [offsetNanos]. */
    fun start(
        streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int, live: Boolean = false,
        onFirstFrame: () -> Unit = {},
        onDriftResync: (() -> Unit)? = null,
    ) {
        stop()
        if (terminated.get()) return
        liveSession = live
        silentSession = false
        bridgeCeilingNanos = Long.MAX_VALUE // A full start is not a bridge

        clock.reset(offsetNanos)

        parkFlag.set(false)
        val (w, h) = targetDims(streamSet, lastQuality)
        val channel = VideoChannel()
        try {
            val firstVideoFrame = CountDownLatch(1)
            channel.launch(streamSet, w, h, offsetNanos, onFirstFrame = {
                clock.markFirstFrame()
                firstVideoFrame.countDown()
                onFirstFrame()
            }, onEos = onStreamEnd, parkFlag = parkFlag,
                onDriftResync = onDriftResync)
            val aStop = AtomicBoolean()
            val ap = try {
                buildAudioDecoder(streamSet, offsetNanos, aStop)
            } catch (e: IOException) {
                // The video side is already running; tear it down before propagating
                channel.teardownProcess()
                throw e
            }
            active = channel
            // The audio decoder opens after the video decoder, so by the time
            // audio starts, the video is already ahead by the open gap.  A
            // CatchUp skips the leading PCM gap so the audio clock lands at the
            // video's current position, avoiding the AudioMasterClock stall
            // takeover (750 ms) → wall-clock drift → dropped frames → stuck video.
            val catchUp = AudioSink.CatchUp(offsetNanos) { clock.currentTime() }
            audioHalf = ap?.let {
                AudioHalf(
                    it,
                    audio.start(
                        it, terminated, aStop,
                        contentStartNanos = offsetNanos, originKnown = audioOriginKnown(),
                        startGate = firstVideoFrame, catchUp = catchUp,
                        onUnexpectedEnd = onAudioFailure,
                    ),
                    aStop,
                )
            }
            updateRawFrameSink()
            isPlaying = true
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start FFmpeg", e)
            events.onError(DreamMediaException.Decode("Failed to start FFmpeg: ${e.message}", e))
        }
    }

    /** Seamless seek: silences old audio, warms new stream at offset. */
    fun beginSeek(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean {
        if (!isPlaying || terminated.get() || parkFlag.get()) return false
        audioWarmPool.invalidateAll()
        if (bridgeCeilingNanos != Long.MAX_VALUE) return false
        synchronized(switchLock) { if (incoming != null) return false }
        val old = active ?: return false
        val (w, h) = targetDims(streamSet, lastQuality)

        // ── Fast path: same source ── in-place seek on the existing grabber   ──
        // The grabber stays open (no re-open, no re-probe), the reader thread
        // seeks it directly with setTimestamp. Audio still needs a fresh decoder
        // at the new offset, which is the same work as the full-swap path.
        val safeUrl = MediaHostGuard.resolveSafeUrl(streamSet.currentVideo.url)
        if (old.javaCppPipe.currentUrl == safeUrl && old.javaCppPipe.expectedW == w && old.javaCppPipe.expectedH == h) {
            val firstVideoFrame = CountDownLatch(1)
            if (old.javaCppPipe.requestInPlaceSeek(offsetNanos, firstVideoFrame)) {
                val oldAudio = audioHalf
                audioHalf = null
                oldAudio?.stop?.set(true)
                audio.stop()
                clock.reset(offsetNanos)
                val aStop = AtomicBoolean()
                try {
                    val ap = buildAudioDecoder(streamSet, offsetNanos, aStop)
                    val originKnown = audioOriginKnown()
                    audioHalf = ap?.let {
                        AudioHalf(
                            it,
                            audio.start(
                                it, terminated, aStop,
                                contentStartNanos = offsetNanos, originKnown = originKnown,
                                // Gate audio on the first frame of the new timeline, so sound does
                                // not lead the picture after a seek (same as the full-swap path).
                                // The audio decoder open is fast (small probesize) and any residual
                                // A/V gap after the gate opens is reconciled by AudioMasterClock's
                                // stall takeover + skip-ahead resync, which measure against actual
                                // frame PTS instead of wall clock.
                                startGate = firstVideoFrame, onUnexpectedEnd = onAudioFailure,
                            ),
                            aStop,
                        )
                    }
                    discardHalvesAsync(null, oldAudio)
                    logger.debug("$debugLabel In-place seek to ${offsetNanos / 1_000_000} ms (video grabber reused).")
                    return true
                } catch (e: IOException) {
                    logger.error("$debugLabel Failed to restart audio after in-place seek.", e)
                    discardHalvesAsync(null, oldAudio)
                    return false
                }
            }
            // requestInPlaceSeek returned false (no grabber) — fall through to full channel swap
        }

        // ── Standard path: full channel swap ──
        // Freeze the picture and cut the sound right away
        old.stop.set(true)
        val oldAudio = audioHalf
        audioHalf = null
        oldAudio?.stop?.set(true)
        audio.stop()
        clock.reset(offsetNanos)

        val channel = VideoChannel()
        try {
            val firstVideoFrame = CountDownLatch(1)
            channel.launch(streamSet, w, h, offsetNanos, onFirstFrame = {
                clock.markFirstFrame()
                firstVideoFrame.countDown()
            }, onEos = onStreamEnd, parkFlag = parkFlag)
            val aStop = AtomicBoolean()
            val ap = try {
                buildAudioDecoder(streamSet, offsetNanos, aStop)
            } catch (e: IOException) {
                channel.teardownProcess()
                renderExecutor.execute { channel.pipe.cleanup() }
                throw e
            }
            synchronized(switchLock) { active = channel }
            val catchUp = AudioSink.CatchUp(offsetNanos) { clock.currentTime() }
            audioHalf = ap?.let {
                AudioHalf(
                    it,
                    audio.start(
                        it, terminated, aStop,
                        contentStartNanos = offsetNanos, originKnown = audioOriginKnown(),
                        startGate = firstVideoFrame, catchUp = catchUp,
                        onUnexpectedEnd = onAudioFailure,
                    ),
                    aStop,
                )
            }
            updateRawFrameSink()
            discardHalvesAsync(old, oldAudio)
            return true
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start seek session.", e)
            discardHalvesAsync(null, oldAudio)
            return false
        }
    }

    /** Replaces audio half of playing session with fresh process on same audio URL, leaving video unchanged. */
    fun restartAudio(streamSet: ActiveStreams, offsetNanos: Long): Boolean {
        if (!isPlaying || terminated.get() || parkFlag.get()) return false
        if (bridgeCeilingNanos != Long.MAX_VALUE || bridgeAudio != null) return false
        synchronized(switchLock) { if (incoming != null) return false }
        val oldAudio = audioHalf
        audioHalf = null
        oldAudio?.stop?.set(true)
        audio.stop()
        val aStop = AtomicBoolean()
        val ap = try {
            buildAudioDecoder(streamSet, offsetNanos, aStop)
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start replacement audio process.", e)
            discardHalvesAsync(null, oldAudio)
            return false
        }
        discardHalvesAsync(null, oldAudio)
        if (ap == null) {
            // The source has no audio track: there is nothing to restart, and the session is healthy
            logger.debug("$debugLabel Audio restart skipped: this source plays silently.")
            return true
        }
        val originKnown = audioOriginKnown()
        val at = audio.start(
            ap, terminated, aStop,
            contentStartNanos = offsetNanos, originKnown = originKnown,
            startGate = null, onUnexpectedEnd = onAudioFailure,
            catchUp = if (originKnown) AudioSink.CatchUp(offsetNanos) { clock.currentTime() } else null,
        )
        audioHalf = AudioHalf(ap, at, aStop)
        logger.debug("$debugLabel Audio half restarted in place at ${offsetNanos / 1_000_000} ms.")
        return true
    }

    /** Warm-up budget for a replacement audio-track process before the switch gives up and keeps the
     *  current track (better a stale language than indefinite silence on a dead URL). */
    private val audioSwitchWarmupTimeoutNanos = 15_000_000_000L

    /** Generation counter for in-flight audio-track switches: only the newest may complete its swap,
     *  so rapid re-picks and a session [stop] (which bumps it) safely orphan older warm-ups. */
    private val audioSwitchGeneration = AtomicLong()

    /** Seamless audio-track switch for seekable content: spawns new track's FFmpeg on background thread, then swaps. */
    fun beginAudioTrackSwitch(streamSet: ActiveStreams): Boolean {
        if (!isPlaying || terminated.get() || parkFlag.get()) return false
        if (bridgeCeilingNanos != Long.MAX_VALUE || bridgeAudio != null) return false
        synchronized(switchLock) { if (incoming != null) return false }
        val generation = audioSwitchGeneration.incrementAndGet()
        daemon({ runAudioTrackSwitch(streamSet, generation) }, "MediaPlayer-audio-switch").start()
        return true
    }

    /** True while [generation] is still the newest audio switch and the session can still take it. */
    private fun audioSwitchStillCurrent(generation: Long): Boolean =
        audioSwitchGeneration.get() == generation && !terminated.get() && isPlaying && !parkFlag.get()

    /** A replacement audio line ready to promote: its process, stop flag, and where its PCM begins. */
    private class PreparedAudioLine(
        val process: Process,
        val stop: AtomicBoolean,
        val contentStartNanos: Long,
    )

    /**
     * Claims the pre-warmed line for the target track, or null when none is pooled. Its PCM starts at
     * the position it was spawned at rather than at the playhead, which is exactly what the catch-up
     * skip in [AudioSink.startSwitch] exists to absorb — so this only applies where that skip runs
     * ([audioOriginKnown]); a live join anchors on its own PES PTS and must always start fresh.
     */
    private fun takeWarmAudioLine(streamSet: ActiveStreams): PreparedAudioLine? {
        if (!audioOriginKnown()) return null
        val w = audioWarmPool.take(streamSet.currentAudio.url) ?: return null
        logger.debug(
            "$debugLabel Audio-track switch served from the warm pool " +
                    "(spawned at ${w.contentStartNanos / 1_000_000} ms)."
        )
        return PreparedAudioLine(w.process, w.stop, w.contentStartNanos)
    }

    /** Spawns a replacement line at the playhead and waits for its first PCM, the un-warmed path. */
    private fun coldStartAudioLine(
        streamSet: ActiveStreams, generation: Long,
    ): PreparedAudioLine? {
        val seekNanos = clock.currentTime().coerceAtLeast(0L)
        val aStop = AtomicBoolean()
        val ap = try {
            buildAudioDecoder(streamSet, seekNanos, aStop)
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start replacement audio-track process.", e)
            return null
        } ?: return null
        // Old track keeps playing while the replacement warms up; wait for its first stdout bytes
        val deadline = System.nanoTime() + audioSwitchWarmupTimeoutNanos
        var ready = false
        while (System.nanoTime() < deadline && audioSwitchStillCurrent(generation)) {
            try {
                if (ap.inputStream.available() > 0) {
                    ready = true
                    break
                }
            } catch (_: IOException) {
                break
            }
            if (!ap.isAlive) break
            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!ready) {
            aStop.set(true)
            ap.destroy()
            if (audioSwitchGeneration.get() == generation) {
                logger.warn("$debugLabel Audio-track switch delivered no PCM in time; keeping the current track.")
            }
            return null
        }
        return PreparedAudioLine(ap, aStop, seekNanos)
    }

    /** Background body of [beginAudioTrackSwitch]: warm up the replacement, then swap lines. */
    private fun runAudioTrackSwitch(streamSet: ActiveStreams, generation: Long) {
        val line = takeWarmAudioLine(streamSet) ?: coldStartAudioLine(streamSet, generation)
        if (line == null || !audioSwitchStillCurrent(generation)) {
            line?.let {
                it.stop.set(true)
                it.process?.destroy()
            }
            onAudioTrackSwitchSettled()
            return
        }
        val ap = line.process
        val aStop = line.stop
        val seekNanos = line.contentStartNanos
        // Seamless swap: the replacement line pre-buffers (silent) while the OLD one keeps playing,
        // then flips in with no audible gap (see [AudioSink.startSwitch]) — this removes the silence
        // the old stop-then-start swap left while the new line opened and filled. A catch-up skip
        // drops the span the new track fell behind the live clock so it joins already lip-synced. The
        // HLS-feeder path (live) carries its own exact PES-PTS anchor instead, so no byte skip there.
        val originKnown = audioOriginKnown()
        val catchUp = if (originKnown) AudioSink.CatchUp(seekNanos) { clock.currentTime() } else null
        val oldAudio = audioHalf
        audio.startSwitch(
            ap, terminated, aStop,
            contentStartNanos = seekNanos, originKnown = originKnown, catchUp = catchUp,
            shouldPromote = { audioSwitchStillCurrent(generation) },
            onPromoted = {
                // Runs on the switch thread the instant the new line goes live: take ownership and
                // retire the old half. The promoted line publishes its own content origin, so the
                // master clock picks the new session up exactly, with no anchoring guess.
                audioHalf = AudioHalf(ap, Thread.currentThread(), aStop)
                oldAudio?.stop?.set(true)
                discardHalvesAsync(null, oldAudio)
                logger.debug("$debugLabel Audio track switched seamlessly at ${seekNanos / 1_000_000} ms.")
                // A stop() that raced the warm-up couldn't know this half yet: honor it retroactively
                if (terminated.get() || !isPlaying) {
                    aStop.set(true)
                    audio.stop()
                    ap.destroy()
                }
                onAudioTrackSwitchSettled()
            },
            onAborted = {
                // The replacement never went live; keep the current track and drop the new process
                aStop.set(true)
                ap.destroy()
                onAudioTrackSwitchSettled()
            },
            onUnexpectedEnd = onAudioFailure,
        )
    }

    /** Dismantles a superseded video channel and / or audio half on a background thread. */
    private fun discardHalvesAsync(video: VideoChannel?, audioHalf: AudioHalf?) {
        daemon({
            audioHalf?.let {
                it.process?.destroy()
                joinSafely(it.thread)
            }
            video?.let { ch ->
                ch.teardownProcess()
                renderExecutor.execute { ch.pipe.cleanup() }
            }
        }, "MediaPlayer-session-discard").start()
    }

    /** Starts cached replay video alone (no audio, no network) — not yet supported with JavaCPP decoder. */
    fun startReplayVideoOnly(
        snapshot: ByteArray,
        resumeNanos: Long,
        liveEdgeNanos: Long,
        audioPcm: ByteArray?
    ): Boolean {
        logger.warn("$debugLabel Replay video only not supported yet with JavaCPP decoder.")
        return false
    }

    /** Attaches live source while replay holds screen: live channel warms up as incoming channel in parallel. */
    fun attachLiveAfterReplay(
        streamSet: ActiveStreams, liveOffsetNanos: Long, lastQuality: Int,
    ): Boolean {
        if (active == null || !isPlaying || terminated.get()) return false
        liveSession = false
        val (w, h) = targetDims(streamSet, lastQuality)

        val channel = VideoChannel()
        var generation = 0L
        val previous = synchronized(switchLock) {
            generation = incomingGeneration + 1
            incomingGeneration = generation
            incoming.also { incoming = channel }
        }
        previous?.let { discardChannelAsync(it) }
        if (terminated.get()) {
            synchronized(switchLock) { if (incoming === channel && incomingGeneration == generation) incoming = null }
            discardChannelBlocking(channel)
            return false
        }

        return try {
            val firstLiveFrame = CountDownLatch(1)
            channel.launch(
                streamSet,
                w,
                h,
                liveOffsetNanos,
                onFirstFrame = {
                    clock.rebaseTo(liveOffsetNanos)
                    audio.onBridgeHandoff()
                    bridgeCeilingNanos = Long.MAX_VALUE
                    firstLiveFrame.countDown()
                    logger.debug(
                        "$debugLabel [reappear] live channel presented first frame; handoff at ${
                            "%.1f".format(
                                liveOffsetNanos / 1_000_000.0
                            )
                        } ms."
                    )
                },
                onEos = { stderr, normalEos ->
                    abortIncoming(
                        generation,
                        "eos=$normalEos stderr=${MediaUtil.truncate(stderr)}."
                    )
                },
                parkFlag = parkFlag
            )

            val audioUrl = streamSet.currentAudio.url
            val ap =
                if (audioUrl in SILENT_SOURCES) null
                else {
                    val decoder = JavaCppAudioDecoder(debugLabel)
                    val inputStream = decoder.start(
                        url = streamSet.currentAudio.url,
                        seekOffsetNanos = liveOffsetNanos,
                        stopFlag = AtomicBoolean(),
                        terminated = terminated,
                        parkFlag = parkFlag,
                    )
                    if (inputStream == null) {
                        logger.error("$debugLabel [reappear] failed to start audio decoder for $audioUrl")
                        null
                    } else {
                        val proc = JavaCppAudioProcess(decoder, inputStream as java.io.PipedInputStream)
                        synchronized(audioDecoders) { audioDecoders.add(decoder) }
                        proc
                    }
                }
            silentSession = ap == null
            val bridge = bridgeAudio
            if (ap == null) {
                bridge?.stop?.set(true)
                bridgeAudio = null
                audioHalf = null
            } else if (bridge != null) {
                audio.provideLiveInput(ap, bridge.stop)
                audioHalf = AudioHalf(ap, bridge.thread, bridge.stop)
                bridgeAudio = null
            } else {
                val aStop = AtomicBoolean()
                val at = audio.start(
                    ap, terminated, aStop,
                    contentStartNanos = liveOffsetNanos, originKnown = true,
                    startGate = firstLiveFrame, onUnexpectedEnd = onAudioFailure,
                )
                audioHalf = AudioHalf(ap, at, aStop)
            }

            if (terminated.get()) {
                synchronized(switchLock) {
                    if (incoming === channel && incomingGeneration == generation) incoming = null
                }
                discardChannelBlocking(channel)
                return false
            }
            logger.debug("$debugLabel [reappear] live attached $w x $h at ${"%.1f".format(liveOffsetNanos / 1_000_000.0)} ms, warming up...")
            true
        } catch (e: IOException) {
            logger.error("$debugLabel [reappear] failed to attach live after replay.", e)
            val wasCurrent = synchronized(switchLock) {
                if (incoming === channel && incomingGeneration == generation) {
                    incoming = null; true
                } else false
            }
            discardChannelBlocking(channel)
            if (wasCurrent) onQualitySwitchAborted(false)
            false
        }
    }

    /** Captures live channel's entire encoded-packet cache (rolling window) for later replay. */
    fun captureVideoCacheSnapshot(): ByteArray? = null // JavaCPP decoder does not support packet-ring snapshots; will be restored in future

    /** The live edge a replay -> live bridge is currently resuming toward, or null when no bridge is in flight. */
    fun activeBridgeEdgeNanos(): Long? = bridgeCeilingNanos.takeIf { it != Long.MAX_VALUE }

    @Volatile
    private var parkStartNanos = 0L

    @Volatile
    private var frozenPositionNanos = -1L

    /**
     * Whether this session can be parked warm for out-of-render-distance dormancy.
     * JavaCPP-based playback supports warm park by idling the grabber thread.
     */
    fun canPark(): Boolean = canHoldWarm()

    /** Whether the session can hold its position warm at all: something is playing, and no replay bridge or quality switch is currently in flight. */
    private fun canHoldWarm(): Boolean =
        isPlaying && !terminated.get() && active != null &&
                bridgeCeilingNanos == Long.MAX_VALUE && incoming == null &&
                (audioHalf != null || silentSession)

    fun suspend(allowExternalProcess: Boolean = false, retainBuffered: Boolean = false): Boolean {
        if (!(if (allowExternalProcess) canHoldWarm() else canHoldWarm()) || parkFlag.get()) return false
        parkFlag.set(true)
        // Nothing may switch tracks while dormant, so holding idle FFmpeg processes would be pure cost.
        audioWarmPool.invalidateAll()
        audio.pauseForPark()
        if (!retainBuffered) active?.pipe?.trimForPark()
        frozenPositionNanos = pacingClockNanos().takeIf { it >= 0L } ?: clock.currentTime()
        clock.moveTo(frozenPositionNanos)
        parkStartNanos = System.nanoTime()
        logger.debug("$debugLabel [park] session parked warm at ${"%.1f".format(frozenPositionNanos / 1_000_000.0)}ms.")
        return true
    }

    /** Un-parks suspended session: readers resume from frozen position; wall clock shifted past dormant interval. */
    fun resume() {
        if (!parkFlag.get()) return
        clock.addPausedDuration(System.nanoTime() - parkStartNanos)
        frozenPositionNanos = -1L
        audio.resumeFromPark()
        parkFlag.set(false)
        logger.debug("$debugLabel [park] session un-parked; resuming from frozen position.")
    }

    /** True while the session is parked warm. */
    fun isParked(): Boolean = parkFlag.get()

    /** The frozen playback position while parked (so an evicted park saves where the viewer left, not a
     *  position drifted forward by the dormant wall-clock time), or null when not parked. */
    fun parkedPositionNanos(): Long? = frozenPositionNanos.takeIf { parkFlag.get() && it >= 0 }

    /** Captures up to [maxNanos] of recently played PCM for the reappearance audio bridge, or null. */
    fun captureAudioPcm(maxNanos: Long): ByteArray? {
        val maxBytes = (maxNanos / 1_000_000_000.0 * AudioSink.SAMPLE_RATE * AudioSink.BYTES_PER_FRAME).toInt()
        return audio.snapshotPcm(maxBytes).takeIf { it.isNotEmpty() }
    }

    /**
     * The single clock every video pipe paces against. Owns session anchoring, the wall-time takeover
     * for a dead line, and the consistency of both across the several reader threads that sample it.
     */
    private val masterClock = AudioMasterClock(debugLabel, requestAudioResync = audio::requestResync)

    /** Master-clock position in nanos, or -1 when neither audio line nor wall clock is up yet. Audio drives pacing. */
    private fun pacingClockNanos(): Long {
        // While a replay -> live bridge is active the wall clock is clamped to the live edge so it never
        // overruns the handoff point (otherwise the live channel's first frame arrives "late" and is
        // dropped instead of presented, and the audio gate never opens).
        val wall = if (clock.isRunning) clock.currentTime().coerceAtMost(bridgeCeilingNanos) else -1L
        return masterClock.nanos(audio.sampleClock(), wall, parkFlag.get(), ::exactAvBiasNanos)
    }

    /** The position playback is actually at, for callers that need to freeze or save it. */
    fun currentPacingNanos(): Long = pacingClockNanos()

    /** Exact audio-vs-video offset from shared PTS: video first raw PTS, no HLS feeder with JavaCPP. */
    private fun exactAvBiasNanos(): Long? {
        val r0 = active?.javaCppPipe?.firstRawPtsNanos ?: return null
        if (r0 == Long.MIN_VALUE) return null
        return -r0
    }

    /** Resolves the decode dimensions: the current/target texture size when known, else from quality. */
    private fun targetDims(streamSet: ActiveStreams?, lastQuality: Int = 0): Pair<Int, Int> {
        val (tw, th) = getTextureSize()
        if (tw > 0 && th > 0) return tw to th
        val q = when {
            lastQuality > 0 -> lastQuality
            streamSet != null -> MediaStreamSelector.parseQuality(streamSet.currentVideo)
            else -> 0
        }
        if (q <= 0) return 854 to 480
        return MediaStreamSelector.qualityToDims(q).let { it[0] to it[1] }
    }

    /**
     * Seamless quality switch: launches [streamSet]'s new-quality video as a parallel incoming channel while the
     * current one keeps playing, then swaps once it's caught up.
     */
    fun beginQualitySwitch(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int) {
        if (active == null || !isPlaying || terminated.get()) {
            // Nothing to hand off from: drop the staged texture, but the target quality still takes
            // effect below via a full start on the same (new) stream set — not a real failure.
            onQualitySwitchAborted(true)
            start(streamSet, offsetNanos, lastQuality)
            return
        }

        // Supersede any in-flight switch (rapid quality changes)
        val channel = VideoChannel()
        var generation = 0L
        val previous = synchronized(switchLock) {
            generation = incomingGeneration + 1
            incomingGeneration = generation
            incoming.also { incoming = channel }
        }
        previous?.let {
            if (MediaPlayer.DEBUG) logger.debug("$debugLabel Superseding incoming video handoff #${generation - 1}.")
            discardChannelAsync(it)
        }
        if (terminated.get()) {
            synchronized(switchLock) {
                if (incoming === channel && incomingGeneration == generation) incoming = null
            }
            discardChannelBlocking(channel)
            return
        }

        val (w, h) = targetDims(streamSet, lastQuality)
        try {
            // No latch / audio gate: the clock is already running. EOS aborts only this handoff
            if (MediaPlayer.DEBUG) {
                logger.debug(
                    "$debugLabel Starting incoming video handoff #$generation $w x $h " +
                            "at ${"%.1f".format(offsetNanos / 1_000_000.0)} ms.",
                )
            }
            channel.launch(
                streamSet,
                w,
                h,
                offsetNanos,
                onFirstFrame = {
                    clock.markFirstFrame()
                    if (MediaPlayer.DEBUG) logger.debug("$debugLabel Incoming video handoff #$generation presented its first frame.")
                },
                onEos = { stderr, normalEos ->
                    abortIncoming(
                        generation,
                        "eos=$normalEos stderr=${MediaUtil.truncate(stderr)}."
                    )
                },
                parkFlag = parkFlag,
                // No pre-prime preview: the incoming channel's first decoded frame is stale by the
                // session-open time, and presenting it would promote a rewound picture that then holds
                // until decode catches the clock. Promote on the first *paced* frame instead.
                presentPreview = false,
                // Same reason a late frame must not go out here: promotion happens on the first frame
                // this channel presents, so it has to be one that is actually on the clock.
                tolerateLateness = false,
            )
            val shouldDiscard = synchronized(switchLock) {
                !(!terminated.get() && active != null && incoming === channel && incomingGeneration == generation) && if (incoming === channel && incomingGeneration == generation) {
                    incoming = null
                    true
                } else {
                    false
                }
            }
            if (shouldDiscard) discardChannelBlocking(channel)
        } catch (e: IOException) {
            logger.error("$debugLabel Failed to start incoming video for quality switch.", e)
            val wasCurrent = synchronized(switchLock) {
                if (incoming === channel && incomingGeneration == generation) {
                    incoming = null
                    true
                } else {
                    false
                }
            }
            discardChannelBlocking(channel)
            if (wasCurrent) onQualitySwitchAborted(false)
        }
    }

    /**
     * Promotes the incoming quality-switch channel to live: the new channel becomes the rendered one
     * and the old channel is torn down off-thread. Called from the render thread the moment the
     * incoming channel's first frame has been uploaded to the staged texture, so the swap is seamless.
     */
    fun promoteIncoming(): Boolean {
        val old: VideoChannel?
        val generation: Long
        synchronized(switchLock) {
            val inc = incoming ?: return false
            generation = incomingGeneration
            incoming = null
            old = active
            active = inc
        }
        if (MediaPlayer.DEBUG) logger.debug("$debugLabel Promoted incoming video handoff #$generation.")
        updateRawFrameSink() // Re-attach popout / preview to the new live channel
        old?.let { discardChannelAsync(it) }
        return true
    }

    /** Aborts an in-flight quality switch (incoming EOS / failure): drops the incoming channel, keeps the live one. */
    private fun abortIncoming(generation: Long, reason: String) {
        val inc = synchronized(switchLock) {
            if (incomingGeneration != generation) null else incoming.also { incoming = null }
        } ?: return
        if (MediaPlayer.DEBUG) logger.debug("$debugLabel Aborted incoming video handoff #$generation ($reason).")
        discardChannelAsync(inc)
        onQualitySwitchAborted(false)
    }

    /** Tears down [channel] (process join) on a background thread, then releases its GL resources on the render thread. */
    private fun discardChannelAsync(channel: VideoChannel) {
        daemon({
            channel.teardownProcess()
            renderExecutor.execute { channel.pipe.cleanup() }
        }, "MediaPlayer-video-discard").start()
    }

    /** Tears down [channel] inline (caller must not be the render thread), then frees its GL resources. */
    private fun discardChannelBlocking(channel: VideoChannel) {
        channel.teardownProcess()
        renderExecutor.execute { channel.pipe.cleanup() }
    }

    /**
     * Signals all stop flags, destroys the `FFmpeg` processes, closes the audio line, and joins the
     * reader threads. Tears down any in-flight quality switch too. Safe to call when idle.
     */
    fun stop() {
        isPlaying = false
        audioWarmPool.invalidateAll()
        bridgeCeilingNanos = Long.MAX_VALUE
        masterClock.reset()
        parkFlag.set(false) // Release any parked readers so they observe the stop flags and exit
        // A reappearance bridge whose live process never attached: flag it; audio.stop() below releases the
        // line and the pending live-input gate, and the thread is joined at the end.
        bridgeAudio?.stop?.set(true)
        val inc = synchronized(switchLock) {
            incomingGeneration += 1
            incoming.also { incoming = null }
        }
        inc?.let { discardChannelBlocking(it) }

        val a = active
        active = null
        audioHalf?.stop?.set(true)
        a?.let { it.stop.set(true); it.javaCppPipe.kill() }
        audioHalf?.let { it.process?.destroy() }
        audio.stop()
        a?.let {
            it.thread?.let { t -> joinSafely(t) }
            it.javaCppPipe.release()
            renderExecutor.execute { it.pipe.cleanup() }
        }
        // Release all JavaCPP audio decoders
        synchronized(audioDecoders) {
            audioDecoders.forEach { it.release() }
            audioDecoders.clear()
        }
        audioHalf?.thread?.let { joinSafely(it) }
        audioHalf = null
        // Join a still-pending bridge thread (live never attached, so it never moved into audioHalf)
        bridgeAudio?.thread?.let { joinSafely(it) }
        bridgeAudio = null
    }

    /**
     * Releases any remaining pipe GL resources. Called once when this session manager is permanently
     * discarded (the owning `MediaPlayer` is stopping for good). [stop] normally clears channels first.
     */
    fun cleanup() {
        audioWarmPool.close()
        synchronized(switchLock) { incoming.also { incoming = null } }?.let { discardChannelBlocking(it) }
        active?.let { ch ->
            active = null
            ch.javaCppPipe.release()
            renderExecutor.execute { ch.pipe.cleanup() }
        }
        // Release all JavaCPP audio decoders
        synchronized(audioDecoders) {
            audioDecoders.forEach { it.release() }
            audioDecoders.clear()
        }
    }
}
