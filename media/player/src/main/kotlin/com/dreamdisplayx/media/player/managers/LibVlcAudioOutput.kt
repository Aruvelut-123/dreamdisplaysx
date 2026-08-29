package com.dreamdisplayx.media.player.managers

import com.dreamdisplayx.api.media.audio.service.AudioDspStage
import com.dreamdisplayx.media.player.util.MediaBufferEffects
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine

/**
 * Owns the audio output for the libvlc player. libvlc is configured with audio callbacks
 * (`libvlc_audio_set_callbacks` + `libvlc_audio_set_format_callbacks`) so decoded PCM is delivered
 * to us instead of being played through libvlc's default output. We feed it through the per-display
 * [AudioDspStage] (3D spatialisation: direction-aware panning / binaural, occlusion, reverb) and
 * write the result to a Java Sound [SourceDataLine] on our own clock. This restores the directional
 * 3D audio (left/right panning that libvlc's flat stereo output lost), occlusion (blocking the
 * display mutes/muffles the sound) and lets the display control its own audio pacing instead of
 * libvlc stopping the audio a couple of seconds before the video ends.
 *
 * The line is shared across session restarts (the single libvlc player is never rebuilt): [reset]
 * runs at the start of each playback session, [setVolume] updates the gain libvlc would otherwise
 * have applied, [close] releases the line for good.
 */
