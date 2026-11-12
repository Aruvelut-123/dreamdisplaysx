package com.dreamdisplayx.media.player.session

import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.player.GpuTextureRef
import com.dreamdisplayx.media.player.managers.WarmTrack
import com.dreamdisplayx.media.player.stream.ActiveStreams
import java.nio.ByteBuffer

/**
 * One playing session of one display, from the perspective of [MediaPlayer].
 *
 * ## Why this exists
 *
 * The player used to be hard-wired to libvlc: `MediaPlayer` held a concrete
 * `LibVlcSessionManager` and called 37 members on it. libvlc is a complete
 * demux + decode + render + audio stack, so "the session" and "libvlc" happened to be the
 * same object — and the type name said so. That stops being true as soon as a second
 * backend exists, because an FFmpeg demux/decode pipeline shares none of libvlc's
 * machinery (no `libvlc_media_player`, no vmem callbacks, no JNA) yet has to honour the
 * same lifecycle: start, seek, switch quality, park, resume, stop, publish frames.
 *
 * So the contract is split out here, and every backend implements it:
 *
 *  - `LibVlcSession` — the existing libvlc implementation, kept as the working default.
 *  - `FfmpegSession` — the FFmpeg demux + MediaCodec pipeline (Android, opt-in).
 *
 * ## What a session does *not* own
 *
 * Audio stays with libvlc even when video comes from somewhere else. libvlc already knows
 * how to play an audio-only rendition (`:no-video`), and that path carries the Android
 * `AudioTrack` budget, the warm track pool and the park/resume bookkeeping. Rebuilding
 * that next to a second video backend would duplicate the riskiest code in the module for
 * no gain. Backends that do not do audio therefore implement the audio members as no-ops,
 * exactly as `LibVlcSession` does for the members it does not need.
 *
 * ## Threading
 *
 * Not thread-safe by construction; each implementation documents its own rules. The
 * render-thread half ([updateFrame], [updateFramePlanar], [textureFilled], [clearFrame],
 * and the three frame sinks) is always called from the render thread, as the per-method
 * docs state.
 */
internal interface PlaybackSession {

    // ── Identity ────────────────────────────────────────────────────────────

    /**
     * Short name of the backend, for logs and diagnostics (e.g. `"libvlc"`, `"ffmpeg"`).
     *
     * Deciding whether the session is "the libvlc one" must never require an `is LibVlcSession`
     * check at a call site: query the backend by name, or by a capability below.
     */
    val backendName: String

    // ── Frame delivery (render thread) ──────────────────────────────────────

    /** Returns true once a frame is available for upload or has already been uploaded to the GPU texture. */
    fun textureFilled(): Boolean

    /**
     * Uploads the ready frame to [texture] if one is available. Must be called from the render thread.
     * Returns true when a frame was actually uploaded (drives the dual-texture quality handoff).
     */
    fun updateFrame(texture: GpuTextureRef, actualW: Int, actualH: Int): Boolean

