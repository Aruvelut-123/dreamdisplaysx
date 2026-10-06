package com.dreamdisplayx.media.player.managers

import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.model.StretchMode
import com.dreamdisplayx.api.media.player.FrameUploaderFactory
import com.dreamdisplayx.api.media.player.GpuTextureRef
import com.dreamdisplayx.api.media.player.RenderExecutor
import com.dreamdisplayx.api.media.audio.service.AudioDspStage
import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.media.player.events.PlayerEvents
import com.dreamdisplayx.media.player.pipeline.FrameSurface
import com.dreamdisplayx.media.player.pipeline.PlaybackClock
import com.dreamdisplayx.media.player.stream.ActiveStreams
import com.dreamdisplayx.media.player.stream.MediaStreamSelector
import com.dreamdisplayx.media.player.util.LibVlcMediaOptions
import com.dreamdisplayx.media.runtime.security.MediaHostGuard
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * LibVLC session manager rebuilt to mirror the VideoPlayer mod's low-level libvlc model:
 *
 *  - Desktop keeps one libvlc video player for the whole session and replaces its media
 *    synchronously (`set_media` + `play`), so no JNA callback trampoline is dropped while
 *    libvlc's async teardown could still touch it. Android retires media-bearing players and
 *    creates fresh ones because the VLC-Android TLS teardown path is unsafe to reuse.
 *  - Video is delivered through low-level lock/unlock/display/setup/cleanup callbacks into
 *    a triple-buffered pool (the VideoPlayer `TextureRenderCallback` model). Every callback
 *    is held by a strong field reference for the life of the manager.
 *  - All libvlc control operations run on a single control executor, serialised.
 *  - Playback events (playing/end-reached/error) are delivered through a low-level event
 *    listener, exactly like VideoPlayer.
 *  - Desktop audio is decoded through the Java Sound callback so display acoustics remain available;
 *    Android uses libvlc's OpenSL ES output because `java.desktop` is unavailable there. Separate
 *    audio renditions are replaced on the dedicated audio player without rebuilding desktop video.
 */
