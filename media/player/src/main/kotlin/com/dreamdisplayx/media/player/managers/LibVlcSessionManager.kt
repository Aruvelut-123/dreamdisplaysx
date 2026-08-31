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
 *  - A single libvlc instance and a single media player are created ONCE for the whole
 *    session-manager lifetime and never rebuilt. Switching videos only replaces the media
 *    on the existing player (`set_media` + `play`), so no JNA callback trampoline is ever
 *    dropped while libvlc's async teardown could still touch it ("callback object has been
 *    garbage collected" spam is gone by construction).
 *  - Video is delivered through low-level lock/unlock/display/setup/cleanup callbacks into
 *    a triple-buffered pool (the VideoPlayer `TextureRenderCallback` model). Every callback
 *    is held by a strong field reference for the life of the manager.
 *  - All libvlc control operations run on a single control executor, serialised.
 *  - Playback events (playing/end-reached/error) are delivered through a low-level event
 *    listener, exactly like VideoPlayer.
 *  - Audio is left to libvlc's own default output (the `:input-slave` audio stream is merged
 *    into the same player), which removes the fragile Java PCM pipe that caused "audio fades
 *    after a few seconds". Volume is still controlled via libvlc.
 */
internal class LibVlcSessionManager(
    private val debugLabel: String,
    private val clock: PlaybackClock,
    private val events: PlayerEvents,
    private val terminated: AtomicBoolean,

    /** Returns the current GPU texture dimensions (width to height). */
    private val getTextureSize: () -> Pair<Int, Int>,
    private val getBrightness: () -> Double,
    private val getStretchMode: () -> StretchMode = { StretchMode.LETTERBOX },

    /** Invoked when the stream ends or errors. */
    private val onStreamEnd: (stderr: String, normalEos: Boolean) -> Unit,

    /** Invoked when quality switch fails before promotion. */
    private val onQualitySwitchAborted: (appliedAnyway: Boolean) -> Unit = {},

    /** Invoked once an in-flight audio track switch settles. */
    private val onAudioTrackSwitchSettled: () -> Unit = {},

    /** Runs render-thread (GL) cleanup work. */
    private val renderExecutor: RenderExecutor,

    /** Creates per-channel GPU frame uploaders. */
    private val uploaderFactory: FrameUploaderFactory,

    /** Whether the GPU-side planar (I420) render path is active. */
    private val gpuYuvActive: Boolean,

    /** Whether hardware-accelerated decoding is enabled by config. */
    private val useHwAccel: Boolean,

    /** Per-display acoustics DSP stage (unused with libvlc default audio output). */
    audioStage: AudioDspStage? = null,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/LibVlcSession")

    /**
     * Android mode: javax.sound does not exist on Android (no java.desktop), so the
     * Java Sound callback pipeline cannot run there — [LibVlcAudioOutput] is not even
     * instantiated, because its method signatures reference `SourceDataLine` and would
     * fail to link. The video player instead keeps its own audio track and libvlc feeds
     * the OpenSL ES output directly (`--aout=opensl` set in [LibVlc]; 3D positional
     * audio is unavailable on this platform). Every dedicated audio-player interaction
     * is gated on this flag, and all those call sites already null-check the player,
     * so skipping creation is safe.
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
     * alongside [mediaPlayer] on first use and never rebuilt. Audio callbacks are registered on
     * this player only; the video player has `:no-audio` and runs on the system clock.
     */
    @Volatile
    private var audioPlayer: Pointer? = null

    private val released = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    /** Serialises every libvlc control call, mirroring VideoPlayer's control executor. */
    private val controlExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> daemonThread(r, "MediaPlayer-vlc-ctrl") }

    // ── Frame surface ───────────────────────────────────────────────────────

    private val surface = FrameSurface(debugLabel, uploaderFactory, FramePixelFormat.BGRA32)

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

    var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = popoutSink
        set(value) { popoutSink = value }

    var previewFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = previewSink
        set(value) { previewSink = value }

    // ── Low-level callbacks (held strongly for life) ───────────────────────

    private val eventCallback = LibVlc.EventCallback { event, _ -> handleEvent(event) }
    /** Triple-buffered video frame pool + callbacks (VideoPlayer TextureRenderCallback model). */
    private val videoFrames = TextureRenderCallback()

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

    private val videoFormatCallback = LibVlc.VideoFormatCallback { opaque, chroma, width, height, pitches, lines ->
        videoFrames.setup(opaque, chroma, width, height, pitches, lines)
    }

    private val videoCleanupCallback = LibVlc.VideoCleanupCallback { opaque -> videoFrames.cleanup(opaque) }

    private val videoLockCallback = LibVlc.VideoLockCallback { opaque, planes -> videoFrames.lock(opaque, planes) }

    private val videoUnlockCallback = LibVlc.VideoUnlockCallback { opaque, picture, planes ->
        videoFrames.unlock(opaque, picture, planes)
    }

    private val videoDisplayCallback = LibVlc.VideoDisplayCallback { opaque, picture ->
        videoFrames.display(opaque, picture)
    }

    // ── Event handling ──────────────────────────────────────────────────────

    private fun handleEvent(event: Pointer?) {
        if (event == null || released.get() || stopped.get()) return
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
                logger.error("$debugLabel libvlc error: ${LibVlc.errmsg()}")
                errorMessage = "libvlc error"
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
        // Android (system audio): route the volume into libvlc's own output.
        if (systemAudio) {
            val ap = mediaPlayer ?: return
            runCatching { LibVlc.lib.libvlc_audio_set_volume(ap, (volume.coerceIn(0.0, 1.0) * 100).toInt()) }
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
     * Starts (or restarts) playback of [streamSet] on the single, never-rebuilt player.
     * Video is delivered via the low-level callbacks; audio is left to libvlc's default output.
     */
    fun start(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int) {
        val (w, h) = targetDims(streamSet, lastQuality)
        val safeUrl = MediaHostGuard.resolveSafeUrl(streamSet.currentVideo.url)

        // A restart replaces the media on the SAME player; never recreate the player.
        stopped.set(false)
        eosReached = false
        errorMessage = ""
        eosFired.set(false)
        parkFlag.set(false)
        expectedW = w
        expectedH = h
        firstFrameFired = false
        firstFrameLatch = CountDownLatch(1)
        // Reset the playback clock to the requested offset so the progress bar starts at the
        // right second instead of inheriting a stale origin from the previous session.
        clock.reset(offsetNanos)
        // Reset the audio output for this new session (re-prime the 3D DSP chain, flush the line).
        runCatching { audioOutput?.reset() }

        // Build the media options (UA + referer + optional hw decode). Audio is played by a SEPARATE
        // libvlc player (see below) so its Java Sound `write()` blocking never throttles the video
        // vout. The video media gets `:no-audio`: with no audio track the video player's master clock
        // is the system clock, so the vout delivers at the full source frame rate instead of being
        // dragged to ~60% by an audio clock that is pulsed by line writes. Hardware decode is enabled
        // BOTH here (media-level) and at instance-level (--avcodec-hw=any); VLC copies the GPU-decoded
        // frame back to system memory before handing it to the vmem lock callback, so vmem and hw
        // coexist and 4K H.264/HEVC decodes on the GPU instead of starving the CPU.
        val mediaOptions = mutableListOf(*LibVlcMediaOptions.forUrl(safeUrl))
        // Media-level hw decode is an avcodec-module concept (desktop backends only); on
        // Android the decoder module is chosen at instance level (--codec=mediacodec_*) and
        // avcodec stays the software fallback.
        if (!systemAudio) LibVlc.configuredHwBackend()?.let { mediaOptions.add(":avcodec-hw=$it") }
        if (systemAudio) {
            // Android: audio stays inside the video player (OpenSL ES); no callback pipeline,
            // no dedicated audio player. Note the media option keeps audio OFF the callbacks —
            // it is ":no-audio" on desktop because a separate player owns sound there.
            mediaOptions.remove(":no-audio")
        } else {
            mediaOptions.add(":no-audio")
        }
        val audioUrl = streamSet.currentAudio.url
        if (audioUrl.isNotBlank() && !audioUrl.equals(safeUrl, ignoreCase = true)) {
            logger.info("$debugLabel audio will be played by the separate audio player.")
        } else {
            logger.warn("$debugLabel audio NOT started: audioUrl='{}' equalsVideo={}.",
                audioUrl.ifBlank { "<blank>" }, audioUrl.equals(safeUrl, ignoreCase = true))
        }

        submit {
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
                runCatching { LibVlc.lib.libvlc_media_player_stop(mp) }
                val media = LibVlc.createMedia(safeUrl, mediaOptions.toTypedArray())
                LibVlc.lib.libvlc_media_player_set_media(mp, media)
                LibVlc.lib.libvlc_media_release(media) // the player holds its own reference
                // Start the SEPARATE audio player with the DASH audio URL (:no-video so no vout is
                // built; audio callbacks on it feed the Java Sound line). Skipped when there is no
                // separate audio URL (e.g. the merged audio was already in the video container).
                val ap = audioPlayer()
                if (ap != null && audioUrl.isNotBlank() && !audioUrl.equals(safeUrl, ignoreCase = true)) {
                    runCatching { LibVlc.lib.libvlc_media_player_stop(ap) }
                    val audioOptions = mutableListOf(*LibVlcMediaOptions.forUrl(audioUrl))
                    audioOptions.add(":no-video")
                    val audioMedia = LibVlc.createMedia(audioUrl, audioOptions.toTypedArray())
                    LibVlc.lib.libvlc_media_player_set_media(ap, audioMedia)
                    LibVlc.lib.libvlc_media_release(audioMedia)
                    LibVlc.lib.libvlc_media_player_play(ap)
                } else {
                    logger.info("$debugLabel audio player skipped (no separate audio URL).")
                }
                LibVlc.lib.libvlc_media_player_play(mp)
                isPlaying = true
                // Initial A/V sync: both players start independently, so the audio player's clock can
                // drift from the video's right from the start. Schedule an early correction (~1.5s)
                // rather than waiting for the 10s auto-resync.
                scheduleInitialAvSync()
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
                val mp = mediaPlayer ?: return@submit
                runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, offsetNanos / 1_000_000L) }
                val ap = audioPlayer
                if (ap != null) {
                    runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, offsetNanos / 1_000_000L) }
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
    }

    /**
     * Returns the single libvlc media player, creating it on first use and never rebuilding it.
     * This is the VIDEO player: it only registers video callbacks (audio callbacks live on the
     * separate [audioPlayer]), and its media gets `:no-audio` so it runs on the system clock —
     * the audio player's Java Sound `write()` blocking can no longer throttle the vout.
     */
    private fun player(): Pointer? {
        val existing = mediaPlayer
        if (existing != null) return existing
        return try {
            LibVlc.ensureLoaded()
            val lib = LibVlc.lib
            val mp = lib.libvlc_media_player_new(LibVlc.libvlcInstance)
            if (mp == null) {
                logger.error("$debugLabel libvlc_media_player_new returned null: ${LibVlc.errmsg()}")
                return null
            }
            // Video callbacks (once; held strongly). Skippable via diagnostic switch for bisection.
            if (!LibVlcDiagnostics.noVideoCallback) {
                lib.libvlc_video_set_format_callbacks(mp, videoFormatCallback, videoCleanupCallback)
                lib.libvlc_video_set_callbacks(mp, videoLockCallback, videoUnlockCallback, videoDisplayCallback, null)
            }
            // Events (once). The video player drives the session state (playing/end/error).
            val em = lib.libvlc_media_player_event_manager(mp)
            if (em != null) {
                for (e in MEDIA_PLAYER_EVENTS) {
                    lib.libvlc_event_attach(em, e, eventCallback, null)
                }
            }
            mediaPlayer = mp
            if (MediaPlayer.DEBUG) logger.debug("$debugLabel created single libvlc video player.")
            mp
        } catch (t: Throwable) {
            logger.error("$debugLabel failed to create libvlc media player: ${t.message}")
            null
        }
    }

    /**
     * Returns the dedicated AUDIO-only libvlc media player, creating it on first use and never
     * rebuilding it. It only registers the audio callbacks that feed the 3D DSP + Java Sound line
     * ([LibVlcAudioOutput]); its media gets `:no-video`. No events are attached: its END_REACHED
     * (Bilibili DASH audio slaves run shorter than the video) must NOT trigger the session EOS.
     * Skipped entirely when [LibVlcDiagnostics.noAudioCallback] is set (bisection).
     */
    private fun audioPlayer(): Pointer? {
        if (LibVlcDiagnostics.noAudioCallback) return null
        // Android: no callback audio pipeline — libvlc's OpenSL ES output owns sound.
        if (systemAudio) return null
        val existing = audioPlayer
        if (existing != null) return existing
        return try {
            LibVlc.ensureLoaded()
            val lib = LibVlc.lib
            val ap = lib.libvlc_media_player_new(LibVlc.libvlcInstance)
            if (ap == null) {
                logger.error("$debugLabel libvlc audio player new returned null: ${LibVlc.errmsg()}")
                return null
            }
            // Audio callbacks (once; held strongly) feed the 3D DSP + Java Sound line. The line is
            // pre-opened here so the first PCM block has a destination (also primes the default format).
            openAudioLine()
            lib.libvlc_audio_set_format_callbacks(ap, audioFormatSetupCallback, audioFormatCleanupCallback)
            lib.libvlc_audio_set_callbacks(ap, audioPlayCallback, audioPauseCallback,
                audioResumeCallback, audioFlushCallback, audioDrainCallback, null)
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
        submit {
            val mp = mediaPlayer ?: return@submit
            // ENDED state ignores set_time and a bare play() is not guaranteed to restart in libvlc
            // 3.0 — stop() first, then play() restarts from the beginning (loop/replay path).
            val state = try { LibVlc.lib.libvlc_media_player_get_state(mp) } catch (_: Throwable) { -1 }
            if (state == LibVlc.LIBVLC_STATE_ENDED) {
                try {
                    LibVlc.lib.libvlc_media_player_stop(mp)
                    LibVlc.lib.libvlc_media_player_play(mp)
                    // The audio player must be restarted too: its own stream also reached ENDED
                    // (Bilibili DASH audio is often shorter than the video), so a bare set_time on
                    // an ENDED audio player does nothing — video replays but audio stays silent.
                    val ap = audioPlayer
                    if (ap != null) {
                        runCatching { LibVlc.lib.libvlc_media_player_stop(ap) }
                        runCatching { LibVlc.lib.libvlc_media_player_play(ap) }
                        runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, offsetNanos / 1_000_000L) }
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
                runCatching { LibVlc.lib.libvlc_media_player_set_time(mp, offsetNanos / 1_000_000L) }
                // Seek the audio player to the same position (non-ENDED path).
                val ap = audioPlayer
                if (ap != null) {
                    runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, offsetNanos / 1_000_000L) }
                }
            }
            // If a seek left the player in a dead state (stopped/ended — e.g. a backwards seek
            // into an already-released region dropping it out of PLAYING), resume it. Buffering(2)
            // is a normal transient after a backwards seek and MUST NOT be play()ed — that would
            // interrupt the seek and freeze the picture (the "backwards seek sometimes sticks" bug).
            try {
                val after = LibVlc.lib.libvlc_media_player_get_state(mp)
                if (after == LibVlc.LIBVLC_STATE_STOPPED || after == LibVlc.LIBVLC_STATE_ENDED) {
                    LibVlc.lib.libvlc_media_player_play(mp)
                }
            } catch (_: Throwable) { }
        }
        logger.debug("$debugLabel libvlc seek to ${offsetNanos / 1_000_000} ms.")
        return true
    }

    /**
     * Schedules a one-shot A/V correction ~1.5s after playback starts. The video and audio players
     * start independently, so their clocks drift apart immediately; the 10s auto-resync in
     * [currentPacingNanos] would leave the audio audibly off-sync for the first ~10s. This snaps the
     * audio player to the video `get_time` early, once both have settled into playback. Best-effort:
     * if either player isn't ready yet, the next 10s auto-resync still corrects the drift.
     */
    private fun scheduleInitialAvSync() {
        if (LibVlcDiagnostics.noAutoResync) return
        Thread {
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
                return@Thread
            }
            submit {
                val mp = mediaPlayer
                val ap = audioPlayer
                if (mp != null && ap != null && !stopped.get() && !parkFlag.get()) {
                    val videoMs = runCatching { LibVlc.lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                    if (videoMs >= 0) {
                        runCatching { LibVlc.lib.libvlc_media_player_set_time(ap, videoMs) }
                        logger.debug("$debugLabel initial A/V sync: audio player snapped to {} ms.", videoMs)
                    }
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Stops the session. The single player is kept alive (never released) so its JNA callbacks
     * stay valid across restarts. Call [cleanup] to release the player and all resources.
     */
    fun stop() {
        isPlaying = false
        parkFlag.set(false)
        eosReached = true
        stopped.set(true)
        submit {
            val mp = mediaPlayer
            if (mp != null) {
                runCatching { LibVlc.lib.libvlc_media_player_stop(mp) }
            }
            val ap = audioPlayer
            if (ap != null) {
                runCatching { LibVlc.lib.libvlc_media_player_stop(ap) }
            }
        }
        surface.clear()
    }

    /**
     * Releases the single player and all resources. GPU teardown is deferred to the render thread.
     *
     * Teardown ordering is critical on Android: `libvlc_media_player_release` must NEVER run while
     * VLC's own worker threads are still alive. Each VLC worker thread carries thread-local state
     * (the `jni_key` TLS slot) that is cleaned by `pthread_key_clean_all` → the
     * `jni_detach_thread` TLS destructor when that thread exits. If the player object was already
     * freed, the destructor dereferences the dangling value and crashes — observed SIGSEGV at
     * `libvlc.so+0xef7418` inside `pthread_key_clean_all` immediately after the first frame was
     * delivered. The previous code called the ASYNC [stop] (whose queued task a following
     * `shutdownNow()` could cancel before it ever ran) and then released synchronously, so the
     * release routinely beat the stop. Here we stop synchronously first — `libvlc_media_player_stop`
     * blocks until the input layer and its worker threads have exited — and only then release.
     */
    fun cleanup() {
        isPlaying = false
        parkFlag.set(false)
        eosReached = true
        stopped.set(true)
        released.set(true)
        val mp = mediaPlayer
        mediaPlayer = null
        val ap = audioPlayer
        audioPlayer = null
        // Stop -> release must run serialised on the control executor and COMPLETE before this
        // method returns: VLC's worker threads carry thread-local state that is torn down on exit
        // (pthread_key_clean_all -> the jni_detach_thread TLS destructor on Android), and releasing
        // the player while those threads are still alive frees the very object the destructor is
        // about to dereference -> SIGSEGV (observed at libvlc.so+0xef7418 in pthread_key_clean_all,
        // right after the first frame was delivered). The old code submitted the stop
        // asynchronously, then released synchronously right after — so the release routinely beat
        // the stop (and shutdownNow() could cancel the queued stop entirely). Awaiting the paired
        // stop+release on the control executor removes both races.
        val done = CountDownLatch(1)
        try {
            controlExecutor.execute {
                try {
                    if (mp != null) {
                        runCatching { LibVlc.lib.libvlc_media_player_stop(mp) }
                        runCatching { LibVlc.lib.libvlc_media_player_release(mp) }
                    }
                    if (ap != null) {
                        runCatching { LibVlc.lib.libvlc_media_player_stop(ap) }
                        runCatching { LibVlc.lib.libvlc_media_player_release(ap) }
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

    /** Hard quality switch: stop current media, start new one on the same player. */
    fun beginQualitySwitch(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int) {
        stop()
        start(streamSet, offsetNanos, lastQuality)
    }

    fun promoteIncoming(): Boolean = true // hard switch already promoted

    // ── Audio track switch ──────────────────────────────────────────────────

    fun beginAudioTrackSwitch(streamSet: ActiveStreams): Boolean {
        // libvlc manages audio tracks internally; a restart of the same player re-reads the
        // stream and picks up the new audio URL. No-op here.
        return false
    }

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
        // Persist the authoritative libvlc position (get_time, ms) rather than the wall-clock
        // estimate, so the progress bar resumes exactly where the stream was paused.
        parkPositionNanos = currentPacingNanos().takeIf { it >= 0 } ?: clock.currentTime()
        submit {
            runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 1) }
            val ap = audioPlayer
            if (ap != null) {
                runCatching { LibVlc.lib.libvlc_media_player_set_pause(ap, 1) }
            }
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
        }
    }

    fun isParked(): Boolean = parkFlag.get()

    fun parkedPositionNanos(): Long? = parkPositionNanos.takeIf { parkFlag.get() && it >= 0 }

    // ── Audio helpers (unused with libvlc default output) ───────────────────

    @Suppress("UNUSED_PARAMETER")
    fun restartAudio(streamSet: ActiveStreams, offsetNanos: Long): Boolean = false

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
                val ap = audioPlayer
                if (ap != null) {
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

    private fun targetDims(streamSet: ActiveStreams?, lastQuality: Int = 0): Pair<Int, Int> {
        val q = when {
            lastQuality > 0 -> lastQuality
            streamSet != null -> MediaStreamSelector.parseQuality(streamSet.currentVideo)
            else -> 0
        }
        if (q <= 0) return 854 to 480
        return MediaStreamSelector.qualityToDims(q).let { it[0] to it[1] }
    }

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

        private var dropBuffer: ByteBuffer? = null
        private var dropPointer: Pointer? = null
        private var frameWidth = 1
        private var frameHeight = 1
        private var bufferSize = 4
        private var nextWrite = 0
        private var writing = -1
        private var latest = -1

        @Synchronized
        fun setup(opaque: com.sun.jna.ptr.PointerByReference?, chroma: Pointer?, width: Pointer?, height: Pointer?,
                  pitches: Pointer?, lines: Pointer?): Int {
            if (width == null || height == null || chroma == null || pitches == null || lines == null) return 0
            val w = width.getInt(0)
            val h = height.getInt(0)
            if (w <= 0 || h <= 0 || w > 16384 || h > 16384) {
                logger.warn("$debugLabel rejected libvlc frame dimensions ${w}x$h")
                return 0
            }
            logger.info("$debugLabel libvlc video setup: {}x{} (expected {}x{})", w, h, expectedW, expectedH)
            // RV32 chroma (VideoPlayer model): single RGBA8888 plane, 4 bytes/px.
            chroma.write(0, RV32, 0, RV32.size)
            pitches.setInt(0, w * 4)
            lines.setInt(0, h)
            if (frameWidth != w || frameHeight != h) resize(w, h)
            return 1
        }

        @Synchronized
        fun cleanup(opaque: Pointer?) {
            clear()
        }

        @Synchronized
        fun lock(opaque: Pointer?, planes: Pointer?): Pointer? {
            if (planes == null) return DROP_TOKEN
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
            writing = index
            // RV32: single RGBA8888 plane.
            planes.setPointer(0, pointers[index]!!)
            return Pointer.createConstant((index + 1).toLong())
        }

        @Synchronized
        fun unlock(opaque: Pointer?, picture: Pointer?, planes: Pointer?) {
        }

        @Synchronized
        fun display(opaque: Pointer?, picture: Pointer?) {
            if (picture == null) return
            val token = Pointer.nativeValue(picture)
            if (token == DROP_TOKEN_VALUE) return
            val index = (token - 1).toInt()
            if (index < 0 || index >= BUFFER_COUNT) return
            if (writing == index) writing = -1
            if (released.get() || stopped.get() || buffers[index] == null) return
            latest = index
            // Publish the newest frame into the surface for the render thread.
            publishFrame(index)
        }

        private fun publishFrame(index: Int) {
            val buf = buffers[index] ?: return
            val ew = expectedW
            val eh = expectedH
            if (ew <= 0 || eh <= 0) return
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
                val rgba = buf.duplicate().order(ByteOrder.nativeOrder()); rgba.rewind()
                val total = frameWidth * frameHeight * 4
                val frameSize = ew * eh * 4

                var spare = surface.takeOrAllocate(frameSize)
                spare.clear()

                if (frameWidth == ew && frameHeight == eh) {
                    // Direct copy: libvlc already produced RV32 (RGBA8888) — no colour conversion.
                    // Bulk copy: one range PUT instead of per-byte loops (up to 33MB on 4K).
                    rgba.limit(rgba.position() + total)
                    spare.put(rgba)
                } else {
                    val scratch = rgbScratch?.takeIf { it.capacity() >= frameWidth * frameHeight * 4 }?.also { it.clear() }
                        ?: ByteBuffer.allocateDirect(frameWidth * frameHeight * 4).also { rgbScratch = it }
                    rgba.limit(rgba.position() + frameWidth * frameHeight * 4)
                    scratch.put(rgba)
                    scratch.flip()
                    fitFrame(scratch, frameWidth, frameHeight, spare, ew, eh)
                }
                applyBrightness(spare, frameSize, getBrightness())
                spare.flip()

                if (parkFlag.get()) return

                // Popout / preview sinks (RV32 / BGRA8888 frames). Skippable via diagnostic switch.
                if (!LibVlcDiagnostics.noFrameSink) {
                    val sink = popoutSink ?: previewSink
                    if (sink != null) sink(spare, ew, eh, FramePixelFormat.BGRA32)
                }

                // Publish to the GPU surface for the render thread. Skippable via diagnostic switch.
                if (!LibVlcDiagnostics.noVideoPublish) {
                    surface.publish(spare, frameSize)
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

        private fun matches(w: Int, h: Int) = buffers[0] != null && frameWidth == w && frameHeight == h

        private fun resize(w: Int, h: Int) {
            // Reuse the pool when the size is unchanged: the pool is registered with libvlc via
            // vmem callbacks and the previous media's frame callbacks may still be in flight during
            // a reload — replacing the direct buffers under them is exactly the native
            // ACCESS_VIOLATION (0xC0000005) crash seen when reloading a video. Only reallocate when
            // this frame needs more room than the pool already has (grow-only, never shrink).
            if (buffers[0] != null && frameWidth == w && frameHeight == h) return
            frameWidth = w
            frameHeight = h
            // RV32 is a single RGBA8888 plane at 4 B/px. Add a generous tail of padding so a slightly
            // oversized libvlc write (alignment drift, a transient odd row count right after a seek,
            // or libvlc feeding pitch beyond our declared w*4) lands inside slack rather than writing
            // past the end of this direct buffer and corrupting adjacent heap memory — the heap- /
            // native-corruption crash (0xC0000374/ntdll, often reported on an unrelated JIT thread)
            // that followed a seek. The reader only ever copies the exact w*h*4 span, so the slack is
            // purely defensive.
            bufferSize = w * h * 4
            val padded = bufferSize + VIDEO_BUFFER_PADDING
            if (buffers[0] == null || buffers[0]!!.capacity() < padded) {
                for (i in 0 until BUFFER_COUNT) {
                    buffers[i] = ByteBuffer.allocateDirect(padded).order(ByteOrder.nativeOrder())
                    pointers[i] = com.sun.jna.Native.getDirectBufferPointer(buffers[i]!!)
                    inUse[i] = false
                }
                dropBuffer = ByteBuffer.allocateDirect(padded.coerceAtLeast(4)).order(ByteOrder.nativeOrder())
                dropPointer = com.sun.jna.Native.getDirectBufferPointer(dropBuffer!!)
            }
            nextWrite = 0
            writing = -1
            latest = -1
        }

        @Synchronized
        private fun clear() {
            // Keep the direct buffers alive! This is called from libvlc's format-cleanup callback on
            // pause/seek. If we nulled buffers[]/bufferSize here, a resume (or a next seek with the
            // SAME dimensions) would NOT re-run setup()/resize() — libvlc skips setup when the size
            // is unchanged — so every subsequent lock() would take the DROP_TOKEN path and every
            // display() would early-return, freezing the video on the last frame while audio plays
            // on. We only reset the internal ring state; the next setup() still reallocates grow-only
            // if a larger frame needs it.
            for (i in 0 until BUFFER_COUNT) {
                inUse[i] = false
            }
            nextWrite = 0
            writing = -1
            latest = -1
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

        private fun ensureDropBuffer() {
            // libvlc can call lock() after a cleanup (pause/seek/resume) has nulled the buffers; it
            // then hands the decoded frame to the pointer we return here. If we hand back a tiny
            // buffer (e.g. 4 bytes) libvlc writes a whole w*h*4 frame into it → massive heap
            // corruption (0xC0000374, surfacing on random threads) — the pause/resume crash that
            // persisted after the audio path was isolated. So the drop buffer must ALWAYS be at
            // least a full padded frame. frameWidth/frameHeight survive clear(), so they still hold
            // the last known dimensions here.
            val size = ((frameWidth * frameHeight * 4) + VIDEO_BUFFER_PADDING).coerceAtLeast(4)
            if (dropBuffer != null && dropPointer != null && dropBuffer!!.capacity() >= size) return
            dropBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            dropPointer = com.sun.jna.Native.getDirectBufferPointer(dropBuffer!!)
        }
    }

    // ── First-frame latch ───────────────────────────────────────────────────

    private var firstFrameLatch = CountDownLatch(1)
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

    private fun applyBrightness(buf: ByteBuffer, size: Int, brightness: Double) {
        val factor = brightness.coerceIn(0.0, 2.0)
        if (factor == 1.0) return
        buf.flip()
        var i = 0
        while (i + 3 < size) {
            val r = ((buf.get(i).toInt() and 0xFF) * factor).toInt().coerceIn(0, 255)
            val g = ((buf.get(i + 1).toInt() and 0xFF) * factor).toInt().coerceIn(0, 255)
            val b = ((buf.get(i + 2).toInt() and 0xFF) * factor).toInt().coerceIn(0, 255)
            buf.put(i, r.toByte()); buf.put(i + 1, g.toByte()); buf.put(i + 2, b.toByte())
            // keep alpha (i+3) untouched
            i += 4
        }
    }

    companion object {
        private const val LIBVLC_MEDIA_PLAYER_LENGTH_CHANGED = 0x111
        private const val REPLAY_FPS = 30.0

        /** Defensive tail (bytes) added beyond w*h*4 when allocating the RV32 video pool so a marginally
         * oversized libvlc write (alignment drift / transient seek frame) lands in slack instead of
         * corrupting adjacent heap memory. The reader only copies the exact w*h*4 span. */
        private const val VIDEO_BUFFER_PADDING = 4096
        private val RV32 = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), '3'.code.toByte(), '2'.code.toByte())
        private val DROP_TOKEN = Pointer.createConstant(0x7FFFFFFFL)
        private val DROP_TOKEN_VALUE = 0x7FFFFFFFL
        private fun outputFps(sourceFps: Double?): Double =
            sourceFps?.takeIf { it.isFinite() && it > 1.0 && it <= 240.0 } ?: REPLAY_FPS
    }
}