    /**
     * Uploads the ready planar frame into the three plane textures if one is available. Only
     * meaningful for backends producing planar output; no-op (false) otherwise.
     */
    fun updateFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, actualW: Int, actualH: Int): Boolean

    /** Discards any ready frame so the renderer will not show stale content after a timeline jump. */
    fun clearFrame()

    /** Raw-frame sink for the popout window. Called on the delivering thread. */
    var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?

    /** Raw-frame sink for the display-menu preview. Called on the delivering thread. */
    var previewFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?

    /** Raw-frame sink for the optional ambient-light sampler. Called on the delivering thread. */
    var ambientLightSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?

    // ── Quality handoff ─────────────────────────────────────────────────────

    /** True while a parallel quality switch is warming up the new resolution. */
    fun hasIncoming(): Boolean

    /** Uploads the latest incoming (quality-switch) frame to [texture]. Render thread only. */
    fun updateIncomingFrame(texture: GpuTextureRef, actualW: Int, actualH: Int): Boolean

    /** Uploads the latest incoming planar frame into the staged plane textures. Render thread only. */
    fun updateIncomingFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, actualW: Int, actualH: Int): Boolean

    /** Promotes the warmed-up quality-switch channel to live; returns false if it was already aborted. */
    fun promoteIncoming(): Boolean

    // ── Transport ───────────────────────────────────────────────────────────

    /** Starts playback of [streamSet] at [offsetNanos]. Returns false if the session could not start. */
    fun start(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean

    /** Seeks the session to [offsetNanos]. Returns false when the seek could not be issued. */
    fun beginSeek(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean

    /** Begins a quality switch: teardown plus restart at the new rendition. */
    fun beginQualitySwitch(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int)

    /** Stops playback but keeps the session reusable. */
    fun stop()

    /** Stops playback immediately, skipping any graceful teardown. */
    fun stopNow()

    /** Pauses immediately without tearing the session down. */
    fun pauseNow()

    /** Replaces only the audio rendition, leaving video untouched. */
    fun restartAudio(streamSet: ActiveStreams, offsetNanos: Long, lastQuality: Int): Boolean

    // ── Park / suspend / resume ─────────────────────────────────────────────

    /** Whether this session can be parked (stopped while keeping its position). */
    fun canPark(): Boolean

    /** Parks the session, releasing native resources while remembering the position. */
    fun suspend(allowExternalProcess: Boolean = false, retainBuffered: Boolean = false): Boolean

    /** Resumes a parked session. */
    fun resume()

    /** True while parked. */
    fun isParked(): Boolean

    /** Position to show while parked, or null when not parked / not known. */
    fun parkedPositionNanos(): Long?

    /** Moves the remembered park position without resuming. */
    fun repositionParked(nanos: Long)

    // ── Status ──────────────────────────────────────────────────────────────

    /** True while playback is advancing. */
    val isPlaying: Boolean

    /** Delivered video frames per second, smoothed. */
    val currentVideoFps: Double

    /** Frame-interval diagnostic (min/avg/max gap), or null when unavailable. */
    fun frameIntervalInfo(): String?

    /** A/V pacing reference in nanoseconds, used by the render loop. */
    fun currentPacingNanos(): Long

    /** Bridge-edge timestamp for replay integration, or null when not applicable. */
    fun activeBridgeEdgeNanos(): Long?

    /** Monotonic counter bumped on every delivered frame; the watchdog uses it to detect stalls. */
    val lastFrameNanos: java.util.concurrent.atomic.AtomicLong

    // ── Audio control ───────────────────────────────────────────────────────

    /** Applies [volume] (0–2.0) to the session's own audio output, if it has one. */
    fun setVolume(volume: Double)

    /** Re-reads and re-applies the volume, for backends whose mixer can drift. */
    fun verifyVolume()

    /** Pre-warms audio tracks so a later switch is instant. No-op for backends that do not need it. */
    fun setWarmAudioTracks(tracks: List<WarmTrack>)

    /** Captures decoded PCM for diagnostics/replay, or null when unsupported. */
    fun captureAudioPcm(maxNanos: Long): ByteArray?

    // ── Replay ──────────────────────────────────────────────────────────────

    /** Captures an encoded video snapshot for replay, or null when unsupported. */
    fun captureVideoCacheSnapshot(): ByteArray?

    /** Starts a replay of a captured video snapshot without audio. */
    fun startReplayVideoOnly(snapshot: ByteArray?, resume: Long, positionNanos: Long, audioPcm: ByteArray?): Boolean

    /** Attaches a live stream after a video-only replay. */
    fun attachLiveAfterReplay(streamSet: ActiveStreams, liveOffsetNanos: Long, lastQuality: Int): Boolean

    // ── Teardown ────────────────────────────────────────────────────────────

    /** Releases GPU resources. Must be called from the render thread on permanent shutdown. */
    fun cleanup()
}