internal class LibVlcSessionManager(
    private val debugLabel: String,
    private val clock: PlaybackClock,
    private val events: PlayerEvents,
    private val terminated: AtomicBoolean,

    /** Returns the current GPU texture dimensions (width to height). */
    private val getTextureSize: () -> Pair<Int, Int>,
    private val getStretchMode: () -> StretchMode = { StretchMode.LETTERBOX },

    /** Invoked when the stream ends or errors. */
    private val onStreamEnd: (stderr: String, normalEos: Boolean) -> Unit,

    /** Invoked when quality switch fails before promotion. */
    private val onQualitySwitchAborted: (appliedAnyway: Boolean) -> Unit = {},

    /** Runs render-thread (GL) cleanup work. */
    private val renderExecutor: RenderExecutor,

    /** Creates per-channel GPU frame uploaders. */
    private val uploaderFactory: FrameUploaderFactory,

    /** Whether the GPU-side planar (I420) render path is active. */
    private val gpuYuvActive: Boolean,

    /** Whether hardware-accelerated decoding is enabled by config. */
    private val useHwAccel: Boolean,

    /** Per-display acoustics DSP stage for the desktop Java Sound callback path. */
    audioStage: AudioDspStage? = null,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/LibVlcSession")

    /**
     * Android mode: javax.sound does not exist on Android (no java.desktop), so the
     * Java Sound callback pipeline cannot run there — [LibVlcAudioOutput] is not even
     * instantiated because its method signatures reference `SourceDataLine` and would
     * fail to link. libvlc feeds OpenSL ES directly; muxed media keeps audio on the video
     * player while separate renditions use a dedicated audio player.
     */
    private val systemAudio: Boolean = com.dreamdisplayx.util.OsInfo.isAndroid

    /**
     * Owns the Java Sound audio line + 3D DSP feeding for this display. libvlc is configured with
     * audio callbacks so decoded PCM reaches us instead of libvlc's default output; this restores
     * directional 3D audio (panning), occlusion and our own audio pacing. Null on Android
     * ([systemAudio]): libvlc's own OpenSL ES output plays the sound there.
     */
    private val audioOutput: LibVlcAudioOutput? =
        if (systemAudio) null else LibVlcAudioOutput(debugLabel, audioStage)

    /** Rate-limits the A/V sync diagnostic (audio line clock vs libvlc clock) to once per few seconds. */
    private val lastSyncDiagNanos = java.util.concurrent.atomic.AtomicLong(0L)

    // ── Video FPS debug counter (renders the actual delivered frame rate, for the debug label) ──
    @Volatile
    private var fpsFrames = 0L
    @Volatile
    private var fpsWindowStartNanos = 0L
    @Volatile
    private var fpsValue = 0.0

    /** Delivered-video FPS (frames displayed per second), smoothed over a 1s sliding window. */
    val currentVideoFps: Double get() = fpsValue

    // ── Frame-interval diagnostics (min/avg/max gap between vout displays, in ms) ──
    // Reveals the vout's actual pacing pattern: if min ≈ source-frame interval (e.g. 33ms for 30fps)
    // but avg is ~2x that, libvlc is DROPPING half the frames (clock throttling); if min ≈ avg ≈ the
    // slow value, the decoder itself is only producing frames at that rate.
    @Volatile private var lastDisplayNanos = 0L
    @Volatile private var gapMinMs = 0L
    @Volatile private var gapMaxMs = 0L
    @Volatile private var gapSumMs = 0L
    @Volatile private var gapCount = 0L

    /** "min/avg/max ms" frame-interval summary of the last ~N vout displays; null when no frames yet. */
    fun frameIntervalInfo(): String? {
        if (gapCount <= 0) return null
        val avg = gapSumMs.toDouble() / gapCount
        return "${gapMinMs}/${"%.0f".format(avg)}/${gapMaxMs}ms"
    }

    private fun recordFrameGap() {
        val now = System.nanoTime()
        val prev = lastDisplayNanos
        lastDisplayNanos = now
        if (prev == 0L) return
        val gap = (now - prev) / 1_000_000L
        if (gap <= 0L) return
        if (gapMinMs == 0L || gap < gapMinMs) gapMinMs = gap
        if (gap > gapMaxMs) gapMaxMs = gap
        gapSumMs += gap
        gapCount++
    }

    /**
     * A/V drift threshold (~0.3 s). This is NOT the lip-sync gap (that is the audio buffer, ~0.045 s,
     * and is set in [LibVlcAudioOutput]); it is the point at which a real, sustained drift is declared
     * and the queued audio is flushed to snap the sound back to the video. The threshold must stay well
     * above the normal buffer lead so the auto-resync does not fire on every block of jitter — a 45 ms
     * trial threshold fired almost every diagnostic (the healthy lead already sits at the buffer), and
     * that constant cross-thread `line.flush()` from the render thread, racing the libvlc audio thread's
     * write/stop/resume on the same SourceDataLine, is what crashed the JVM with an access violation on
     * pause/resume. 0.3 s is a safe middle: high enough that healthy jitter never trips it, low enough
     * that a real stall still recovers within a few seconds.
     */
    private val AUTO_RESYNC_THRESHOLD_NANOS = 300_000_000L

    /**
     * Grace window after an in-place seek during which the A/V snap logic must not drag the audio
     * player back to the video player's stale clock. A far seek flushes the video demuxer for
     * seconds; during that window `get_time` still reports the OLD position while the audio player
     * (audio-only, no vout) has already landed on the target and is playing there. Snapping audio
     * to the video clock then rewinds the whole playback to the pre-seek position — observed as
     * "audio plays at the target for a few seconds, then A/V continue from where it used to be".
     */
    private val SEEK_SETTLE_WINDOW_NANOS = 5_000_000_000L

    /** First landing check delay; fast seeks can settle before verification starts. */
    private val SEEK_VERIFY_DELAY_NANOS = 1_500_000_000L

    /** Maximum distance from the target that still counts as landed. */
    private val SEEK_VERIFY_TOLERANCE_MS = 1_200L

    /** Gap between clock samples used to distinguish a dropped seek from an active flush. */
    private val SEEK_VERIFY_SAMPLE_GAP_NANOS = 500_000_000L

    /** Minimum clock advance across the sample gap that proves old content is still playing. */
    private val SEEK_VERIFY_DROP_ADVANCE_MS = 300L

    /** Bounded verification passes per seek. */
    private val SEEK_VERIFY_MAX_CHECKS = 12

    /** Delay after re-applying set_time before checking its result again. */
    private val SEEK_VERIFY_RECHECK_NANOS = 1_000_000_000L

    /** Fail-open bound for the picture freeze, so a broken seek cannot freeze the display forever. */
    private val SEEK_FREEZE_MAX_NANOS = 10_000_000_000L

    /** Wall deadline (System.nanoTime) until which A/V snapping is suppressed after a seek. */
    @Volatile
    private var seekSettleUntilNanos = 0L

    /**
     * Monotonic id of the newest seek. A verification task started for an older id is stale and
     * must not re-apply set_time (a newer seek superseded it). Read at task start, re-checked at
     * every pass.
     */
    private val seekGeneration = AtomicLong(0)

    /**
     * Picture freeze while a seek settles: while armed and the video clock is still far from the
     * target, [TextureRenderCallback.publishFrame] drops stale pre-seek frames so the user does
     * not watch old content "like another player" while the audio player is already at the target.
     * The last displayed frame simply stays on screen until the decoder resumes past the new
     * position (the intended hard-switch UX).
     */
    @Volatile
    private var seekFrozen = false

    @Volatile
    private var seekFrozenTargetMs = -1L

    @Volatile
    private var seekFrozenDeadlineNanos = 0L

    /** True while inside the post-seek settle window during which audio must not be snapped to video. */
    private fun seekSettling(): Boolean = System.nanoTime() < seekSettleUntilNanos

    /** Arms the settle window and arms it for the seek-verification pass below. */
    private fun armSeekSettleWindow() {
        seekSettleUntilNanos = System.nanoTime() + SEEK_SETTLE_WINDOW_NANOS
    }

    init {
        // Mirror the config's hw-accel preference onto the shared libvlc instance before it is
        // created (the singleton instance is built on first use, so this must be set up front).
        LibVlc.useHwAccel = useHwAccel
    }

    /** Media-player events we attach to (VideoPlayer's set). */
    private val MEDIA_PLAYER_EVENTS = intArrayOf(
        LibVlc.LIBVLC_MEDIA_PLAYER_PLAYING,
        LibVlc.LIBVLC_MEDIA_PLAYER_PAUSED,
        LibVlc.LIBVLC_MEDIA_PLAYER_STOPPED,
        LibVlc.LIBVLC_MEDIA_PLAYER_END_REACHED,
        LibVlc.LIBVLC_MEDIA_PLAYER_ENCOUNTERED_ERROR,
        LibVlc.LIBVLC_MEDIA_PLAYER_TIME_CHANGED,
        LibVlc.LIBVLC_MEDIA_PLAYER_LENGTH_CHANGED,
    )

    // ── libvlc singleton state (VideoPlayer model) ──────────────────────────

    @Volatile
    private var mediaPlayer: Pointer? = null

    /**
     * Second libvlc media player dedicated to audio-only playback. Decoupled from the video player
     * so its audio-clock (Java Sound `write()` blocking) never throttles the video vout. Created
     * alongside [mediaPlayer] for desktop; Android rotates to a fresh player for each media session. Audio callbacks are registered on
     * this player only; the video player has `:no-audio` and runs on the system clock.
     */
    @Volatile
    private var audioPlayer: Pointer? = null

    /** Last volume handed to [setVolume], re-asserted after an attach (libvlc volume is per-player). */
    @Volatile
    private var lastVolume = 1.0

    /** Retired Android players stay strongly referenced and paused until process exit. */
    private val retiredAndroidPlayers = java.util.Collections.synchronizedList(mutableListOf<Pointer>())

    private val released = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val lifecycleGeneration = AtomicLong(0L)

    /** Serialises every libvlc control call, mirroring VideoPlayer's control executor. */
    private val controlExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> daemonThread(r, "MediaPlayer-vlc-ctrl") }

    // ── Frame surface ───────────────────────────────────────────────────────

    private val surface = FrameSurface(debugLabel, uploaderFactory, FramePixelFormat.BGRA32).also { s ->
        // Display-time frame selection: the render thread takes the queued frame that is DUE for the current
        // playback position (libvlc `get_time`) instead of always taking the newest published one, so a frame
        // delivered early waits for its slot instead of replacing the picture ahead of time.
        // libvlc's plane callbacks expose no per-frame PTS, so publishFrame() stamps each frame from the same
        // media clock the render thread reads (see FrameSurface.enableDisplaySync).
        s.enableDisplaySync({ currentPacingNanos() }, DISPLAY_SYNC_FRAME_NS)
    }

    @Volatile
    var expectedW = 0; private set

    @Volatile
    var expectedH = 0; private set

    private val noFrames = AtomicLong(0)

    var isPlaying = false; private set

    val lastFrameNanos: AtomicLong get() = noFrames

    // ── EOS / error signals ─────────────────────────────────────────────────

    @Volatile
    private var eosReached = false

    @Volatile
    private var errorMessage = ""

    /** Guards [onStreamEnd] so it fires exactly once per session. */
    private val eosFired = AtomicBoolean(false)

    // ── Park state ──────────────────────────────────────────────────────────

    private val parkFlag = AtomicBoolean(false)
    private var parkPositionNanos = 0L

    // ── Popout / preview sinks ──────────────────────────────────────────────

    private var popoutSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null
    private var previewSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    /** Latest sampled RGB color for optional world-light integrations. */
    @Volatile
    var ambientLightColor: Int = 0
        private set

    var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = popoutSink
        set(value) { popoutSink = value }

    var previewFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = previewSink
        set(value) { previewSink = value }

    /** Samples a decoded frame for optional client-side lighting integrations. */
    var ambientLightSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null

    // ── Low-level callbacks (held strongly for life) ───────────────────────

    /** Event callbacks are retained because Android keeps retired players alive until process exit. */
    private val eventCallbacks = java.util.Collections.synchronizedList(mutableListOf<LibVlc.EventCallback>())

    /**
     * Every native player gets its own callback object and direct-buffer pool. This is important on
     * Android: an old player's delayed vout callback must never use the replacement player's frame
     * pointers while libvlc is copying a picture.
     */
    private val videoCallbackSets = java.util.Collections.synchronizedList(mutableListOf<VideoCallbackSet>())
    private val videoCallbacksByPlayer = java.util.Collections.synchronizedMap(mutableMapOf<Long, VideoCallbackSet>())

    private inner class VideoCallbackSet {
        val frames = TextureRenderCallback()
        val format = LibVlc.VideoFormatCallback { opaque, chroma, width, height, pitches, lines ->
            frames.setup(opaque, chroma, width, height, pitches, lines)
        }
        val cleanup = LibVlc.VideoCleanupCallback { opaque -> frames.cleanup(opaque) }
        val lock = LibVlc.VideoLockCallback { opaque, planes -> frames.lock(opaque, planes) }
        val unlock = LibVlc.VideoUnlockCallback { opaque, picture, planes ->
            frames.unlock(opaque, picture, planes)
        }
        val display = LibVlc.VideoDisplayCallback { opaque, picture -> frames.display(opaque, picture) }

        init {
            // Keep JNA callback threads attached until VLC's native owner exits. The Android
            // loader also creates VLC's JNI TLS key before JNA, so this is a defensive second line.
            LibVlc.configureCallbackThread(format)
            LibVlc.configureCallbackThread(cleanup)
            LibVlc.configureCallbackThread(lock)
            LibVlc.configureCallbackThread(unlock)
            LibVlc.configureCallbackThread(display)
        }

        fun deactivate() = frames.deactivate()
    }

    private fun deactivateVideoCallbacks(player: Pointer?) {
        if (player == null) return
        videoCallbacksByPlayer[Pointer.nativeValue(player)]?.deactivate()
    }

    // ── Audio callbacks (held strongly; feed the 3D DSP + Java Sound line) ───

    private val audioFormatSetupCallback = LibVlc.AudioSetupCallback { data, format, rate, channels ->
        audioOutput?.onFormatSetup(data, format, rate, channels) ?: 0
    }

    private val audioFormatCleanupCallback = LibVlc.AudioCleanupCallback { data ->
        audioOutput?.onFormatCleanup(data)
    }

    private val audioPlayCallback = LibVlc.AudioPlayCallback { data, samples, count, pts ->
        audioOutput?.onPlay(data, samples, count, pts)
    }

    private val audioPauseCallback = LibVlc.AudioPauseCallback { data, pts ->
        audioOutput?.onPause(data, pts)
    }

    private val audioResumeCallback = LibVlc.AudioResumeCallback { data, pts ->
        audioOutput?.onResume(data, pts)
    }

    private val audioFlushCallback = LibVlc.AudioFlushCallback { data, pts ->
        audioOutput?.onFlush(data, pts)
    }

    private val audioDrainCallback = LibVlc.AudioDrainCallback { data ->
        audioOutput?.onDrain(data)
    }


    // ── Event handling ──────────────────────────────────────────────────────

    private fun handleEvent(event: Pointer?, sourcePlayer: Pointer) {
        // Android retires old players instead of stopping/releasing them. Their delayed events must
        // never mutate the state of the replacement player (especially END_REACHED / ERROR).
        if (event == null || released.get() || stopped.get() || mediaPlayer !== sourcePlayer) return
        val type = event.getInt(0)
        when (type) {
            LibVlc.LIBVLC_MEDIA_PLAYER_PLAYING -> {
                stopped.set(false)
                isPlaying = true
                // Audio diagnostics: log track count and current volume.
                val player = mediaPlayer
                if (player != null) {
                    val tracks = LibVlc.lib.libvlc_audio_get_track_count(player)
                    val vol = LibVlc.lib.libvlc_audio_get_volume(player)
                    logger.debug("$debugLabel libvlc playing: audioTracks={} volume={}.", tracks, vol)
                    // Update F3 decoder info — query immediately, and retry if the decoder isn't
                    // initialised yet (the info may not be available at the very first PLAYING event).
                    updateDecoderName(player, immediate = true)
                }
            }
            LibVlc.LIBVLC_MEDIA_PLAYER_PAUSED -> {
                if (MediaPlayer.DEBUG) logger.debug("$debugLabel libvlc paused.")
            }
            LibVlc.LIBVLC_MEDIA_PLAYER_END_REACHED -> {
                // DIAGNOSTIC: audio-vs-video length comparison. When a DASH audio slave is shorter
                // than the video master (common on Bilibili), libvlc stops calling onPlay once the
                // audio fragments run out and the audible audio ends early while video continues.
                val audioMs = runCatching { audioOutput?.audioFeedMs() }.getOrDefault(-1L) ?: -1L
                val videoMs = runCatching {
                    val p = mediaPlayer
                    if (p != null) LibVlc.lib.libvlc_media_player_get_length(p) else -1L
                }.getOrDefault(-1L)
                if (audioMs >= 0 && videoMs > 0) {
                    logger.debug(
                        "$debugLabel A/V END: audio fed {} ms vs video length {} ms ({} ms gap).",
                        audioMs, videoMs, videoMs - audioMs
                    )
                }
                logger.debug("$debugLabel libvlc end reached.")
                eosReached = true
                fireStreamEnd()
            }
            LibVlc.LIBVLC_MEDIA_PLAYER_TIME_CHANGED -> {
                // Time-changed log is useful for debugging but we never rebase the clock:
                // currentPacingNanos() reads libvlc_media_player_get_time() directly, and
                // rebasing would corrupt clock.originNanos used by doRestart's offset.
                if (MediaPlayer.DEBUG) {
                    val player = mediaPlayer
                    if (player != null) {
                        val us = LibVlc.lib.libvlc_media_player_get_time(player)
                        if (us >= 0) logger.debug("$debugLabel TIME_CHANGED {} ms.", us / 1000)
                    }
                }
            }
            LibVlc.LIBVLC_MEDIA_PLAYER_ENCOUNTERED_ERROR -> {
                // errmsg() is thread-local and almost always empty here (the failure happened on a
                // libvlc worker thread, not the event thread), so enrich the message with the real
                // reason from the libvlc log sink plus the player state at error time.
                val errmsg = LibVlc.errmsg()
                val stateName = runCatching {
                    val p = mediaPlayer
                    if (p != null) {
                        when (LibVlc.lib.libvlc_media_player_get_state(p)) {
                            LibVlc.LIBVLC_STATE_PLAYING -> "Playing"
                            LibVlc.LIBVLC_STATE_PAUSED -> "Paused"
                            LibVlc.LIBVLC_STATE_STOPPED -> "Stopped"
                            LibVlc.LIBVLC_STATE_ENDED -> "Ended"
                            else -> "Unknown"
                        }
                    } else "no-player"
                }.getOrDefault("Unknown")
                val recentLog = LibVlc.recentLogLines(12)
                // HW-decode backend failure (e.g. d3d11va surface-allocation crash on NVIDIA):
                // advance to the next candidate in the per-vendor chain (cuda → d3d11va →
                // software, vaapi → software, …) and let the MediaPlayer restart the SAME stream
                // with the new media-level :avcodec-hw. "none" means explicit software decode —
                // the chain's last resort, never a silent re-pick of the failed backend.
                val hwFallback = LibVlc.advanceHwBackendAfterHwFailure(recentLog)
                val detail = buildString {
                    append("libvlc error")
                    if (errmsg.isNotBlank()) append(": ").append(errmsg)
                    append(" [state=").append(stateName).append("]")
                    if (hwFallback != null) {
                        append(" [hw-decode fallback -> ").append(hwFallback).append("]")
                    }
                    if (recentLog.isNotEmpty()) {
                        append(" | libvlc log: ").append(recentLog.joinToString("; "))
                    }
                }
                logger.error("$debugLabel $detail")
                errorMessage = detail
                eosReached = true
                fireStreamEnd()
            }
            else -> {}
        }
    }

    /** Fires [onStreamEnd] exactly once per session. */
    private fun fireStreamEnd() {
        if (eosFired.compareAndSet(false, true) && !terminated.get()) {
            isPlaying = false
            onStreamEnd(errorMessage.ifEmpty { "End of stream" }, errorMessage.isEmpty())
        }
    }

    // ── Volume ──────────────────────────────────────────────────────────────

    /**
     * Applies the effective volume as a PCM gain on our own audio line. With audio callbacks set,
     * libvlc's software volume (libvlc_audio_set_volume) would be a no-op — the decoded samples are
     * delivered to us raw, so volume is applied here (and by the 3D DSP chain).
     */
    fun setVolume(volume: Double) {
        audioOutput?.setVolume(volume)
        // libvlc's own output needs the volume as well on Android (system audio), where the PCM never
        // reaches [LibVlcAudioOutput] and libvlc volume is per-player.
        val pct = (volume.coerceIn(0.0, 1.0) * 100).toInt()
        lastVolume = volume
        if (systemAudio) {
            val mp = mediaPlayer
            if (mp != null) {
                runCatching { LibVlc.lib.libvlc_audio_set_volume(mp, pct) }
            }
            val ap = audioPlayer
            if (ap != null) {
                runCatching { LibVlc.lib.libvlc_audio_set_volume(ap, pct) }
            }
        }
    }

    /** Opens (or reuses) the shared Java Sound audio line for this display. */
    private fun openAudioLine() {
        runCatching { audioOutput?.openLine() }
    }

    /**
     * Refreshes [MediaPlayer.currentDecoder] (F3 overlay) from libvlc's decoder info. The value is
     * only available once the decoder thread has started, so the first PLAYING event often reports
     * nothing yet; retry shortly afterwards on the control executor.
     */
    private fun updateDecoderName(player: Pointer, immediate: Boolean) {
        val update = {
            try {
                val name = LibVlc.videoDecoderName(player)
                if (!name.isNullOrBlank()) {
                    val old = MediaPlayer.currentDecoder.getAndSet(name)
                    if (old != name && MediaPlayer.DEBUG) logger.debug("$debugLabel video decoder: {}.", name)
                } else {
                    logger.debug("$debugLabel video decoder info not ready yet.")
                }
            } catch (t: Throwable) {
                logger.debug("$debugLabel video decoder query failed: ${t.message}")
            }
        }
        if (immediate) update()
        submit {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            update()
        }
    }

    // ── Frame pipe proxy ────────────────────────────────────────────────────

    fun textureFilled(): Boolean = surface.textureFilled()

    fun updateFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean {
        if (w != expectedW || h != expectedH) {
            // Render thread drives the authoritative texture size. Adopt it (the old JavaCPP
            // pipeline did the same via getTextureSize()) and drop the stale-size ready frame;
            // the next publish scales to the new size and uploads match from then on.
            if (w > 0 && h > 0) {
                expectedW = w
                expectedH = h
            }
            surface.clear()
            return false
        }
        return surface.updateFrame(texture, w, h, expectedW, expectedH)
    }

    fun updateFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean {
        if (w != expectedW || h != expectedH) {
            if (w > 0 && h > 0) {
                expectedW = w
                expectedH = h
            }
            surface.clear()
            return false
        }
        return surface.updateFramePlanar(y, u, v, w, h, expectedW, expectedH)
    }

    fun hasIncoming(): Boolean = false // hard quality switch, no parallel channel

    fun updateIncomingFrame(texture: GpuTextureRef, w: Int, h: Int): Boolean = false

    fun updateIncomingFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, w: Int, h: Int): Boolean = false

    fun clearFrame() = surface.clear()

    // ── Session lifecycle ───────────────────────────────────────────────────

    /**
     * Starts (or restarts) playback of [streamSet] on the current session players.
     * Video is delivered via the low-level callbacks; audio uses the dedicated player on desktop
     * and on Android only when the selected rendition is separate from the video URL.
     */
    fun start(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean {
        val (w, h) = targetDims(streamSet, lastQuality)
        val safeUrl = MediaHostGuard.resolveSafeUrl(streamSet.currentVideo.url)
        val audioUrl = streamSet.currentAudio.url
        // Compare against the resolver's logical video URL, not the redirect target. A muxed direct
        // link commonly resolves to a signed CDN URL; treating that redirect as a separate rendition
        // would silence the video and download the same media a second time on Android.
        val separateAudio = audioUrl.isNotBlank() &&
            !audioUrl.equals(streamSet.currentVideo.url, ignoreCase = true)
        val safeAudioUrl = try {
            if (separateAudio) MediaHostGuard.resolveSafeUrl(audioUrl) else safeUrl
        } catch (error: Throwable) {
            logger.warn("$debugLabel rejected unsafe audio rendition before native playback: ${error.message}")
            return false
        }
        val started = AtomicBoolean(false)

        // Desktop replaces media on the same player; Android retires old players before creating fresh ones.
        val lifecycleToken = synchronized(lifecycleLock) {
            if (released.get()) return false
            stopped.set(false)
            lifecycleGeneration.incrementAndGet()
            lifecycleGeneration.get()
        }
        eosReached = false
        errorMessage = ""
        eosFired.set(false)
        parkFlag.set(false)
        // Fresh session: both players start together at the same offset, so the post-seek
        // snap-suppression window (armed by beginSeek / seek verification) must not leak in and
        // defer the initial A/V alignment chain.
        seekSettleUntilNanos = 0L
        seekFrozen = false
        seekGeneration.incrementAndGet()
        expectedW = w
        expectedH = h
        firstFrameFired = false
        firstFrameLatch = CountDownLatch(1)
        // Reset the playback clock to the requested offset so the progress bar starts at the
        // right second instead of inheriting a stale origin from the previous session.
        clock.reset(offsetNanos)
        // Reset the audio output for this new session (re-prime the 3D DSP chain, flush the line).
        runCatching { audioOutput?.reset() }

        // Build the media options (UA + referer + optional hw decode). Desktop audio is played by a
        // SEPARATE libvlc player (see below) so its Java Sound `write()` blocking never throttles the
        // video vout. Android only does that for separate renditions; muxed media keeps its audio on
        // the video player. When the video media gets `:no-audio`, its master clock is the system
        // clock, so the vout delivers at the full source frame rate instead of being dragged to ~60%
        // by an audio clock that is pulsed by line writes. Hardware decode is enabled
        // BOTH here (media-level) and at instance-level (--avcodec-hw=any); VLC copies the GPU-decoded
        // frame back to system memory before handing it to the vmem lock callback, so vmem and hw
        // coexist and 4K H.264/HEVC decodes on the GPU instead of starving the CPU.
        val mediaOptions = mutableListOf(*LibVlcMediaOptions.forUrl(safeUrl))
        // Media-level hw decode is an avcodec-module concept (desktop backends only); Android
        // selects its decoder at instance level and defaults to explicit software avcodec when
        // the launcher cannot provide a real MediaCodec bridge.
        if (!systemAudio) {
            // currentHwBackend() may return "none" (explicit software decode after the hw chain
            // was exhausted, or an empty -Ddreamdisplayx.hwDecode= override): keep that option —
            // with no option libvlc defaults to `any` and could silently re-pick the broken hw
            // backend that just failed.
            LibVlc.currentHwBackend()?.let { mediaOptions.add(":avcodec-hw=$it") }
        }
        // Desktop always silences the video player and routes sound through the dedicated audio player —
        // including a single-file source (an mp4 direct link, or any stream whose audio URL equals the
        // video URL), which is fed the SAME url. Letting libvlc's own output play that track proved
        // unreliable on Linux: pulse reported "write index corrupt"/underflow, and the wedged aout kept
        // libvlc's decoder fifo from draining at end of stream, which froze the game. The Java Sound line
        // is the one audio path whose pacing we control. Cost: libvlc downloads that url a second time.
        if (systemAudio && separateAudio) {
            // A video-only rendition must stay silent while the dedicated Android audio player
            // feeds OpenSL ES. Muxed URLs keep their audio in the video player and do not need a
            // second native player.
            mediaOptions.add(":no-audio")
        } else if (!systemAudio) {
            mediaOptions.add(":no-audio")
        }
        if (separateAudio) {
            logger.info("$debugLabel audio will be played by the separate audio player.")
        } else if (audioUrl.isNotBlank()) {
            if (systemAudio) {
                logger.info("$debugLabel muxed audio will remain on the video player.")
            } else {
                logger.info("$debugLabel single-file audio: the dedicated audio player plays the same url.")
            }
        } else {
            logger.warn("$debugLabel no audio track resolved (audioUrl is blank); the display stays silent.")
        }

        submit {
            // A stop() / stopNow() that landed between this task's submission and its run must win:
            // running the attach would resurrect a player the stop already tore down (and stack it
            // under the replacement player the caller went on to create).
            if (!lifecycleIsCurrent(lifecycleToken)) return@submit
            // Android never reuses a player that has already carried media: even set_media() may
            // tear down its old input/vout/aout and trigger VLC-Android's unsafe TLS destructor.
            if (systemAudio) retireAndroidPlayers()
            if (!lifecycleIsCurrent(lifecycleToken)) return@submit
            val mp = player()
            if (mp == null) {
                errorMessage = "libvlc player unavailable"
                eosReached = true
                return@submit
            }
            try {
                // Reload safety: tear down the PREVIOUS media before attaching the new one. Setting
                // media on a player whose old vout/aout threads are still draining races the new
                // format setup and crashes natively (EXCEPTION_ACCESS_VIOLATION right after the new
                // "libvlc video setup" line, before the first frame). libvlc_media_player_stop is
                // synchronous on the control executor, so the old input fully releases first.
                // Android: NEVER call stop/release, and do not reuse a player after it has carried
                // media. Even set_media() can implicitly tear down the old input/vout/aout in this
                // VLC-Android AAR and reach the same TLS destructor UAF. The old player was retired
                // before this block; this player is fresh and may receive the new media.
                if (!systemAudio) {
                    runCatching { LibVlc.lib.libvlc_media_player_stop(mp) }
                }
                // Re-check after the (potentially long) old-media stop: a stop() that arrived while
                // this task was draining the previous input must still win over the new attach.
                if (!lifecycleIsCurrent(lifecycleToken)) return@submit
                val activeMp = mp
                val media = LibVlc.createMedia(safeUrl, mediaOptions.toTypedArray())
                val attachVideo = synchronized(lifecycleLock) {
                    if (lifecycleGeneration.get() == lifecycleToken && !stopped.get() && !released.get()) {
                        LibVlc.lib.libvlc_media_player_set_media(activeMp, media)
                        true
                    } else {
                        false
                    }
                }
                LibVlc.lib.libvlc_media_release(media)
                if (!attachVideo) return@submit // the player holds its own reference
                // Desktop always uses a dedicated audio player so Java Sound pacing cannot throttle
                // the vout. Android only creates one for a separate audio rendition; muxed media stays
                // on the video player's OpenSL ES output, avoiding duplicate sound.
                if (!lifecycleIsCurrent(lifecycleToken)) return@submit
                val ap = if (audioUrl.isNotBlank() && (!systemAudio || separateAudio)) audioPlayer() else null
                if (ap != null) {
                    // Same rule as above: never stop() on Android — set_media replaces the old
                    // input synchronously; an explicit stop would tear worker threads and hit the
                    // VLC-Android TLS destructor UAF (pthread_key_clean_all, libvlc.so+0xef7418).
                    if (!systemAudio) {
                        runCatching { LibVlc.lib.libvlc_media_player_stop(ap) }
                    }
                    val audioUrlToPlay = if (separateAudio) safeAudioUrl else safeUrl
                    val audioOptions = mutableListOf(*LibVlcMediaOptions.forUrl(audioUrlToPlay))
                    audioOptions.add(":no-video")
                    val audioMedia = LibVlc.createMedia(audioUrlToPlay, audioOptions.toTypedArray())
                    val attachAudio = synchronized(lifecycleLock) {
                        if (lifecycleGeneration.get() == lifecycleToken && !stopped.get() && !released.get()) {
                            LibVlc.lib.libvlc_media_player_set_media(ap, audioMedia)
                            true
                        } else {
                            false
                        }
                    }
                    LibVlc.lib.libvlc_media_release(audioMedia)
                    if (!attachAudio) return@submit
                    synchronized(lifecycleLock) {
                        if (lifecycleGeneration.get() != lifecycleToken || stopped.get() || released.get()) return@submit
                        LibVlc.lib.libvlc_media_player_play(ap)
                    }
                } else if (audioUrl.isBlank()) {
                    logger.info("$debugLabel audio player skipped (no audio track resolved).")
                }
                synchronized(lifecycleLock) {
                    if (lifecycleGeneration.get() != lifecycleToken || stopped.get() || released.get()) return@submit
                    LibVlc.lib.libvlc_media_player_play(activeMp)
                }
                // libvlc volume is per-player. Re-assert it after every fresh play so both the muxed
                // video path and a separate Android audio player honor the user's pre-start volume.
                if (systemAudio) {
                    val volumePercent = (lastVolume.coerceIn(0.0, 1.0) * 100).toInt()
                    runCatching { LibVlc.lib.libvlc_audio_set_volume(activeMp, volumePercent) }
                    ap?.let { runCatching { LibVlc.lib.libvlc_audio_set_volume(it, volumePercent) } }
                }
                if (!lifecycleIsCurrent(lifecycleToken)) return@submit
                isPlaying = true
                // Initial A/V sync: both players start independently, so the audio player's clock can
                // drift from the video's right from the start. Schedule an early correction (~1.5s)
                // rather than waiting for the 10s auto-resync.
                scheduleInitialAvSync()
                started.set(true)
            } catch (t: Throwable) {
                logger.error("$debugLabel failed to start libvlc media: ${t.message}")
                errorMessage = t.message ?: "libvlc start failed"
                eosReached = true
            }
        }

        // Seek if needed (after media is set; performed on the control executor).
        // libvlc_media_player_set_time takes MILLISECONDS; convert ns -> ms.
        if (offsetNanos > 0) {
            submit {
                synchronized(lifecycleLock) {
                    if (!lifecycleIsCurrent(lifecycleToken)) return@submit
                    val mp = mediaPlayer ?: return@submit
                    runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, offsetNanos / 1_000_000L) }
                    val ap = audioPlayer
                    if (ap != null) runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, offsetNanos / 1_000_000L) }
                }
            }
        }

        // Wait for the first frame so the caller can rely on frames being delivered (with timeout).
        // The control-executor submit is asynchronous, so unconditionally await the latch.
        try {
            if (!firstFrameLatch.await(10, TimeUnit.SECONDS)) {
                logger.error("$debugLabel libvlc first frame timeout")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return started.get()
    }

    /**
     * Returns the current libvlc VIDEO player, creating it on first use. Desktop keeps and reuses
     * this player; Android replaces it after every media-bearing session. Desktop media gets
     * `:no-audio` because the separate audio player's Java Sound `write()` must not throttle the
     * vout; Android adds that flag only for video-only renditions.
     */
    private fun player(): Pointer? = synchronized(lifecycleLock) {
        if (stopped.get() || released.get()) return@synchronized null
        val existing = mediaPlayer
        if (existing != null) return@synchronized existing
        try {
            LibVlc.ensureLoaded()
            val lib = LibVlc.lib
            val mp = lib.libvlc_media_player_new(LibVlc.libvlcInstance)
            if (mp == null) {
                logger.error("$debugLabel libvlc_media_player_new returned null: ${LibVlc.errmsg()}")
                return null
            }
            // Each native player gets an independent callback set and direct-buffer pool. Skippable
            // via the diagnostic switch for bisection; callback sets are retained for delayed callbacks
            // from retired Android players.
            if (!LibVlcDiagnostics.noVideoCallback) {
                val callbacks = VideoCallbackSet()
                videoCallbackSets += callbacks
                videoCallbacksByPlayer[Pointer.nativeValue(mp)] = callbacks
                lib.libvlc_video_set_format_callbacks(mp, callbacks.format, callbacks.cleanup)
                lib.libvlc_video_set_callbacks(mp, callbacks.lock, callbacks.unlock, callbacks.display, null)
            }
            // Events are bound to this concrete player. The video player drives session state;
            // retired Android players may still emit delayed events that must be ignored.
            val em = lib.libvlc_media_player_event_manager(mp)
            if (em != null) {
                val callback = LibVlc.EventCallback { event, _ -> handleEvent(event, mp) }
                LibVlc.configureCallbackThread(callback)
                eventCallbacks += callback
                for (e in MEDIA_PLAYER_EVENTS) {
                    lib.libvlc_event_attach(em, e, callback, null)
                }
            }
            mediaPlayer = mp
            if (MediaPlayer.DEBUG) logger.debug("$debugLabel created libvlc video player.")
            mp
        } catch (t: Throwable) {
            logger.error("$debugLabel failed to create libvlc media player: ${t.message}")
            null
        }
    }

    /**
     * Returns the current dedicated AUDIO-only libvlc media player, creating it on first use.
     * Desktop reuses it; Android replaces it with the video player after each media-bearing session.
     * On desktop it only registers the audio callbacks that feed the 3D DSP + Java
     * Sound line ([LibVlcAudioOutput]); its media gets `:no-video`. No events are attached: its
     * END_REACHED (Bilibili DASH audio slaves run shorter than the video) must NOT trigger the
     * session EOS. Skipped entirely when [LibVlcDiagnostics.noAudioCallback] is set (bisection).
     * On Android no callback pipeline exists (no javax.sound): the player is created WITHOUT
     * audio callbacks so libvlc's own OpenSL ES output (instance-level `--aout=opensles`) plays
     * the DASH audio m4s directly — without it the separate audio URL on a video-only Bilibili
     * DASH m4s is never played and there is no sound at all.
     */
    private fun audioPlayer(): Pointer? = synchronized(lifecycleLock) {
        if (LibVlcDiagnostics.noAudioCallback || stopped.get() || released.get()) return@synchronized null
        val existing = audioPlayer
        if (existing != null) return@synchronized existing
        try {
            LibVlc.ensureLoaded()
            val lib = LibVlc.lib
            val ap = lib.libvlc_media_player_new(LibVlc.libvlcInstance)
            if (ap == null) {
                logger.error("$debugLabel libvlc audio player new returned null: ${LibVlc.errmsg()}")
                return null
            }
            if (!systemAudio) {
                // Desktop only: audio callbacks feed the 3D DSP + Java Sound line. The line is
                // pre-opened here so the first PCM block has a destination (also primes the default format).
                openAudioLine()
                LibVlc.configureCallbackThread(audioFormatSetupCallback)
                LibVlc.configureCallbackThread(audioFormatCleanupCallback)
                LibVlc.configureCallbackThread(audioPlayCallback)
                LibVlc.configureCallbackThread(audioPauseCallback)
                LibVlc.configureCallbackThread(audioResumeCallback)
                LibVlc.configureCallbackThread(audioFlushCallback)
                LibVlc.configureCallbackThread(audioDrainCallback)
                lib.libvlc_audio_set_format_callbacks(ap, audioFormatSetupCallback, audioFormatCleanupCallback)
                lib.libvlc_audio_set_callbacks(ap, audioPlayCallback, audioPauseCallback,
                    audioResumeCallback, audioFlushCallback, audioDrainCallback, null)
            }
            audioPlayer = ap
            if (MediaPlayer.DEBUG) logger.debug("$debugLabel created dedicated libvlc audio player.")
            ap
        } catch (t: Throwable) {
            logger.error("$debugLabel failed to create libvlc audio player: ${t.message}")
            null
        }
    }

    /**
     * Starts video-only replay (not supported by libvlc port; returns false).
     */
    fun startReplayVideoOnly(
        @Suppress("UNUSED_PARAMETER") snapshot: ByteArray?,
        @Suppress("UNUSED_PARAMETER") resume: Long,
        @Suppress("UNUSED_PARAMETER") positionNanos: Long,
        @Suppress("UNUSED_PARAMETER") audioPcm: ByteArray?,
    ): Boolean = false

    /**
     * Attaches a live stream after a video-only replay (not supported; returns false).
     */
    fun attachLiveAfterReplay(
        @Suppress("UNUSED_PARAMETER") streamSet: ActiveStreams,
        @Suppress("UNUSED_PARAMETER") liveOffsetNanos: Long,
        @Suppress("UNUSED_PARAMETER") lastQuality: Int,
    ): Boolean = false

    /** Seeks both the video and audio players to [offsetNanos]. */
    fun beginSeek(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean {
        // Reset audio flags before the seek so a pause-then-resume immediately after seek starts clean.
        audioOutput?.onSeekReset()
        // A seek/loop reuses the native player, but it also resets the software clock. Let the first
        // frame at the new target mark that clock again; otherwise firstFrameFired stays true from
        // the previous position and sync following sees a permanently stopped clock.
        firstFrameFired = false
        // Retire verification from an older seek before publishing this seek's target.
        val seekId = seekGeneration.incrementAndGet()
        val lifecycleToken = synchronized(lifecycleLock) { lifecycleGeneration.get() }
        val targetMs = offsetNanos / 1_000_000L
        // Hold the last displayed picture while the video demuxer flushes onto the target. This
        // prevents pre-seek frames from visibly continuing while the audio player has already moved.
        seekFrozenTargetMs = targetMs
        seekFrozenDeadlineNanos = System.nanoTime() + SEEK_FREEZE_MAX_NANOS
        seekFrozen = true
        // Suppress the A/V snap chain for the settle window: the video demuxer takes seconds to
        // flush onto a far target while the audio player lands there immediately — snapping audio
        // to the video's stale clock would rewind the whole playback to the pre-seek position.
        armSeekSettleWindow()
        submit {
            if (seekId != seekGeneration.get() || !lifecycleIsCurrent(lifecycleToken)) return@submit
            val mp = mediaPlayer ?: return@submit
            // ENDED state ignores set_time and a bare play() is not guaranteed to restart in libvlc
            // 3.0 — stop() first, then play() restarts from the beginning (loop/replay path).
            val state = try { LibVlc.lib.libvlc_media_player_get_state(mp) } catch (_: Throwable) { -1 }
            if (state == LibVlc.LIBVLC_STATE_ENDED) {
                try {
                    if (systemAudio) {
                        // Android: never stop() or set_media() on an ended player. Both operations can
                        // tear down VLC-Android workers and hit its unsafe TLS destructor. Validate
                        // the selected URLs before retiring the old players, then bind fresh players.
                        val vUrl = streamSet.currentVideo.url
                        val safeVUrl = MediaHostGuard.resolveSafeUrl(vUrl)
                        val aUrl = streamSet.currentAudio.url
                        val safeAUrl = if (aUrl.isNotBlank() && !aUrl.equals(vUrl, ignoreCase = true)) {
                            MediaHostGuard.resolveSafeUrl(aUrl)
                        } else {
                            null
                        }
                        retireAndroidPlayers()
                        if (!lifecycleIsCurrent(lifecycleToken) || seekId != seekGeneration.get()) return@submit
                        val freshMp = player()
                        if (freshMp == null) throw IllegalStateException("libvlc video player unavailable")
                        val vOpts = mutableListOf(*LibVlcMediaOptions.forUrl(safeVUrl))
                        val vMedia = runCatching { LibVlc.createMedia(safeVUrl, vOpts.toTypedArray()) }.getOrNull()
                        if (vMedia != null) {
                            val attach = synchronized(lifecycleLock) {
                                if (lifecycleGeneration.get() == lifecycleToken && !stopped.get() && !released.get() && seekId == seekGeneration.get()) {
                                    runCatching { LibVlc.lib.libvlc_media_player_set_media(freshMp, vMedia) }
                                    true
                                } else {
                                    false
                                }
                            }
                            runCatching { LibVlc.lib.libvlc_media_release(vMedia) }
                            if (!attach) return@submit
                            synchronized(lifecycleLock) {
                                if (lifecycleGeneration.get() != lifecycleToken || stopped.get() || released.get() || seekId != seekGeneration.get()) return@submit
                                runCatching { LibVlc.lib.libvlc_media_player_play(freshMp) }
                                runCatching { LibVlc.lib.libvlc_media_player_set_time(freshMp, offsetNanos / 1_000_000L) }
                            }
                        }
                        // Audio player: bind the fresh player too, never reuse the ended one.
                        val ap = audioPlayer()
                        if (ap != null && safeAUrl != null) {
                            val aOpts = mutableListOf(*LibVlcMediaOptions.forUrl(safeAUrl))
                            aOpts.add(":no-video")
                            val aMedia = runCatching { LibVlc.createMedia(safeAUrl, aOpts.toTypedArray()) }.getOrNull()
                            if (aMedia != null) {
                                val attach = synchronized(lifecycleLock) {
                                    if (lifecycleGeneration.get() == lifecycleToken && !stopped.get() && !released.get() && seekId == seekGeneration.get()) {
                                        runCatching { LibVlc.lib.libvlc_media_player_set_media(ap, aMedia) }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                runCatching { LibVlc.lib.libvlc_media_release(aMedia) }
                                if (!attach) return@submit
                                synchronized(lifecycleLock) {
                                    if (lifecycleGeneration.get() != lifecycleToken || stopped.get() || released.get() || seekId != seekGeneration.get()) return@submit
                                    runCatching { LibVlc.lib.libvlc_media_player_play(ap) }
                                    runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, offsetNanos / 1_000_000L) }
                                }
                            }
                        }
                    } else {
                        // Desktop loop/replay (there is no Android TLS destructor). For the normal
                        // loop target (0 ms), play() is enough to leave ENDED without stopping the
                        // input first. Stopping the dedicated audio player at every five-second EOF
                        // made libvlc wait for decoder FIFOs to drain; after several loops that was
                        // the last line in the native log and the display froze. User seeks to a
                        // non-zero target still use the conservative stop -> play -> set_time path.
                        val zeroTarget = targetMs == 0L
                        if (zeroTarget) {
                            runCatching { LibVlc.lib.libvlc_media_player_play(mp) }
                            runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, 0L) }
                        } else {
                            LibVlc.lib.libvlc_media_player_stop(mp)
                            LibVlc.lib.libvlc_media_player_play(mp)
                            runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, targetMs) }
                        }
                        // The audio player must be restarted too: its own stream also reached ENDED
                        // (Bilibili DASH audio is often shorter than the video). Avoid stopping it on
                        // the ordinary loop; a stopped audio input is what leaves the decoder FIFO
                        // teardown pending in the reported Linux trace.
                        val ap = audioPlayer
                        if (ap != null) {
                            if (zeroTarget) {
                                runCatching { LibVlc.lib.libvlc_media_player_play(ap) }
                                runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, 0L) }
                            } else {
                                runCatching { LibVlc.lib.libvlc_media_player_stop(ap) }
                                runCatching { LibVlc.lib.libvlc_media_player_play(ap) }
                                runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, targetMs) }
                            }
                        }
                        scheduleSeekLandingVerification(targetMs)
                    }
                    // Re-arm the EOS gate: the loop/replay restart path (beginSeek from ENDED) must
                    // let the NEXT end-of-stream fire handleStreamEnd again, otherwise the second
                    // replay ends frozen on the last frame (eosFired stays true after the first
                    // ENDED → fireStreamEnd, so the second END_REACHED event is swallowed).
                    eosFired.set(false)
                    // The two players restart independently, so their clocks drift apart right away;
                    // the 10s auto-resync would leave the audio audibly off-sync for the first loop.
                    // Schedule an early correction (~1.5s) once both have settled into playback.
                    scheduleInitialAvSync()
                } catch (_: Throwable) { }
            } else {
                // libvlc_media_player_set_time takes MILLISECONDS; convert ns -> ms.
                synchronized(lifecycleLock) {
                    if (lifecycleGeneration.get() != lifecycleToken || stopped.get() || released.get() || seekId != seekGeneration.get()) return@submit
                    runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, targetMs) }
                    // Seek the audio player to the same position (non-ENDED path).
                    val ap = audioPlayer
                    if (ap != null) runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, targetMs) }
                }
                scheduleSeekLandingVerification(targetMs)
                // Re-arm the EOS gate after every in-place seek, matching the ENDED restart path;
                // a prior end notification must not suppress the next genuine end event.
                eosFired.set(false)
                // The two players recover from a seek independently (separate buffers / decoders), so
                // their clocks can land apart; the 10s auto-resync would leave the audio audibly
                // off-sync for the first ~10s after every seek. Schedule the same early correction
                // (~1.5s) the cold-start path uses. Skipped while parked by scheduleInitialAvSync.
                scheduleInitialAvSync()
            }
            if (seekId != seekGeneration.get() || !lifecycleIsCurrent(lifecycleToken)) return@submit
            // If a seek left the player in a dead state (stopped/ended — e.g. a backwards seek
            // into an already-released region dropping it out of PLAYING), resume it. Buffering(2)
            // is a normal transient after a backwards seek and MUST NOT be play()ed — that would
            // interrupt the seek and freeze the picture (the "backwards seek sometimes sticks" bug).
            try {
                // Android may have retired the player in the ENDED branch above; never query or
                // resume that stale pointer after rotation. Use only the current active player, while
                // holding the lifecycle lock so cleanup cannot release it between the check and call.
                val after = synchronized(lifecycleLock) {
                    if (lifecycleGeneration.get() != lifecycleToken || stopped.get() || released.get() || seekId != seekGeneration.get()) return@submit
                    val currentMp = mediaPlayer ?: return@submit
                    val sampled = LibVlc.lib.libvlc_media_player_get_state(currentMp)
                    if (sampled == LibVlc.LIBVLC_STATE_STOPPED || sampled == LibVlc.LIBVLC_STATE_ENDED) {
                        // `play()` is asynchronous, so an immediate state sample can still read ENDED
                        // even when the input is already leaving EOF. Do not stop here: that would
                        // reintroduce the decoder-fifo teardown that froze repeated loops. A delayed,
                        // generation-aware fallback below handles a genuinely latched ENDED state.
                        LibVlc.lib.libvlc_media_player_play(currentMp)
                    }
                    sampled
                }
                // A successful loop is logically playing immediately; the asynchronous PLAYING
                // callback will confirm it later. Updating this flag here prevents playlist/sync
                // callbacks arriving in that short window from treating the session as dead and
                // queueing another cold start on top of the just-restarted inputs.
                if (after == LibVlc.LIBVLC_STATE_STOPPED || after == LibVlc.LIBVLC_STATE_ENDED ||
                    after == LibVlc.LIBVLC_STATE_PLAYING || after == LibVlc.LIBVLC_STATE_PAUSED
                ) {
                    if (!reviveIfCurrentSeek(seekId)) return@submit
                }
                if (!systemAudio && targetMs == 0L) {
                    scheduleEndedRestartFallback(seekId, lifecycleToken)
                }
            } catch (_: Throwable) { }
        }
        logger.debug("$debugLabel libvlc seek to ${offsetNanos / 1_000_000} ms.")
        return true
    }

    /** Re-checks an ended desktop replay after libvlc has had time to leave ENDED asynchronously. */
    private fun scheduleEndedRestartFallback(seekId: Long, lifecycleToken: Long) {
        Thread({
            // libvlc transitions out of ENDED asynchronously. Give the normal play()+set_time
            // path a short window before using the conservative desktop stop/play fallback.
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            submit {
                val restarted = synchronized(lifecycleLock) {
                    if (seekId != seekGeneration.get() || lifecycleGeneration.get() != lifecycleToken || stopped.get() || released.get()) return@submit
                    val mp = mediaPlayer ?: return@submit
                    val videoState = runCatching { LibVlc.lib.libvlc_media_player_get_state(mp) }.getOrDefault(-1)
                    val videoDead = videoState == LibVlc.LIBVLC_STATE_STOPPED || videoState == LibVlc.LIBVLC_STATE_ENDED
                    val ap = audioPlayer
                    val audioState = ap?.let {
                        runCatching { LibVlc.lib.libvlc_media_player_get_state(it) }.getOrDefault(-1)
                    }
                    val audioDead = audioState == LibVlc.LIBVLC_STATE_STOPPED || audioState == LibVlc.LIBVLC_STATE_ENDED
                    if (!videoDead && !audioDead) return@submit
                    logger.warn(
                        "$debugLabel ended replay remained in native state (video={}, audio={}); retrying dead channel(s) with stop/play.",
                        videoState, audioState,
                    )
                    if (videoDead) {
                        runCatching { LibVlc.lib.libvlc_media_player_stop(mp) }
                        runCatching { LibVlc.lib.libvlc_media_player_play(mp) }
                        runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, 0L) }
                    }
                    if (audioDead && ap != null) {
                        runCatching { LibVlc.lib.libvlc_media_player_stop(ap) }
                        runCatching { LibVlc.lib.libvlc_media_player_play(ap) }
                        runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, 0L) }
                    }
                    true
                }
                if (restarted) reviveIfCurrentSeek(seekId)
            }
        }, "$debugLabel-loop-recovery").also { it.isDaemon = true }.start()
    }

    private fun lifecycleIsCurrent(token: Long): Boolean = synchronized(lifecycleLock) {
        lifecycleGeneration.get() == token && !stopped.get() && !released.get()
    }

    /** Reopens a seek only if no newer seek or stop won the lifecycle race. */
    private fun reviveIfCurrentSeek(seekId: Long): Boolean = synchronized(lifecycleLock) {
        if (seekId != seekGeneration.get() || stopped.get() || released.get()) return@synchronized false
        isPlaying = true
        stopped.set(false)
        eosReached = false
        true
    }

    /** Publishes a stop and invalidates queued seek/recovery work as one lifecycle transition. */
    private fun markStopped(parked: Boolean, releasing: Boolean = false) {
        synchronized(lifecycleLock) {
            isPlaying = false
            parkFlag.set(parked)
            eosReached = true
            stopped.set(true)
            lifecycleGeneration.incrementAndGet()
            if (releasing) released.set(true)
            seekSettleUntilNanos = 0L
            seekFrozen = false
            seekGeneration.incrementAndGet()
        }
    }

    /**
     * Verifies an in-place seek without interrupting a legitimate slow demuxer flush.
     *
     * Two clock samples distinguish the failure modes: an advancing clock still playing the
     * pre-seek timeline means libvlc dropped set_time and warrants one re-assertion; a stalled
     * clock means the demuxer is flushing toward the target, so re-applying set_time would restart
     * that flush. The loop is bounded and generation-aware, so newer seeks retire stale checks.
     */
    private fun scheduleSeekLandingVerification(targetMs: Long) {
        val generation = seekGeneration.get()
        Thread {
            var checks = 0
            var reasserted = false
            try {
                Thread.sleep(SEEK_VERIFY_DELAY_NANOS / 1_000_000L)
            } catch (_: InterruptedException) {
                return@Thread
            }
            while (checks++ < SEEK_VERIFY_MAX_CHECKS) {
                if (stopped.get() || parkFlag.get() || released.get()) return@Thread
                if (seekGeneration.get() != generation) return@Thread
                val mp = mediaPlayer ?: return@Thread
                val state = runCatching { LibVlc.lib.libvlc_media_player_get_state(mp) }.getOrDefault(-1)
                // 3 = PLAYING, 2 = BUFFERING. Other states do not need a seek re-assertion.
                if (state != LibVlc.LIBVLC_STATE_PLAYING && state != 2) return@Thread
                val firstMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                if (firstMs < 0) return@Thread
                if (kotlin.math.abs(firstMs - targetMs) <= SEEK_VERIFY_TOLERANCE_MS) {
                    seekFrozen = false
                    return@Thread
                }
                try {
                    Thread.sleep(SEEK_VERIFY_SAMPLE_GAP_NANOS / 1_000_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (stopped.get() || parkFlag.get() || released.get()) return@Thread
                if (seekGeneration.get() != generation) return@Thread
                val secondMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                if (secondMs < 0) return@Thread

                // Keep audio from being snapped back to the stale video clock while this seek is
                // still settling, including flushes longer than the original five-second window.
                armSeekSettleWindow()
                when (classifySeekLanding(firstMs, secondMs, targetMs, SEEK_VERIFY_TOLERANCE_MS, SEEK_VERIFY_DROP_ADVANCE_MS)) {
                    SeekLanding.LANDED -> {
                        seekFrozen = false
                        return@Thread
                    }
                    SeekLanding.FLUSHING -> {
                        logger.debug(
                            "$debugLabel Seek to {} ms still flushing (video clock at {} ms); waiting.",
                            targetMs, secondMs
                        )
                    }
                    SeekLanding.DROPPED -> {
                        if (reasserted) {
                            logger.warn(
                                "$debugLabel Seek to {} ms still not landed after re-assert (video clock advancing at {} ms); giving up.",
                                targetMs, secondMs
                            )
                            seekFrozen = false
                            return@Thread
                        }
                        reasserted = true
                        logger.warn(
                            "$debugLabel Seek to {} ms was dropped (video clock advancing {} -> {} ms); re-applying set_time.",
                            targetMs, firstMs, secondMs
                        )
                        runCatching {
                            submit {
                                if (stopped.get() || parkFlag.get() || released.get()) return@submit
                                if (seekGeneration.get() != generation) return@submit
                                val current = mediaPlayer ?: return@submit
                                runCatching { LibVlc.lib.libvlc_media_player_set_time(current, targetMs) }
                                audioPlayer?.let { ap ->
                                    runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, targetMs) }
                                }
                                armSeekSettleWindow()
                            }
                        }
                        try {
                            Thread.sleep(SEEK_VERIFY_RECHECK_NANOS / 1_000_000L)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                }
            }
            // Fail open: a permanently broken seek must not leave the display frozen forever.
            seekFrozen = false
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Schedules a short A/V correction chain right after playback starts. The video and audio
     * players start independently, so their clocks drift apart immediately; the 10s auto-resync in
     * [currentPacingNanos] would leave the audio audibly off-sync for the first ~10s. This snaps the
     * audio player to the video `get_time` as soon as both have settled, then re-checks a few times:
     * right after a video switch the audio player may still be opening its stream, so the first
     * check often fires before its clock exists. Best-effort: the 10s auto-resync still corrects
     * any residual drift.
     */
    private fun scheduleInitialAvSync() {
        if (LibVlcDiagnostics.noAutoResync) return
        Thread {
            // First check well before the old 1.5s mark, then a few spaced re-checks so a slow
            // audio start converges quickly instead of waiting for the 10s auto-resync.
            var delayMs = 400L
            var attempts = 0
            while (attempts < 6 && !stopped.get()) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                delayMs = 700L
                attempts++
                val aligned = CountDownLatch(1)
                var inSync = false
                submit {
                    try {
                        val mp = mediaPlayer
                        val ap = audioPlayer
                        if (mp == null || ap == null || stopped.get() || parkFlag.get()) {
                            inSync = true // nothing to sync any more; end the chain
                            return@submit
                        }
                        val videoMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                        val audioMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(ap) }.getOrDefault(-1L)
                        when {
                            // Post-seek settle window: the video clock still reports the pre-seek
                            // position while its demuxer flushes toward the target. Snapping the
                            // audio (already at the target) to that stale clock rewinds playback.
                            seekSettling() -> Unit
                            // Video clock not up yet: keep waiting for a later attempt.
                            videoMs < 0 -> Unit
                            // Audio clock not up yet, or audibly drifted: snap it to the video.
                            audioMs < 0 || kotlin.math.abs(videoMs - audioMs) > 250L -> {
                                runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, videoMs) }
                                logger.debug("$debugLabel initial A/V sync: audio player snapped to {} ms.", videoMs)
                            }
                            // Within a quarter second: good enough, stop the chain.
                            else -> inSync = true
                        }
                    } finally {
                        aligned.countDown()
                    }
                }
                aligned.await(2, TimeUnit.SECONDS)
                if (inSync) return@Thread
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Stops the session. To the extent possible the libvlc players are NOT stopped on Android:
     * this libvlc-all AAR's `libvlc_media_player_stop` does not join every VLC worker thread, and
     * `pthread_key_clean_all` on an exiting thread runs VLC-Android's `jni_detach_thread` TLS
     * destructor which dereferences thread-local state — observed SIGSEGV at `libvlc.so+0xef7418`
     * on plain world exit AND on audio DASH stream end even with stop serialised and never released.
     * Pausing keeps every worker thread alive, so the TLS destructor never runs with stale state;
     * the players then stay allocated for the JVM lifetime and the OS reclaims them on exit.
     * Desktop keeps stop (its threads join synchronously and there is no Android TLS destructor).
     */
    fun stop() {
        markStopped(parked = false)
        // Wake a MediaPlayer.start() waiting for its first frame. Without this, a switch that
        // arrives while the old stream is buffering waits the full ten-second first-frame timeout
        // before its teardown can run.
        firstFrameLatch.countDown()
        submit {
            val mp = mediaPlayer
            if (mp != null) {
                stopOrPause(mp)
            }
            val ap = audioPlayer
            if (ap != null) {
                stopOrPause(ap)
            }
        }
        surface.clear()
    }

    /**
     * Stops playback from the CALLER's thread, bypassing the control-executor queue.
     *
     * `stop()` serialises through the single control executor, but that queue can be blocked by a
     * long-running task (a slow media attach whose native input thread is stuck reading a
     * throttled CDN edge). The stop then sits behind it for tens of seconds, `awaitStopped()`
     * times out, and the caller creates a replacement player while the old one is still alive —
     * two native players and two vouts on one display (observed 2026-09-06: two "libvlc video
     * setup" lines a second apart for two different URLs after a video switch). libvlc's player
     * API is thread-safe, so calling stop/pause directly here tears the media down immediately;
     * the queued [stop]/[cleanup] tasks that follow become near-instant no-ops.
     */
    fun stopNow() {
        markStopped(parked = false)
        firstFrameLatch.countDown()
        val mp = mediaPlayer
        if (mp != null) {
            runCatching {
                if (systemAudio) LibVlc.lib.libvlc_media_player_set_pause(mp, 1)
                else LibVlc.lib.libvlc_media_player_stop(mp)
            }
        }
        val ap = audioPlayer
        if (ap != null) {
            runCatching {
                if (systemAudio) LibVlc.lib.libvlc_media_player_set_pause(ap, 1)
                else LibVlc.lib.libvlc_media_player_stop(ap)
            }
        }
        surface.clear()
    }

    /**
     * Immediately PAUSES both native players from the caller's thread WITHOUT the blocking native
     * stop: `libvlc_media_player_set_pause` returns at once and does not join the input / vout / aout
     * worker threads, unlike `libvlc_media_player_stop` (which can sit for seconds behind a throttled
     * CDN edge). A caller about to release GPU resources can therefore be sure no further frames are
     * produced without freezing the render / client thread.
     */
    fun pauseNow() {
        markStopped(parked = true)
        // stopAsync() uses this path while a start task may be waiting for the first frame.
        firstFrameLatch.countDown()
        val mp = mediaPlayer
        if (mp != null) runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 1) }
        val ap = audioPlayer
        if (ap != null) runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 1) }
        audioOutput?.setPaused(true)
        surface.clear()
    }

    /**
     * Android: pause instead of stop (see [stop] KDoc) — worker threads stay alive, the TLS
     * destructor never observes freed state. Desktop: plain stop.
     */
    private fun stopOrPause(p: Pointer) {
        if (systemAudio) {
            runCatching { LibVlc.lib.libvlc_media_player_set_pause(p, 1) }
        } else {
            runCatching { LibVlc.lib.libvlc_media_player_stop(p) }
        }
    }

    /**
     * Retires Android players without invoking stop, release, or set_media on them.
     * The VLC-Android AAR may tear down native workers from set_media too, so retired players
     * remain paused and strongly referenced until process exit; the OS then reclaims them. We still
     * wait briefly for the paused state before creating a replacement, which prevents two vouts from
     * decoding at full speed during rapid URL/quality switches while preserving the safe no-stop rule.
     */
    private fun retireAndroidPlayers() {
        synchronized(lifecycleLock) {
            if (!systemAudio) return
            mediaPlayer?.let { player ->
                deactivateVideoCallbacks(player)
                pauseAndroidPlayerAndAwait(player)
                retiredAndroidPlayers += player
            }
            audioPlayer?.let { player ->
                pauseAndroidPlayerAndAwait(player)
                retiredAndroidPlayers += player
            }
            mediaPlayer = null
            audioPlayer = null
        }
    }

    /** Pauses a VLC-Android player and gives its input/vout threads a bounded chance to quiesce. */
    private fun pauseAndroidPlayerAndAwait(player: Pointer) {
        runCatching { LibVlc.lib.libvlc_media_player_set_pause(player, 1) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750)
        while (System.nanoTime() < deadline) {
            val state = runCatching { LibVlc.lib.libvlc_media_player_get_state(player) }.getOrDefault(-1)
            if (state != LibVlc.LIBVLC_STATE_PLAYING) return
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        logger.debug("$debugLabel Android player did not report PAUSED before replacement; keeping it retired.")
    }

    /**
     * Stops the session and releases resources. GPU teardown is deferred to the render thread.
     *
     * Teardown ordering is critical on Android: VLC-Android registers a `pthread_key` whose TLS
     * destructor (`jni_detach_thread`, `modules/video_output/android/utils.c`) runs when ANY VLC
     * native worker thread exits, and it dereferences thread-local state. This AAR's
     * `libvlc_media_player_stop` does NOT join every worker thread (OpenSL ES aout, MediaCodec
     * decoder, vout display threads can still be winding down when stop returns) — a worker
     * thread exiting at ANY later moment dereferences freed state and dies with SIGSEGV at
     * `libvlc.so+0xef7418` inside `pthread_key_clean_all` (observed on plain world exit AND on
     * DASH audio stream end, even with stop serialised and never released). On Android active teardown
     * must PAUSE the players instead of stopping them, but this cannot prevent workers from exiting at
     * natural EOF. The Android loader establishes VLC's JNI TLS key before JNA's callback key, and
     * callback threads are explicitly kept attached, so EOF teardown observes the JVM attachment in
     * the safe order; the OS reclaims paused players at process exit. Desktop has no such TLS
     * destructor and keeps serialised stop -> release.
     */
    fun cleanup() {
        markStopped(parked = false, releasing = true)
        val (mp, ap) = synchronized(lifecycleLock) {
            val oldVideo = mediaPlayer
            val oldAudio = audioPlayer
            deactivateVideoCallbacks(oldVideo)
            mediaPlayer = null
            audioPlayer = null
            oldVideo to oldAudio
        }
        // On desktop, stop must run serialised on the control executor and release MUST run only
        // after stop has completed. On Android the players are only paused (never stopped) and
        // kept alive. Add cleanup players to the same process-lifetime roots as URL-switch players;
        // otherwise DisplayMediaController can release the owning manager while libvlc still has
        // delayed callbacks into its direct buffers/JNA trampolines.
        if (systemAudio) {
            deactivateVideoCallbacks(mp)
            if (mp != null) retiredAndroidPlayers += mp
            if (ap != null) retiredAndroidPlayers += ap
            synchronized(ANDROID_KEEP_ALIVE) {
                if (!ANDROID_KEEP_ALIVE.contains(this)) ANDROID_KEEP_ALIVE += this
            }
        }
        val done = CountDownLatch(1)
        try {
            controlExecutor.execute {
                try {
                    if (mp != null) {
                        stopOrPause(mp)
                        if (!systemAudio) {
                            runCatching { LibVlc.lib.libvlc_media_player_release(mp) }
                        }
                    }
                    if (ap != null) {
                        stopOrPause(ap)
                        if (!systemAudio) {
                            runCatching { LibVlc.lib.libvlc_media_player_release(ap) }
                        }
                    }
                } finally {
                    done.countDown()
                }
            }
        } catch (_: RejectedExecutionException) {
            done.countDown()
        }
        runCatching { done.await(15, TimeUnit.SECONDS) }
        runCatching { controlExecutor.shutdownNow() }
        audioOutput?.close()
        surface.clear()
        renderExecutor.execute { surface.cleanup() }
    }

    // ── Quality switch ──────────────────────────────────────────────────────

    /** Hard quality switch: stop current media and start the new rendition. */
    fun beginQualitySwitch(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int) {
        stop()
        start(streamSet, offsetNanos, lastQuality)
    }

    fun promoteIncoming(): Boolean = true // hard switch already promoted

    // ── Audio track switch ──────────────────────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    fun setWarmAudioTracks(tracks: List<WarmTrack>) {
        // Not needed with libvlc.
    }

    // ── Park / suspend / resume ─────────────────────────────────────────────

    fun canPark(): Boolean = true

    private fun canHoldWarm(): Boolean = true

    fun suspend(allowExternalProcess: Boolean = false, retainBuffered: Boolean = false): Boolean {
        val mp = mediaPlayer ?: return false
        parkFlag.set(true)
        // Parked: the last displayed frame stays on screen naturally; release any pending seek
        // freeze so a later resume is never gated on a seek that was superseded while parked.
        seekFrozen = false
        // Persist the authoritative libvlc position (get_time, ms) rather than the wall-clock
        // estimate, so the progress bar resumes exactly where the stream was paused.
        parkPositionNanos = currentPacingNanos().takeIf { it >= 0 } ?: clock.currentTime()
        submit {
            runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 1) }
            val ap = audioPlayer
            if (ap != null) {
                runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 1) }
            }
            // Gate the PCM feed explicitly. libvlc's own aout pause callback must not control this:
            // it also fires while the input rebuffers, and a missing resume muted the session for
            // good (see LibVlcAudioOutput.onPause).
            audioOutput?.setPaused(true)
        }
        return true
    }

    fun resume() {
        val mp = mediaPlayer ?: return
        parkFlag.set(false)
        submit {
            runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 0) }
            val ap = audioPlayer
            if (ap != null) {
                runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 0) }
            }
            audioOutput?.setPaused(false)
        }
    }

    fun isParked(): Boolean = parkFlag.get()

    fun parkedPositionNanos(): Long? = parkPositionNanos.takeIf { parkFlag.get() && it >= 0 }

    /**
     * Repositions the frozen anchor of a parked session after an in-place seek. A warm-paused or
     * parked session reads its position back through [parkedPositionNanos] (UI progress, resume
     * position, [resume] pacing fallback), so the anchor must follow the seek or the UI would show
     * the pre-seek position until the session is resumed. No-op when not parked.
     */
    fun repositionParked(nanos: Long) {
        if (parkFlag.get() && nanos >= 0) parkPositionNanos = nanos
    }

    // ── Audio helpers ────────────────────────────────────────────────────────

    /**
     * Replaces the selected audio rendition without changing the video on desktop. Android needs a
     * full session restart because switching between muxed and separate renditions changes whether
     * the video player itself must carry audio; its native players are never reused after media.
     */
    fun restartAudio(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean {
        if (released.get()) return false
        if (streamSet.currentAudio.url.isBlank()) return false
        // A cold-paused or EOS session has no native channel to replace. Keeping the new stream in
        // MediaPlayer is still a successful selection; the next start() will attach it.
        if (stopped.get()) return true
        if (systemAudio) {
            val wasPlaying = isPlaying
            val wasParked = parkFlag.get()
            return try {
                if (!start(streamSet, offsetNanos, lastQuality)) {
                    false
                } else {
                    if (!wasPlaying || wasParked) {
                        suspend(allowExternalProcess = true, retainBuffered = true)
                    }
                    true
                }
            } catch (t: Throwable) {
                logger.warn("$debugLabel failed to switch Android audio rendition.", t)
                false
            }
        }

        val result = AtomicBoolean(false)
        val done = CountDownLatch(1)
        try {
            controlExecutor.execute {
                try {
                    if (!released.get() && !stopped.get()) {
                        result.set(restartDesktopAudioOnControl(streamSet, offsetNanos))
                    }
                } finally {
                    done.countDown()
                }
            }
        } catch (_: RejectedExecutionException) {
            return false
        }
        return try {
            if (done.await(15, TimeUnit.SECONDS)) result.get() else false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Performs the desktop audio replacement on the serialized libvlc control executor. */
    private fun restartDesktopAudioOnControl(streamSet: ActiveStreams, offsetNanos: Long): Boolean {
        val ap = audioPlayer ?: return false
        val safeUrl = runCatching { MediaHostGuard.resolveSafeUrl(streamSet.currentAudio.url) }.getOrNull()
            ?: return false
        val media = runCatching {
            val options = mutableListOf(*LibVlcMediaOptions.forUrl(safeUrl), ":no-video")
            LibVlc.createMedia(safeUrl, options.toTypedArray())
        }.getOrNull() ?: return false
        return try {
            runCatching { audioOutput?.reset() }
            runCatching { LibVlc.lib.libvlc_media_player_stop(ap) }
            LibVlc.lib.libvlc_media_player_set_media(ap, media)
            if (offsetNanos > 0L) {
                runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, offsetNanos / 1_000_000L) }
            }
            LibVlc.lib.libvlc_media_player_play(ap)
            if (!isPlaying || parkFlag.get()) {
                runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 1) }
                audioOutput?.setPaused(true)
            } else {
                audioOutput?.setPaused(false)
            }
            true
        } catch (t: Throwable) {
            logger.warn("$debugLabel failed to switch audio rendition.", t)
            false
        } finally {
            runCatching { LibVlc.lib.libvlc_media_release(media) }
            scheduleInitialAvSync()
        }
    }

    fun captureAudioPcm(maxNanos: Long): ByteArray? = null

    fun captureVideoCacheSnapshot(): ByteArray? = null

    // ── Pacing / clock ──────────────────────────────────────────────────────

    fun currentPacingNanos(): Long {
        // libvlc is the authoritative player clock: get_time returns MILLISECONDS (×1e6 → nanos), and
        // it tracks the frames libvlc actually DISPLAYS. Do NOT use the Java Sound line position here:
        // the line's real internal ring buffer rounds the requested size up (often to seconds on some
        // hardware), so an audio-line-authoritative clock reported the video as lagging by that whole
        // buffer — first a stale-anchor 109:53:50 blow-up, then a steady multi-second skew. The line
        // position is still measured below for the sync diagnostic.
        val mp = mediaPlayer ?: return clock.currentTime()
        val ms = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
        // Rate-limited A/V sync health diagnostic (INFO, ~10 s): how far the audio still queued in the
        // line's ring buffer trails the video. libvlc's `get_time` is the display clock; the line
        // buffer latency is the gap between the displayed frame and what you hear. A small, steady
        // value (~the buffer size, a few hundred ms) means healthy sync; a value that keeps growing
        // is real A/V drift. (libvlc 3.0.21's audio-callback pts is a monotonic clock, not media time,
        // so we deliberately report buffer latency instead of an absolute line position.)
        val now = System.nanoTime()
        val last = lastSyncDiagNanos.get()
        if (now - last > 10_000_000_000L && lastSyncDiagNanos.compareAndSet(last, now)) {
            // Audio lead is measured as the line's buffer latency: written-frames minus emitted-frames.
            // Positive = video's newest delivered sample is still queued in the line (video renders
            // ahead of the audible audio — normal, small and steady). A large value means the audio
            // THREAD itself stalled (the line can't drain), which is the only condition we can safely
            // correct by flushing. A genuinely audible "audio ahead of a frozen picture" is a vout
            // frame-drop (Minecraft render hitch) and is NOT visible in this metric — libvlc drives
            // video delivery from its audio clock, so the fix for that is on the render side, not by
            // flushing the audio line (which would only cause an audible jump).
            // The whole diagnostic + auto-resync can be disabled for bisection (-Ddreamdisplayx.noAutoResync).
            if (!LibVlcDiagnostics.noAutoResync) {
                val lead = audioOutput?.leadNanos()
                if (lead != null && lead > AUTO_RESYNC_THRESHOLD_NANOS) {
                    val leadMs = lead / 1_000_000L
                    if (MediaPlayer.DEBUG) {
                        logger.debug(
                            "{} A/V drift: audio buffer {}ms behind video (>{}ms) — flushing audio to re-sync.",
                            debugLabel, leadMs, AUTO_RESYNC_THRESHOLD_NANOS / 1_000_000L
                        )
                    }
                    audioOutput?.forceResync()
                }
                // Two-player A/V sync: the audio player runs on its own clock (separate from the video
                // player's system clock). Compare their get_time values; if the audio player drifts
                // more than the threshold, snap it back to the video position. This keeps lip-sync
                // without the audio clock ever throttling the vout. set_time on the audio player is
                // cheap (audio-only, no vout to rebuild) and only fires on genuine sustained drift.
                // Skipped while a seek is settling: the video clock is untrustworthy until its
                // demuxer flush completes (see SEEK_SETTLE_WINDOW_NANOS).
                val ap = audioPlayer
                if (ap != null && !seekSettling()) {
                    val videoMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                    val audioMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(ap) }.getOrDefault(-1L)
                    if (videoMs >= 0 && audioMs >= 0) {
                        val drift = videoMs - audioMs
                        if (kotlin.math.abs(drift) > AUTO_RESYNC_THRESHOLD_NANOS / 1_000_000L) {
                            if (MediaPlayer.DEBUG) {
                                logger.debug(
                                    "{} A/V drift: audio player {}ms from video ({} vs {}ms) — snapping audio to video.",
                                    debugLabel, drift, audioMs, videoMs
                                )
                            }
                            runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, videoMs) }
                        }
                    }
                }
            }
        }
        return if (ms >= 0) ms * 1_000_000L else clock.currentTime()
    }

    fun activeBridgeEdgeNanos(): Long? = null

    // ── Control executor ─────────────────────────────────────────────────────

    private fun submit(r: () -> Unit) {
        if (released.get()) return
        try {
            controlExecutor.execute { if (!released.get()) r() }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun daemonThread(r: Runnable, name: String): Thread =
        Thread(r, name).also { it.isDaemon = true }

    // ── Target dimensions ────────────────────────────────────────────────────

    private fun targetDims(streamSet: ActiveStreams?, lastQuality: Int = 0): Pair<Int, Int> =
        MediaStreamSelector.targetDimensions(
            streamSet?.currentVideo,
            lastQuality,
            getTextureSize(),
        )

    /**
     * Triple-buffered video frame pool driven by the low-level libvlc video callbacks
     * (VideoPlayer's TextureRenderCallback model). libvlc writes into one of three direct
     * buffers; `display` marks the newest; the render thread copies it into the FrameSurface
     * and returns the buffer to the pool.
     */
    private inner class TextureRenderCallback {
        private val BUFFER_COUNT = 3
        private val buffers = arrayOfNulls<ByteBuffer>(BUFFER_COUNT)
        private val pointers = arrayOfNulls<Pointer>(BUFFER_COUNT)
        private val inUse = BooleanArray(BUFFER_COUNT)
        private val retiredBuffers = ArrayList<ByteBuffer>()

        private var dropBuffer: ByteBuffer? = null
        private var dropPointer: Pointer? = null
        private var frameWidth = 1
        private var frameHeight = 1
        private var framePitch = 32
        private var frameLines = 32
        private var bufferSize = 4
        @Volatile
        private var active = true
        private var formatReady = false
        private var formatGeneration = 0L
        private var nextWrite = 0
        private var writing = -1

        @Synchronized
        fun setup(opaque: com.sun.jna.ptr.PointerByReference?, chroma: Pointer?, width: Pointer?, height: Pointer?,
                  pitches: Pointer?, lines: Pointer?): Int {
            if (!active || width == null || height == null || chroma == null || pitches == null || lines == null) return 0
            val w = width.getInt(0)
            val h = height.getInt(0)
            if (w <= 0 || h <= 0 || w > 16384 || h > 16384) {
                logger.warn("$debugLabel rejected libvlc frame dimensions ${w}x$h")
                return 0
            }
            val pitch = align32(w * 4)
            val lineCount = align32(h)
            logger.info("$debugLabel libvlc video setup: {}x{} pitch={} lines={} (expected {}x{})", w, h, pitch, lineCount, expectedW, expectedH)
            // RV32 chroma (VideoPlayer model): single RGBA8888 plane, 32-byte aligned stride.
            chroma.write(0, RV32, 0, RV32.size)
            pitches.setInt(0, pitch)
            lines.setInt(0, lineCount)
            resize(w, h, pitch, lineCount)
            formatReady = true
            return 1
        }

        @Synchronized
        fun deactivate() {
            active = false
        }

        @Synchronized
        fun cleanup(opaque: Pointer?) {
            clear()
        }

        @Synchronized
        fun lock(opaque: Pointer?, planes: Pointer?): Pointer? {
            if (planes == null || !active || !formatReady) {
                ensureDropBuffer()
                if (planes != null) planes.setPointer(0, dropPointer!!)
                return DROP_TOKEN
            }
            if (buffers[0] == null || bufferSize <= 0) {
                ensureDropBuffer()
                planes.setPointer(0, dropPointer!!)
                return DROP_TOKEN
            }
            val index = acquireWriteBuffer()
            if (index < 0) {
                ensureDropBuffer()
                planes.setPointer(0, dropPointer!!)
                return DROP_TOKEN
            }
            inUse[index] = true
            writing = index
            // RV32: single RGBA8888 plane with the pitch supplied by setup().
            planes.setPointer(0, pointers[index]!!)
            return Pointer.createConstant(formatGeneration * BUFFER_COUNT + index + 1L)
        }

        @Synchronized
        fun unlock(opaque: Pointer?, picture: Pointer?, planes: Pointer?) {
        }

        @Synchronized
        fun display(opaque: Pointer?, picture: Pointer?) {
            if (picture == null) return
            val token = Pointer.nativeValue(picture)
            if (token == DROP_TOKEN_VALUE) return
            val encoded = token - 1L
            val generation = encoded / BUFFER_COUNT
            val index = (encoded % BUFFER_COUNT).toInt()
            if (generation != formatGeneration || index < 0 || index >= BUFFER_COUNT) return
            if (writing == index) writing = -1
            try {
                if (!active || !formatReady || released.get() || stopped.get() || buffers[index] == null) return
                // Publish the newest frame into the surface for the render thread.
                publishFrame(index)
            } finally {
                // libvlc has finished reading the picture after display returns. Never leave a slot
                // leased: a missing release would fill the three-slot pool and force permanent drops.
                inUse[index] = false
            }
        }

        private fun copyVisibleRows(sourceBuffer: ByteBuffer, target: ByteBuffer) {
            val source = sourceBuffer.duplicate().order(ByteOrder.nativeOrder())
            val rowBytes = frameWidth * 4
            for (row in 0 until frameHeight) {
                val start = row * framePitch
                source.clear().position(start).limit(start + rowBytes)
                target.put(source)
            }
        }

        private fun publishFrame(index: Int) {
            val buf = buffers[index] ?: return
            val ew = expectedW
            val eh = expectedH
            if (ew <= 0 || eh <= 0) return
            // Seek picture-freeze: while a seek is settling, hold the last displayed picture
            // instead of visibly continuing the old timeline ("another player"). Drop frames until
            // the video clock reaches the target, the fail-open deadline expires, or a newer seek /
            // stop / park releases the gate.
            if (seekFrozen) {
                if (System.nanoTime() >= seekFrozenDeadlineNanos) {
                    seekFrozen = false
                } else {
                    val mp = mediaPlayer
                    val clockMs = if (mp == null) -1L
                    else runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                    if (clockMs < 0L || kotlin.math.abs(clockMs - seekFrozenTargetMs) > SEEK_VERIFY_TOLERANCE_MS) {
                        return // stale pre-seek frame: keep the frozen picture
                    }
                    seekFrozen = false // video clock reached the target: resume publishing
                }
            }
            // DEBUG: count delivered frames for the FPS label (1s sliding window).
            val now = System.nanoTime()
            if (fpsWindowStartNanos == 0L) fpsWindowStartNanos = now
            fpsFrames++
            recordFrameGap()
            val elapsedNs = now - fpsWindowStartNanos
            if (elapsedNs >= 1_000_000_000L) {
                val fps = fpsFrames.toDouble() * 1_000_000_000.0 / elapsedNs
                fpsValue = if (fpsValue <= 0.0) fps else fpsValue * 0.5 + fps * 0.5
                fpsFrames = 0L
                fpsWindowStartNanos = now
            }
            try {
                val packedSourceSize = frameWidth * frameHeight * 4
                val frameSize = ew * eh * 4
                val scratch = rgbScratch?.takeIf { it.capacity() >= packedSourceSize }?.also { it.clear() }
                    ?: ByteBuffer.allocateDirect(packedSourceSize).also { rgbScratch = it }
                copyVisibleRows(buf, scratch)
                scratch.flip()

                val spare = surface.takeOrAllocate(frameSize)
                spare.clear()
                if (frameWidth == ew && frameHeight == eh) {
                    // libvlc writes each row at the negotiated aligned pitch; the render surface is
                    // tightly packed, so copy visible rows rather than the stride-padded allocation.
                    spare.put(scratch)
                } else {
                    fitFrame(scratch, frameWidth, frameHeight, spare, ew, eh)
                }
                spare.flip()

                if (parkFlag.get()) return

                // Popout / preview sinks (RV32 / BGRA8888 frames). Skippable via diagnostic switch.
                if (!LibVlcDiagnostics.noFrameSink) {
                    val sink = popoutSink ?: previewSink
                    if (sink != null) sink(spare, ew, eh, FramePixelFormat.BGRA32)
                }
                // Keep lighting independent from optional popout/preview diagnostics.
                ambientLightSink?.invoke(spare, ew, eh, FramePixelFormat.BGRA32)

                // Publish to the GPU surface for the render thread. Skippable via diagnostic switch.
                if (!LibVlcDiagnostics.noVideoPublish) {
                    // Stamped with the player's current media position: this frame becomes due at that time, so the
                    // render thread can hold it back for its slot instead of showing whatever arrived last.
                    val vlcPlayer = mediaPlayer
                    val framePtsNanos = if (vlcPlayer == null) FrameSurface.SHOW_NOW else {
                        runCatching { LibVlc.lib.libvlc_media_player_get_time(vlcPlayer) }
                            .getOrDefault(-1L)
                            .let { if (it >= 0L) it * 1_000_000L else FrameSurface.SHOW_NOW }
                    }
                    surface.publish(spare, frameSize, framePtsNanos)
                    noFrames.set(System.nanoTime())
                }

                if (!firstFrameFired) {
                    firstFrameFired = true
                    firstFrameLatch.countDown()
                    clock.markFirstFrame() // start the playback position clock on the first decoded frame
                    logger.info("$debugLabel first frame delivered {}x{} (libvlc, yuv={}).", ew, eh, gpuYuvActive)
                }
            } catch (t: Throwable) {
                if (MediaPlayer.DEBUG) logger.warn("$debugLabel frame publish: ${t.message}")
            }
        }

        private fun resize(w: Int, h: Int, pitch: Int, lines: Int) {
            // A new libvlc format gets a fresh generation. Retain the previous direct slices so a
            // delayed native callback cannot write into freed memory or collide with the new pool.
            for (old in buffers) old?.let { retiredBuffers += it }
            dropBuffer?.let { retiredBuffers += it }
            val size = Math.addExact(Math.multiplyExact(pitch, lines), VIDEO_BUFFER_PADDING)
            frameWidth = w
            frameHeight = h
            framePitch = pitch
            frameLines = lines
            bufferSize = size
            formatGeneration = (formatGeneration + 1L).coerceAtLeast(1L)
            for (i in 0 until BUFFER_COUNT) {
                val (buffer, pointer) = alignedDirectBuffer(size)
                buffers[i] = buffer
                pointers[i] = pointer
                inUse[i] = false
            }
            val (drop, pointer) = alignedDirectBuffer(size.coerceAtLeast(4))
            dropBuffer = drop
            dropPointer = pointer
            nextWrite = 0
            writing = -1
        }

        @Synchronized
        private fun clear() {
            // Keep the direct buffers alive! This is called from libvlc's format-cleanup callback on
            // pause/seek. If we nulled buffers[]/bufferSize here, a resume (or a next seek with the
            // SAME dimensions) would NOT re-run setup()/resize() — libvlc skips setup when the size
            // is unchanged — so every subsequent lock() would take the DROP_TOKEN path and every
            // display() would early-return, freezing the video on the last frame while audio plays
            // on. The next setup() creates a fresh format generation and retains the old slices.
            formatReady = false
            nextWrite = 0
            writing = -1
        }

        private fun acquireWriteBuffer(): Int {
            for (i in 0 until BUFFER_COUNT) {
                val index = (nextWrite + i) % BUFFER_COUNT
                if (!inUse[index] && index != writing) {
                    nextWrite = (index + 1) % BUFFER_COUNT
                    return index
                }
            }
            return -1
        }

        private fun alignedDirectBuffer(size: Int): Pair<ByteBuffer, Pointer> {
            val owner = ByteBuffer.allocateDirect(Math.addExact(size, 31)).order(ByteOrder.nativeOrder())
            val raw = Native.getDirectBufferPointer(owner)
            val address = Pointer.nativeValue(raw)
            require(address != 0L) { "libvlc vmem direct buffer has no native address" }
            val aligned = (address + 31L) and -32L
            val offset = (aligned - address).toInt()
            val view = owner.duplicate().order(ByteOrder.nativeOrder())
            view.position(offset).limit(offset + size)
            val slice = view.slice().order(ByteOrder.nativeOrder())
            val pointer = Native.getDirectBufferPointer(slice)
            require(Pointer.nativeValue(pointer) % 32L == 0L) { "libvlc vmem buffer is not 32-byte aligned" }
            return slice to pointer
        }

        private fun ensureDropBuffer() {
            // libvlc can call lock() after a cleanup (pause/seek/resume) has nulled the buffers; it
            // then hands the decoded frame to the pointer we return here. If we hand back a tiny
            // buffer (e.g. 4 bytes) libvlc writes a whole w*h*4 frame into it → massive heap
            // corruption (0xC0000374, surfacing on random threads) — the pause/resume crash that
            // persisted after the audio path was isolated. So the drop buffer must ALWAYS be at
            // least a full padded frame. frameWidth/frameHeight survive clear(), so they still hold
            // the last known dimensions here.
            // A decoder can race format setup with its first lock callback. Use the target texture
            // dimensions when the callback has not reported a real frame size yet; returning a tiny
            // drop buffer would let libvlc memcpy a full decoded frame past the direct allocation.
            val safeWidth = maxOf(frameWidth, expectedW, 1)
            val safeHeight = maxOf(frameHeight, expectedH, 1)
            val pitch = align32(safeWidth * 4)
            val lines = align32(safeHeight)
            val size = (pitch * lines + VIDEO_BUFFER_PADDING).coerceAtLeast(4)
            if (dropBuffer != null && dropPointer != null && dropBuffer!!.capacity() >= size) return
            dropBuffer?.let { retiredBuffers += it }
            val (drop, pointer) = alignedDirectBuffer(size)
            dropBuffer = drop
            dropPointer = pointer
        }
    }

    // ── First-frame latch ───────────────────────────────────────────────────

    private var firstFrameLatch = CountDownLatch(1)
    @Volatile
    private var firstFrameFired = false

    // ── Frame conversion helpers ────────────────────────────────────────────

    private var rgbScratch: ByteBuffer? = null

    /** Reusable buffer for the intermediate (fit-to-aspect) scale step of LETTERBOX/CROP. */
    private var fitScratch: ByteBuffer? = null

    /** Reusable zero-fill chunk for clearing letterbox/crop bars. */
    private var zeroChunk: ByteArray? = null

    /**
     * Fits a source RGBA frame into the display-sized [dst] buffer honouring the active [getStretchMode]:
     * STRETCH scales to fill exactly (may distort); LETTERBOX scales to fit keeping aspect and pads the
     * remainder black; CROP scales to cover keeping aspect and centers-crops the overflow.
     */
    private fun fitFrame(src: ByteBuffer, srcW: Int, srcH: Int, dst: ByteBuffer, dstW: Int, dstH: Int) {
        val mode = getStretchMode()
        if (mode == StretchMode.STRETCH) {
            resizeRgba(src, srcW, srcH, dst, dstW, dstH)
            dst.position(dstW * dstH * 4)
            return
        }
        val srcAspect = srcW.toDouble() / srcH
        val dstAspect = dstW.toDouble() / dstH
        val fitW: Int
        val fitH: Int
        if (mode == StretchMode.LETTERBOX) {
            // scale to fit inside
            val scale = if (srcAspect > dstAspect) dstW.toDouble() / srcW else dstH.toDouble() / srcH
            fitW = (srcW * scale).toInt().coerceAtLeast(1)
            fitH = (srcH * scale).toInt().coerceAtLeast(1)
        } else { // CROP: scale to cover
            val scale = if (srcAspect > dstAspect) dstH.toDouble() / srcH else dstW.toDouble() / srcW
            fitW = (srcW * scale).toInt().coerceAtLeast(1)
            fitH = (srcH * scale).toInt().coerceAtLeast(1)
        }
        // Scale the source into the fit-sized intermediate.
        val scratch = fitScratch?.takeIf { it.capacity() >= fitW * fitH * 4 }?.also { it.clear() }
            ?: ByteBuffer.allocateDirect(fitW * fitH * 4).also { fitScratch = it }
        resizeRgba(src, srcW, srcH, scratch, fitW, fitH)
        scratch.flip()

        // Clear the whole destination to black first (spare.clear() only resets position — it does
        // NOT zero the buffer, so letterbox bars would otherwise show stale pixels).
        dst.clear()
        fillZero(dst, dstW * dstH * 4)
        dst.clear()

        // Center the fit-sized frame, clipping to the destination bounds (CROP has negative offsets).
        val offX = (dstW - fitW) / 2
        val offY = (dstH - fitH) / 2
        val x0 = maxOf(0, offX)
        val x1 = minOf(dstW, offX + fitW)
        val y0 = maxOf(0, offY)
        val y1 = minOf(dstH, offY + fitH)
        for (dy in y0 until y1) {
            val sy = dy - offY
            val srcOff = sy * fitW * 4 + (x0 - offX) * 4
            val len = (x1 - x0) * 4
            scratch.position(srcOff)
            scratch.limit(srcOff + len)
            dst.position(dy * dstW * 4 + x0 * 4)
            dst.put(scratch)
        }
        // Leave position at the full frame size so the caller's flip() exposes every byte.
        dst.position(dstW * dstH * 4)
        scratch.rewind()
    }

    /** Fills [bytes] of [buf] with zeros from its current position (using a reusable chunk). */
    private fun fillZero(buf: ByteBuffer, bytes: Int) {
        if (zeroChunk == null) zeroChunk = ByteArray(8192)
        val chunk = zeroChunk!!
        var left = bytes
        while (left > 0) {
            val n = minOf(left, chunk.size)
            buf.put(chunk, 0, n)
            left -= n
        }
    }

    private fun resizeRgba(src: ByteBuffer, srcW: Int, srcH: Int, dst: ByteBuffer, dstW: Int, dstH: Int) {
        src.rewind()
        val srcRow = srcW * 4
        val dstRow = dstW * 4
        if (srcW == dstW) {
            // Same row width: bulk-copy whole rows (fast path, common when the texture only
            // differs in height from the decoded frame).
            for (dy in 0 until dstH) {
                val sy = (dy * srcH / dstH).coerceIn(0, srcH - 1)
                src.position(sy * srcRow).limit(sy * srcRow + srcRow)
                dst.put(src)
            }
            return
        }
        for (dy in 0 until dstH) {
            val sy = (dy * srcH / dstH).coerceIn(0, srcH - 1)
            for (dx in 0 until dstW) {
                val sx = (dx * srcW / dstW).coerceIn(0, srcW - 1)
                val p = sy * srcW * 4 + sx * 4
                dst.put(src.get(p)); dst.put(src.get(p + 1)); dst.put(src.get(p + 2)); dst.put(src.get(p + 3))
            }
        }
        src.rewind()
    }

    companion object {
        /**
         * Android libvlc players and their JNA callbacks cannot be released safely after media has
         * been attached. Keep the owning manager strongly reachable until process exit, including
         * when a display unloads and its MediaPlayer is otherwise eligible for GC.
         */
        private val ANDROID_KEEP_ALIVE = java.util.Collections.synchronizedList(
            mutableListOf<LibVlcSessionManager>(),
        )

        private const val LIBVLC_MEDIA_PLAYER_LENGTH_CHANGED = 0x111
        private const val REPLAY_FPS = 30.0

        /**
         * Nominal frame duration handed to [FrameSurface.enableDisplaySync]; the surface halves it (and caps the
         * result at 20 ms), so this is the widest tolerance the queue can use when picking a due frame.
         */
        private const val DISPLAY_SYNC_FRAME_NS = 40_000_000L

        /** Defensive tail (bytes) added beyond w*h*4 when allocating the RV32 video pool so a marginally
         * oversized libvlc write (alignment drift / transient seek frame) lands in slack instead of
         * corrupting adjacent heap memory. The reader only copies the exact w*h*4 span. */
        private const val VIDEO_BUFFER_PADDING = 4096
        private fun align32(value: Int): Int = (value + 31) and -32
        private val RV32 = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), '3'.code.toByte(), '2'.code.toByte())
        private val DROP_TOKEN = Pointer.createConstant(0x7FFFFFFFL)
        private val DROP_TOKEN_VALUE = 0x7FFFFFFFL
        private fun outputFps(sourceFps: Double?): Double =
            sourceFps?.takeIf { it.isFinite() && it > 1.0 && it <= 240.0 } ?: REPLAY_FPS
    }
}