internal class LibVlcAudioOutput(
    private val debugLabel: String,
    private val dspStage: AudioDspStage?,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/LibVlcAudio")

    companion object {
        /** The PCM format negotiated with libvlc and used by every session: 44.1 kHz stereo S16 (native endianness). */
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_FRAME = CHANNELS * BYTES_PER_SAMPLE

        /** Nanoseconds per decoded frame; the blip of one stereo S16 sample pair. */
        private const val FRAME_NANOS = 1_000_000_000L / SAMPLE_RATE

        /** Upper bound on frames per audio callback (~0.25 s). A post-seek/pause pathological count that is
         * actually backed by a smaller native sample region must not make us read further than libvlc intends —
         * over-reading native memory was the stack-buffer-overrun crash (0xC0000409) on seek/pause. */
        private const val MAX_COUNT_PER_BLOCK = SAMPLE_RATE / 4

        /**
         * Line buffer bytes (~0.1 s of stereo S16). Tight enough that the constant video-ahead (libvlc
         * paces video from its delivery clock while the sound leaves the speakers only after this ring
         * drains — the lead equals the buffer, baked into the architecture with no public way to inject
         * the real playback position) is barely perceptible, yet large enough that a seek / stream
         * restart doesn't race the tiny ring into a native stack-buffer-overrun (0xC0000409). Backpressure
         * (`SourceDataLine.write` blocks when the ring is full) still caps drift at the buffer, and the
         * A/V auto-resync threshold in the session manager flushes queued audio on real drift. This is a
         * safety pull-back from a 45 ms trial that crashed on seek; if 0.1 s proves stable, it can go
         * lower again, and if game hitches underrun (stutter + stalls) it can come back up.
         *
         * Tunable via -Ddreamdisplayx.audioBufferMs=<ms> (default 100). A LARGER buffer is safer (the
         * 45ms trial crashed) and can smooth out write-blocking that would otherwise pulse the libvlc
         * audio clock — relevant if the video FPS is pinned at ~60% of the source frame rate while the
         * CPU is idle (audio-clock throttling theory).
         */
        private val LINE_BUFFER_BYTES: Int =
            (SAMPLE_RATE * BYTES_PER_FRAME * bufferMs() / 1000L).toInt().coerceAtLeast(4410)

        /** Buffer size in ms for the Java Sound line; override with -Ddreamdisplayx.audioBufferMs. */
        private fun bufferMs(): Int {
            val v = System.getProperty("dreamdisplayx.audioBufferMs")?.trim()
            val parsed = v?.toIntOrNull()?.coerceIn(20, 1000) ?: 100
            return parsed
        }
    }

    // ── State ────────────────────────────────────────────────────────────────

    @Volatile
    private var line: SourceDataLine? = null

    /**
     * Serialises every access to the underlying [SourceDataLine] so the libvlc audio thread (play
     * write / pause stop / resume start) and the render thread ([bufferedNanos] / [forceResync] / flush)
     * never touch it concurrently. Java Sound methods are individually thread-safe, but the JNA boundary
     * and stop-vs-write-vs-flush interleavings on a tiny ring underran the native path when the two
     * threads raced on pause/resume (EXCEPTION_ACCESS_VIOLATION in jvm.dll). Taking the lock around each
     * operation keeps the calls serialised and the crash out.
     */
    private val lineLock = Any()

    /** Effective volume (user volume × distance attenuation), 0..~2. */
    @Volatile
    private var gain = 1.0

    /** Cached video-vs-audio lead in nanos, written only on the libvlc audio thread, read from any thread. */
    @Volatile
    private var cachedLeadNanos: Long? = null

    /** Set by the render thread via [forceResync], consumed (the actual flush) on the libvlc audio thread. */
    @Volatile
    private var resyncPending = false

    /** True while paused. The line itself is NEVER stopped/started — those Java Sound native calls raced on
     * pause/resume and crashed (0xC0000409 / 0xC0000005, no JVM log). Instead we keep the line running
     * forever and simply drop samples here when paused; the line drains silently and picks up on resume. */
    @Volatile
    private var paused = false

    /** Reusable PCM buffer, read on the libvlc audio thread only (play callbacks are serialised). */
    private var pcmBuffer = ByteArray(0)

    // ── A/V sync clock ───────────────────────────────────────────────────────
    //
    // After the audio split, libvlc's own clock advances as samples are *delivered* to our play
    // callback, but the sound only reaches the speakers after the line's ring buffer drains. The
    // authoritative position is therefore computed from the line's REAL playback cursor each time:
    // the media time of the newest sample we've written, minus the frames still sitting in the line
    // buffer (frames written but not yet emitted). This self-heals on every audio block — no
    // long-lived anchor to go stale — so seeks, restarts and live streams can't make the position
    // extrapolate wildly (which a single anchor did, reading the line's cumulative counter as if it
    // were media time and jumping to ~100 hours on a long-running game).

    /** Cumulative frames written to the line since it opened (or since the last flush). */
    @Volatile
    private var totalWrittenFrames = 0L

    /** True once at least one audio block has been written since the last flush. */
    @Volatile
    private var clockLive = false

    // ── Control ──────────────────────────────────────────────────────────────

    /** Resets per-session state (called at the start of every playback). MUST NOT touch the line: this runs
     * on the control thread, and touching the `SourceDataLine` here while the libvlc audio thread is
     * mid-callback was a cross-thread native race (heap corruption, 0xC0000374) on pause/resume/restart.
     * libvlc itself flushes the audio pipeline around a restart ([onFlush] runs on the audio thread), so
     * here we only clear our own flags, clock and cache. */
    fun reset() {
        runCatching { dspStage?.reset() }
        clockLive = false
        paused = false
        resyncPending = false
        cachedLeadNanos = null
        totalWrittenFrames = 0L
    }

    /**
     * Lightweight seek-time state reset. Called from the control thread when a seek is set up; must NOT
     * touch the line (the libvlc audio thread may be mid-callback and the line is that thread's alone).
     * libvlc itself flushes the audio pipeline around a seek ([onFlush]), so here we only clear our own
     * flags and cache so a pause-then-resume immediately after a seek starts from a clean slate.
     */
    fun onSeekReset() {
        paused = false
        resyncPending = false
        clockLive = false
        cachedLeadNanos = null
    }

    /** Updates the gain applied to the PCM (user volume × distance attenuation). */
    fun setVolume(volume: Double) {
        gain = volume.coerceIn(0.0, 2.0)
    }

    /** Releases the audio line permanently. */
    fun close() {
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
    }

    // ── libvlc format callback (called on the libvlc audio thread) ───────────

    /**
     * Tells libvlc which PCM format we want: S16 (native endianness) at 44.1 kHz stereo, matching
     * the DSP chain and the old JavaCPP AudioSink. Returns 0 on success.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onFormatSetup(data: com.sun.jna.ptr.PointerByReference?, format: Pointer?, rate: Pointer?, channels: Pointer?): Int {
        return try {
            format?.setString(0, "S16N")
            rate?.setInt(0, SAMPLE_RATE)
            channels?.setInt(0, CHANNELS)
            0
        } catch (t: Throwable) {
            logger.warn("$debugLabel audio format setup failed: ${t.message}")
            -1
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onFormatCleanup(data: Pointer?) {
        // Nothing to release; the line is owned separately and closed with [close].
    }

    // ── libvlc audio callbacks (called on the libvlc audio thread) ────────────

    /**
     * Receives [samples] (count per-channel frames of S16 PCM), runs it through the 3D DSP stage
     * and writes it to the line. Blocking on the line write naturally paces libvlc's audio thread,
     * which is the audio clock our video rendering follows. Also re-anchors the A/V clock: the
     * first sample of this block (libvlc audio-callback pts, in µs) corresponds to the line's frame
     * counter as it stands, so [playedPositionNanos] can map line position -> media position.
     */
    fun onPlay(data: Pointer?, samples: Pointer?, count: Int, pts: Long) {
        if (samples == null || count <= 0) return
        val ln = line ?: return
        // Silent-mode diagnostic switch: with -Ddreamdisplayx.silentAudio=true the line is never created
        // and every block is dropped here, so we can bisect whether the pause/resume heap corruption is
        // in the audio path or elsewhere (video / libvlc itself).
        if (LibVlcDiagnostics.silentAudio) return
        // Track only how many frames we've written vs. how many the line has emitted; the ring-buffer
        // delta is the A/V lead (see [bufferedNanos]). We deliberately ignore `pts`: on libvlc 3.0.21
        // the custom-audio-callback pts is a system monotonic clock (~uptime) rather than media time,
        // so anchoring a media position on it produced ~100-hour readings.
        // Clamp count to a sane ceiling (~1 s of frames). libvlc can hand us a pathological count right
        // after a seek/flush while its internal state settles; reading count*4 bytes past a smaller
        // native sample buffer was a stack-buffer-overrun crash (0xC0000409) on seek. Bail out instead.
        if (count > MAX_COUNT_PER_BLOCK) {
            logger.warn("$debugLabel audio block too large ({} frames) — dropping to avoid a native overrun.", count)
            return
        }
        val countL = count.toLong()
        totalWrittenFrames += countL
        clockLive = true
        // While paused, do not touch the line at all (see [onPause]) — drop the samples and, crucially,
        // do NOT accumulate them into [totalWrittenFrames] (they were never handed to the line, so the
        // written-vs-emitted delta would balloon and desync the A/V clock after seek→pause→resume). The
        // line keeps running and drains to silence; resume flows again.
        if (paused) {
            totalWrittenFrames -= countL
            return
        }
        val bytes = count * BYTES_PER_FRAME
        if (pcmBuffer.size < bytes) pcmBuffer = ByteArray(bytes)
        samples.read(0, pcmBuffer, 0, bytes)
        // If the render thread asked for a re-sync, drain the residue BEFORE writing this block so the
        // audible audio snaps back to the video clock. This must run on the audio thread (the line's
        // owner) — never from the render thread, which would race the line and corrupt the heap.
        if (resyncPending) {
            resyncPending = false
            synchronized(lineLock) {
                runCatching { ln.flush() }
                totalWrittenFrames = runCatching { ln.getLongFramePosition() ?: 0L }.getOrDefault(0L)
            }
            clockLive = false
            logger.warn("$debugLabel audio flushed on audio thread to re-sync A/V.")
        }
        feed(pcmBuffer, bytes, ln)
        // Refresh the A/V lead cache on the audio thread (the line's owner); the render thread reads
        // only this cached value so the native line is never touched cross-thread (heap-corruption risk).
        updateLeadCache()
    }

    /**
     * Signed video-vs-audio lead in nanos, cached on the libvlc audio thread. Positive = video ahead of
     * the audible audio (samples queued but not yet heard), negative = the audible audio has already
     * played past the newest delivered sample (video rendering fell behind). Updated inside [onPlay] /
     * [onFlush] — the single thread that owns the line — so the render thread never touches the
     * `SourceDataLine` (cross-thread `getLongFramePosition` on Java Sound's Windows native layer was
     * the heap-corruption crash, 0xC0000374). Returns `null` until audio has been delivered at least once.
     */
    fun leadNanos(): Long? = cachedLeadNanos

    /**
     * How far the audio still queued in the line trails the video, in nanos (video-leading-audible).
     * See [leadNanos]; non-negative view of the cached value.
     */
    fun bufferedNanos(): Long? = cachedLeadNanos?.coerceAtLeast(0L)

    /**
     * Cache the current lead from [totalWrittenFrames] against the line's emitted-frame counter. MUST be
     * called on the libvlc audio thread (from [onPlay] / [onFlush]); the render thread reads only the
     * cached [cachedLeadNanos] field and never the line itself.
     */
    private fun updateLeadCache() {
        val ln = line ?: run { cachedLeadNanos = null; return }
        val emitted = ln.getLongFramePosition()
        cachedLeadNanos = (totalWrittenFrames - emitted) * FRAME_NANOS
    }

    /**
     * Best-effort audio playback position. libvlc's audio-callback `pts` is *not* trustworthy media time
     * on 3.0.21 (it reads as a monotonic clock), so this reconstructs the position from the delivered
     * playback clock instead: whatever the caller supplies. Returning the line buffer latency via
     * [bufferedNanos] is the preferred, unit-clean signal for tuning; this helper is kept for callers
     * that need an absolute position and can pass a trusted reference (e.g. libvlc `get_time`).
     */
    fun playedPositionNanos(referenceNanos: Long): Long? {
        val buf = bufferedNanos() ?: return null
        return (referenceNanos - buf).coerceAtLeast(0L)
    }

    private fun feed(buf: ByteArray, bytes: Int, ln: SourceDataLine) {
        try {
            val g = gain
            if (dspStage != null) {
                dspStage.process(buf, bytes, g)
            } else {
                MediaBufferEffects.applyVolumeS16LE(buf, bytes, g)
            }
            // Serialise the write against the render thread's flush / position reads and the pause /
            // resume stop / start (see [lineLock]) so the tiny ring never underruns into a native crash.
            synchronized(lineLock) {
                var written = 0
                while (written < bytes) {
                    val n = ln.write(buf, written, bytes - written)
                    if (n <= 0) return
                    written += n
                }
            }
        } catch (t: Throwable) {
            // Never throw into the JNA callback trampoline; just drop the block.
            logger.warn("$debugLabel audio write dropped: ${t.message}")
        }
    }

    /** Marks the output paused. Does NOT touch the line: calling `SourceDataLine.stop()` on pause (and
     * `start()` on resume) from the libvlc audio thread was a native crash (0xC0000409 / 0xC0000005, no
     * JVM log) — the stop/start round-trip on Java Sound's Windows layer corrupted its internal state.
     * The line is instead kept running permanently; [onPlay] drops samples while [paused] is true, so
     * the line drains to silence and resumes naturally. */
    @Suppress("UNUSED_PARAMETER")
    fun onPause(data: Pointer?, pts: Long) {
        paused = true
        resyncPending = false
    }

    @Suppress("UNUSED_PARAMETER")
    fun onResume(data: Pointer?, pts: Long) {
        paused = false
        clockLive = false
    }

    /** Discards buffered PCM (seek / stop): pretend only the already-emitted frames exist. Does NOT touch
     * the line — `SourceDataLine.flush()` / `getLongFramePosition()` on the native layer raced the
     * audio thread's write and was the heap-corruption crash (0xC0000374) on pause/resume. The line is
     * kept running and the stale buffered PCM is simply overwritten by the next [onPlay] blocks. */
    @Suppress("UNUSED_PARAMETER")
    fun onFlush(data: Pointer?, pts: Long) {
        clockLive = false
        // totalWrittenFrames is intentionally NOT reset to getLongFramePosition() here — that would
        // touch the line cross-callback and risk the native race. The next block written by [onPlay]
        // will re-anchor the clock naturally.
        resyncPending = false
        cachedLeadNanos = null
    }

    /** Drains buffered PCM (end of stream). Does NOT touch the line — same native race reason. */
    @Suppress("UNUSED_PARAMETER")
    fun onDrain(data: Pointer?) {
        // no-op: the line is intentionally kept running and never explicitly drained from callbacks.
        // DIAGNOSTIC: report how much audio was actually fed when the stream drained, so we can tell
        // whether the audio track is genuinely shorter than the video (Bilibili DASH audio slaves can
        // carry fewer fragments than the video master) vs. libvlc stopping audio output early.
        if (totalWrittenFrames > 0) {
            val audioMs = totalWrittenFrames * FRAME_NANOS / 1_000_000L
            logger.warn("$debugLabel audio drained (EOF): fed {} frames ≈ {} ms of audio.", totalWrittenFrames, audioMs)
        }
    }

    /**
     * How much audio has been handed to the line so far, in milliseconds. Diagnostic: compare against
     * the video length to tell whether the audio track simply ran out earlier (shorter DASH audio
     * slave) vs. libvlc stopping audio output while the video continues.
     */
    fun audioFeedMs(): Long = totalWrittenFrames * FRAME_NANOS / 1_000_000L


    /**
     * Requests an A/V re-sync. This only sets a marker and does NOT touch the line: it is called from the
     * render thread (the A/V diagnostic), which must never touch the Java Sound `SourceDataLine` — a
     * cross-thread `flush`/`getLongFramePosition` on Java Sound's Windows native layer was the
     * heap-corruption crash (0xC0000374). The actual `line.flush()` + clock re-anchor happen on the libvlc
     * audio thread inside [onPlay] (it owns the line), which drains the residue and snaps the audible
     * audio forward to the delivered clock — the auto-recovery half of A/V sync after a real drift.
     * Safe to call repeatedly; the marker is coalesced.
     */
    fun forceResync() {
        resyncPending = true
    }

    // ── Line lifecycle ───────────────────────────────────────────────────────

    /**
     * Opens (or reuses) the Java Sound line. Called from the control executor before playback
     * starts; a failure here only silences audio, video keeps working.
     */
    fun openLine() {
        if (LibVlcDiagnostics.silentAudio) return
        val existing = line
        if (existing != null && existing.isOpen) return
        val fmt = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE.toFloat(), 16, CHANNELS, BYTES_PER_FRAME, SAMPLE_RATE.toFloat(), false,
        )
        val info = DataLine.Info(SourceDataLine::class.java, fmt)
        if (!AudioSystem.isLineSupported(info)) {
            logger.warn("$debugLabel PCM line not supported.")
            return
        }
        try {
            val newLine = AudioSystem.getLine(info) as SourceDataLine
            newLine.open(fmt, LINE_BUFFER_BYTES)
            newLine.start()
            line = newLine
            totalWrittenFrames = 0L
            clockLive = false
            resyncPending = false
            cachedLeadNanos = null
            logger.info("$debugLabel audio line opened ({} Hz, {} ch).", SAMPLE_RATE, CHANNELS)
        } catch (e: LineUnavailableException) {
            logger.warn("$debugLabel audio line unavailable: ${e.message}.")
        } catch (t: Throwable) {
            logger.warn("$debugLabel audio line open failed: ${t.message}")
        }
    }
}