/** Verdict of a seek-landing clock-sample pair. */
internal enum class SeekLanding { LANDED, DROPPED, FLUSHING }

/**
 * Classifies two video-clock samples (ms) taken roughly [SEEK_VERIFY_SAMPLE_GAP_NANOS] apart
 * against a seek [targetMs].
 *
 * - [SeekLanding.LANDED]: either sample is within [toleranceMs] of the target — the seek reached
 *   its destination and playback can resume normally.
 * - [SeekLanding.DROPPED]: the clock advanced at least [advanceMs] while still far from the target
 *   — the pre-seek timeline is STILL PLAYING (set_time was silently ignored), so re-applying
 *   set_time is warranted.
 * - [SeekLanding.FLUSHING]: the clock barely moved while far from the target — the demuxer is
 *   legitimately flushing toward the target; re-applying set_time would restart that flush and
 *   should be avoided.
 *
 * Negative samples (failed libvlc reads) are treated as [SeekLanding.FLUSHING] so they never
 * trigger a spurious re-assertion; a restart of the flush is the most expensive error mode.
 */
internal fun classifySeekLanding(
    firstMs: Long,
    secondMs: Long,
    targetMs: Long,
    toleranceMs: Long,
    advanceMs: Long,
): SeekLanding {
    if (firstMs < 0L || secondMs < 0L || targetMs < 0L) return SeekLanding.FLUSHING
    if (kotlin.math.abs(firstMs - targetMs) <= toleranceMs ||
        kotlin.math.abs(secondMs - targetMs) <= toleranceMs
    ) {
        return SeekLanding.LANDED
    }
    return if (secondMs - firstMs >= advanceMs) SeekLanding.DROPPED else SeekLanding.FLUSHING
}
