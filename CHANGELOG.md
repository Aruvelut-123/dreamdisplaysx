# 1.9.4.1 Release

Based on Dream Displays [`45ab6f8`](https://github.com/arnodoelinger/dreamdisplays/commit/45ab6f8).

> **F3 debug overlay now shows the real hardware decoder (feat/libvlc)**
> — libvlc WAS decoding on the GPU (verbose log: "Using DXVA2 (AMD Radeon RX 6600...)") but the F3
> overlay kept showing "software". `libvlc_media_player_get_video_decoder_info` was only reading
> `psz_name` (the module name, e.g. "avcodec"); we now prefer `psz_description`, which carries the
> concrete backend (e.g. "H.264/AVC (DXVA2.0 by AMD)"), so the overlay reports the actual decoder.

> **Explicit per-OS hardware-decode backend (dxva2/vaapi/videotoolbox) instead of `--avcodec-hw=any` (feat/libvlc)**
> — the F3 debug overlay reported "software" because libvlc 3.0's `--avcodec-hw=any` can pick a
> surface backend it cannot copy back from into vmem, silently falling back to software decoding
> even though libvlc's avcodec module is the same FFmpeg that supports D3D11VA/DXVA2 copy-back.
>
> Now the backend is explicit per OS (Windows → `dxva2`, Linux → `vaapi`, Mac → `videotoolbox`) on
> both the instance and the media, with `-Ddreamdisplayx.hwDecode=<backend>` to override
> (e.g. `d3d11va`, `any`, or empty to disable). Added `-Ddreamdisplayx.verboseLibvlc=true` to make
> libvlc print its actual decoder/backend selection and any fallback reason.

> **Scrub preview now extracts frames on demand + A/V auto-resync is bidirectional (feat/libvlc)**
> — the scrub bar no longer pre-generates up to 45 sample frames across the whole video (which
> spawned libvlc players on the IO pool and caused game frame drops); instead each hover position
> that isn't already cached queues a single extraction for exactly that timestamp, coalesced so a
> fast drag never runs more than one extraction per video. The extraction info log was removed.
>
> A/V auto-resync now fires on BOTH directions: previously only video-ahead (audio behind) flushed
> the audio line; audio-ahead (audio playing past the newest delivered video sample) was assumed to
> self-resolve but did not — now `abs(lead) > threshold` flushes and re-anchors the clock either way.

> **Fix video freeze on resume after multiple seeks: cleanup no longer nulls the frame buffers (feat/libvlc)**
> — after the drop-buffer overflow fix, seek → seek → pause → resume no longer crashes, but the video
> froze on a stale frame while audio kept playing.
>
> Root cause: `clear()` (called from libvlc's format-cleanup on pause/seek) nulled `buffers[]` and set
> `bufferSize=0`. On resume with the SAME dimensions libvlc does NOT re-run `setup()`/`resize()`, so
> every subsequent `lock()` took the DROP_TOKEN path and every `display()` early-returned — the video
> never updated while the clock and audio continued.
>
> Fix: `clear()` now only resets the ring state (inUse/nextWrite/writing/latest) and keeps the direct
> buffers alive; `resize()` still reallocates grow-only when a larger frame arrives.

> **Fix the real pause/resume native crash: libvlc drop buffer was 4 bytes instead of a full frame (feat/libvlc)**
> — after `clear()` (called from video cleanup on pause/seek) set `bufferSize=0`, `ensureDropBuffer()`
> allocated only 4 bytes. libvlc then wrote a whole w×h×4 frame into a 4-byte direct buffer →
> massive heap overflow (0xC0000374, random-thread crash). The audio-path isolation was a
> red herring all along.
>
> Fix: `ensureDropBuffer()` now always allocates at least `frameWidth * frameHeight * 4 +
> VIDEO_BUFFER_PADDING` (the last known full frame size), so even after a cleanup the drop buffer
> can safely absorb a full frame write. `frameWidth`/`frameHeight` survive `clear()` because they
> are only reset by a new `setup()`.

> **Add JVM diagnostic switches to bisect the remaining pause/resume native crash (feat/libvlc)**
> — the heap corruption (0xC0000374) persists after all audio-callback line access was removed, so
> per-subsystem switches let us isolate which path corrupts the heap.
>
> Each switch is `-Ddreamdisplayx.<name>=true` and can be combined:
> - `silentAudio` — never open the line, drop all audio blocks (no Java Sound at all)
> - `noAudioCallback` — don't register libvlc's custom audio callbacks (libvlc handles audio itself)
> - `noVideoCallback` — don't register the custom video callbacks (libvlc uses its default vout)
> - `noFrameSink` — skip preview/popout frame sinks (no frame copy to the GUI)
> - `noVideoPublish` — skip the GPU surface publish (video frozen, audio only)
> - `noAutoResync` — disable the A/V diagnostic + auto-resync entirely
> - `noHardwareAccel` — don't pass `--avcodec-hw=any` to libvlc

> **libvlc audio callbacks no longer touch the line at all — fixes pause/resume heap corruption (feat/libvlc)**
> — pause/resume still crashed with 0xC0000374 even after stop/start removal.
>
> The remaining native race was `onFlush` / `onDrain` / `reset()` touching the `SourceDataLine`
> (`flush()` / `getLongFramePosition()` / `drain()`) while the audio thread's write was in flight.
> All three now avoid the line entirely: `reset()` only clears flags/clock, `onFlush` only clears the
> clock, and `onDrain` is a no-op. The line is written exclusively from the play callback and kept
> running forever (paused state drops samples). No callback ever calls a Java Sound native method, so
> the cross-callback native race — and the heap corruption — is gone.

> **Upgrade bundled libvlc 3.0.21 → 3.0.22 (feat/libvlc)**
> — trying the latest 3.0-series maintenance release to see whether its seek/pause native fix removes
> the remaining intermittent crash. `native/libvlc/build.sh`, `collect.sh` and the Build Natives workflow
> cache key all move to 3.0.22; the rebuilt natives are published to the new natives release
> (`natives-de826bd-1787899222`).

> **Seek→pause→resume no longer desyncs or crashes (feat/libvlc)**
> — the crash reproduced deterministically as seek → pause → resume.
>
> Two follow-ups to the no-stop pause change: while paused, dropped samples are no longer accumulated
> into the written-frame counter (they were never handed to the line, so the written-vs-emitted delta
> ballooned and the A/V clock desynced after seek→pause→resume), and a seek now resets the audio flags
> (pause/resync/clock) before libvlc's own flush so a pause immediately after seek starts clean. The
> seek-time reset deliberately does not touch the line — libvlc flushes the audio pipeline itself around
> a seek, and the line belongs to the audio thread.

> **Pause/resume no longer calls SourceDataLine.stop()/start() — fixes native crash (feat/libvlc)**
> — pause/resume crashed with 0xC0000409 / 0xC0000005 (no JVM log, native layer) on Windows.
>
> `SourceDataLine.stop()` on pause and `start()` on resume (called from the libvlc audio thread) raced
> Java Sound's Windows native layer and corrupted its internal state, crashing the JVM before a log was
> written. The line is now NEVER stopped or started: on pause we only set a flag (and, on play, drop the
> incoming samples so the line drains to silence), and resume just clears the flag. The line keeps
> running permanently, so the native stop/start round-trip — and the crash — are gone.

> **Narrow the audio callback read window to stop the pause/resume stack-buffer-overrun (feat/libvlc)**
> — pause/resume crashed with exit code -1073740791 (0xC0000409, stack buffer overrun) and no JVM log.
>
> The audio play callback now clamps the block size to ~0.25 s (down from ~1 s). A post-pause pathological
> `count` backed by a smaller native sample region would otherwise make us read further into native memory
> than libvlc intends, which was the stack-buffer-overrun on pause/resume. Combined with the earlier video
> pool padding (which stopped the seek-time heap corruption), the read window is now bounded on both paths.

> **Fix heap-corruption crash on pause/resume (render thread no longer touches the audio line) (feat/libvlc)**
> — the game crashed with exit code -1073740940 (0xC0000374, STATUS_HEAP_CORRUPTION) after pause/resume.
>
> **Render thread now never touches the Java Sound line.** The A/V diagnostic (render thread, every ~10 s)
> called `SourceDataLine.getLongFramePosition()` / `flush()` directly, racing the libvlc audio thread's
> write / stop / resume on the same line — a cross-thread access of Java Sound's Windows native layer
> that corrupted the heap (0xC0000374, exit -1073740940) on pause/resume. The lead is now cached on the
> libvlc audio thread inside the play callback (the line's owner) and read as a volatile field by the
> render thread; an A/V re-sync only sets a marker, and the actual `line.flush()` runs back on the audio
> thread. Every line operation (write, stop, start, flush, drain, position read) now happens on exactly
> one thread, so the native race — and the crash — is gone.

> **Fix seek crash (native stack-buffer-overrun) + safe audio buffer (feat/libvlc)**
> — a seek after the 45 ms buffer trial crashed the game with exit code -1073740791 (0xC0000409).
>
> **Seek crashed the JVM with a native stack-buffer-overrun** — 0xC0000409 (`STATUS_STACK_BUFFER_OVERRUN`)
> fired on seek. Two guards: the audio play callback now clamps `count` to a sane ceiling (~1 s of frames)
> and drops a pathological post-seek block instead of reading `count*4` bytes past a smaller native
> sample buffer, and the audio ring buffer is pulled back from a 45 ms trial to a safer ~0.1 s so a
> seek / stream restart no longer races the tiny ring into the native overrun. Backpressure still caps
> A/V drift at the buffer, and the ~0.1 s lead is barely perceptible.

> **Harder lip-sync + fix pause/resume JVM crash (feat/libvlc)**
> — the auto-resync at 45 ms flushed the audio almost every diagnostic and crashed the game on pause/resume.
>
> **Audio buffer tightened to ~45 ms** — the video is paced by libvlc's delivery clock while the sound
> leaves the speakers only after the Java Sound ring drains, so the video leads the audible audio by the
> buffer. Dropping the ring to 45 ms pulls the lips almost flush. The old 0.3 s buffer had the video a
> visible step ahead; at 45 ms the constant lead is near-imperceptible. If a ring this tight underruns on
> game hitches (stutter + stalls), the value is a single constant to raise back up.
>
> **Auto-RESYNC threshold pulled to 0.3 s and hardened with a line lock** — a 45 ms trial threshold
> fired the auto-resync on almost every A/V diagnostic, because the healthy lead already sits at the
> buffer. The constant cross-thread `line.flush()` from the render thread, racing the libvlc audio
> thread's write / stop / resume on the same `SourceDataLine`, crashed the JVM with an access violation
> (`jvm.dll`) on pause/resume. The threshold is back at 0.3 s (a real, sustained drift still recovers in
> a few seconds), and every line access — write, stop, start, flush, drain, position read — now takes a
> shared [lock] so the two threads are serialised and the native race is gone.

> **Auto-recovering bidirectional A/V sync + smaller audio buffer (feat/libvlc)**
> — the video ran a fixed step ahead of the lips and a real drift, once it happened, never pulled itself back.
>
> **The video always led the audible audio by the buffer size** — libvlc 3.0 paces the video from its own
> clock, which advances the instant a sample is handed to us, while the sound leaves the speakers only
> after the Java Sound ring drains. There is no public way to inject the real playback position (the
> clock callback is notification-only), so the lead is bounded — never drifting — but equal to the line
> buffer. The buffer is now ~0.15 s (was 0.3 s): lips stay together while the `SourceDataLine.write`
> backpressure still caps how far the video can run ahead.
>
> **A real drift didn't recover by itself** — the diagnostic now reports a signed lead (`audioLead=+Xms`
> video ahead / `-Xms` audio ahead) so both directions are visible, and auto-recovery replaces the old
> "wait and hope": if the video runs more than ~1 s ahead of the audible audio, the queued audio is
> flushed and the clock re-anchored, snapping the sound back to the picture instantly. Audio-ahead (the
> picture falls behind through a Minecraft hitch and the vout drops frames) self-resolves as the video
> catches up.

> **Z-fighting layering fix-up: lift before the flattening scale, dynamic scrub previews (feat/libvlc)**
> — the previous LETTERBOX depth-layering was inert, and long-film previews still stalled on one still.
>
> **Z-fighting was still visible** — the first layering attempt called `liftTowardViewer` *after*
> `applyScreenTransform`, i.e. inside the transform's `scale(w, h, 0)`, so the lift's z got multiplied by
> zero and the backdrop and video quads stayed exactly coplanar (still flickering). The video is now
> drawn through its own `drawLayer`, which lifts it *before* the transform (matching the loading
> placeholder), so the two LETTERBOX quads genuinely hold distinct depths. The whole screen also floats
> 0.05 blocks off the face (0.08 with a shader pack).
>
> **Audio still ran ahead of the lips** — the video is paced by libvlc's delivery clock while the sound
> only leaves the speakers after the Java Sound ring buffer drains, so the video leads the audible audio
> by roughly the buffer size. Bumping the buffer to 0.5 s to stop stutter left the lips ~0.5 s behind.
> The buffer is now ~0.3 s — still stable enough to absorb game hitches, small enough that mouth and
> voice stay together. The INFO diagnostic reports the lead directly ("video=Xms, audioBuffered=Yms"); a
> small steady Y is healthy, a growing one is real drift.
>
> **Scrub-preview hover stalled on one frame for long films** — sampling used a fixed 20 frames regardless
> of length, so a two-hour film had a still every ~6 minutes and hovering in between showed the nearest
> (often the opening) frame. Sample count now adapts to duration (up to 45, ~8 s apart), so hovering
> anywhere lands on a frame close to the cursor.
>
> **Z-fighting layering + A/V sync diagnostics (feat/libvlc)**
> — the display still flickered on AMD and the audio-clock diagnostic was reading a non-media clock.
>
> **Display still z-fought on the block** — the whole screen now floats a solid 0.05 blocks off the
> face (0.08 with a shader pack) so it clears the block even on drivers that ignore the pipeline's
> GPU polygon-offset bias. LETTERBOX also renders its black backdrop and the video quad on *separate*
> world-space depth layers (the video lifted one extra layer), exactly like the loading placeholder
> stacks its layers — so the two coplanar quads no longer z-fight over the bars.
>
> **A/V sync diagnostic read ~100 hours** — libvlc 3.0.21's custom-audio-callback `pts` is a monotonic
> clock (≈ uptime), not media time, so the old diagnostic's absolute line position blew up to ~111 h.
> The player clock correctly ignores it (libvlc `get_time` stays authoritative); the INFO diagnostic
> now reports the *line buffer latency* directly ("video=Xms, audioBuffered=Yms") — a small, steady Y
> is healthy sync, a growing one is real drift. The line ring buffer also grew to ~0.5 s so libvlc's
> audio thread (and the video it paces) stops stalling on tight underruns.
>
> **Audio-clock follow-ups: libvlc is the position clock again, and a bigger surface offset (feat/libvlc)**
> — the audio-line-authoritative clock still lagged the video by *seconds*, and the display still
> z-fought on AMD.
>
> **Timeline was still off by several seconds after the stale-anchor fix** — the remaining error
> was the audio line itself: `SourceDataLine.open(fmt, requestedBytes)` only *requests* a buffer
> size, and the audio hardware rounds it up (commonly to seconds), so a clock derived from the
> line's real playback position always lags the displayed frames by that whole internal buffer.
> libvlc's `get_time` tracks the frames libvlc actually *displays*, so it is the authoritative
> player clock again (as it was before the audio split). The line position is still measured and
> logged every ~10 s at INFO ("A/V sync: audioLine=…ms libvlc=…ms") so real drift is visible in
> the log while the buffer's constant offset no longer moves the timeline.
>
> **Display still z-fought on the block (flicker)** — the world-space LETTERBOX lift separated the
> video quad from its backdrop, but the whole display still sat only 0.008 blocks off the block
> face, relying on a GPU polygon-offset bias that some drivers (AMD/OpenGL) silently ignore.
> The surface offset is now 0.02 blocks (0.04 with shaders), clearing the block face even without
> the depth-bias trick — the same world-space lift the placeholder layers already used.
>
> **GPU-scale + audio-sync follow-ups: runaway player clock and LETTERBOX z-fighting (feat/libvlc)**
> — two regressions from the GPU-scaling / audio-sync work.
>
> **Player clock jumped to ~109:53:50 at the first second** — the A/V clock anchored once per
> segment to the Java Sound line's *cumulative* frame counter (which never resets across videos in
> one game session). If that anchor ever survived a session change (a missed flush), the position
> extrapolated from a long-dead anchor over the line's whole lifetime frame count → ~100 hours at
> the very first second. The clock now recomputes the position on every audio block from that
> block's own pts minus the frames still buffered in the line, so no stale anchor can persist —
> the position is always "newest written sample minus queued frames".
>
> **Display z-fighting on the block (flickering) in LETTERBOX mode** — the GPU-scaled renderer
> draws a black backdrop quad plus the video quad, both on the same block face. A vertex-level z
> offset was silently flattened by the display transform's `scale(width, height, 0)`, leaving the
> two quads coplanar → z-fighting where they overlap. The video quad's pose is now lifted toward the
> viewer in *world* space (before the z-flattening scale), the same trick the loading/error
> placeholder layers already used, so it cleanly clears the backdrop.
>
> **GPU-scaled video + re-synced audio clock (feat/libvlc)** — fixes the in-game ~10-20 fps after
> the libvlc port and re-locks the player clock to what the speakers actually emit.
>
> **In-game video dropped to ~10-20 fps** — the root cause was an expensive CPU per-pixel resize on
> the libvlc vout thread: the vmem callback CPU-scaled every delivered frame to the display-texture
> block size (e.g. 3840×2160 → 3600×2160 is ~7.78M pixels × 4 read + 4 write byte-ops ≈ 62M byte
> ops/frame, ~60-90 ms/frame). The texture is now allocated at the video's NATIVE aspect
> (`videoContentAspect` from the stream metadata), so `publishFrame` direct-copies the frame
> (srcW == dstW) and the GPU does the scaling when mapping the texture onto the block face. The
> texture is re-allocated to the native aspect before `play()` once the stream is resolved, and the
> renderer maps it to the block via per-stretch-mode UVs (STRETCH fills, LETTERBOX letterboxes with
> a solid-color backdrop, CROP centers with a UV sub-rect).
>
> **Audio drifted from the video after the split** — libvlc's own clock advances as samples are
> *delivered* to the play callback, which runs ahead of the Java Sound line (decoded PCM sits in the
> ring buffer before the speaker). The authoritative clock is now the line's real playback position
> (`SourceDataLine.getLongFramePosition` anchored to the audio callback's µs pts), so the progress
> bar, seek math and saved resume point stay glued to what the viewer actually hears. The line
> buffer is also halved (0.4 s → 0.2 s) so libvlc's delivery — and hence video pacing — can't
> outrun the audible audio by a perceptible margin.
>
> **Reload crash root cause, F3 decoder name and deterministic scrub frames (feat/libvlc)** —
> the follow-up fixes for the pause/resume crash, the "software decoder" F3 readout and the
> hover thumbnails still showing the opening frame.
>
> **Pause/resume still crashed (EXCEPTION_ACCESS_VIOLATION, no Java logs)** — the reload path
> called `libvlc_media_player_set_media` + `play()` directly on the still-playing player, so the
> old vout/aout threads raced the new format setup and faulted right after "libvlc video setup"
> (exit 0xC0000005, multiple threads erroring). `start()` now stops the previous media first
> (synchronous `libvlc_media_player_stop` on the control executor), giving libvlc a clean teardown
> before the new media is attached — the same single-player, never-rebuilt model VideoPlayer uses
> for restarts.
>
> **F3 always said "software decoder"** — the decoder-info JNA binding passed the struct by value,
> but `libvlc_media_player_get_video_decoder_info` takes pointer-to-pointer and libvlc allocates
> the result (which must be released with `libvlc_media_decoder_info_release`). The old call read
> garbage, always returned null, and the F3 overlay kept the "software" default. The binding now
> uses `PointerByReference` and correctly frees the libvlc-allocated structs, so the real decoder
> name (e.g. the avcodec module reporting GPU copy-back) can be shown.
>
> **Scrub previews still showed the opening frame everywhere** — seeking while playing is racy:
> `get_time` jumps to the target instantly while the vout keeps emitting stale pre-seek pictures,
> so a display latch was satisfied by those old frames. Extraction now pauses the player first
> (waiting for the Paused state), then seeks — libvlc renders the exact target frame into the
> next display callback, which is grabbed deterministically (the standard seek-while-paused
> technique). The grabber also switched from a single overwritten Y plane to a token-addressed
> buffer pool, so lock/display on different threads always copy the picture actually decoded.
>
> **Reload crash on video reload (0xC0000005)** — the vmem buffer pool is now grow-only and never
> rebuilt for the same dimensions, so a reload reuses the registered buffers and in-flight frame
> callbacks never point at freed memory.
>
> **Pause threw the progress bar back to the beginning** — suspend() records the authoritative
> libvlc position (`get_time` via `currentPacingNanos()`) instead of a drifting wall-clock
> estimate.
>
> **Scrub preview, seek-stick and loop/replay fixes (feat/libvlc)** — three follow-up playback
> defects after the audio-split work.
>
> **Scrub preview showed only the first frame everywhere** — `LibVlcFrameExtractor` issued
> `libvlc_media_player_set_time` without waiting for the media to become seekable, so on many URLs
> the seek was silently ignored and the player just kept playing from 0 — every sample therefore
> captured the same opening frame. The extractor now polls `libvlc_media_player_is_seekable` before
> seeking, waits until `get_time` actually reaches the target region (so stale pre-seek frames can't
> satisfy the frame latch), and runs the media with `:no-audio` so the video clock alone drives the
> seek. Progress-bar thumbnails now reflect the hovered timestamp.
>
> **Backwards seek could stick (forwards was fine)** — after a seek the session manager force-called
> `play()` whenever the player was not in Playing, but a backwards seek legitimately drops into
> Buffering while it re-acquires the target region; calling `play()` mid-buffer interrupted the seek
> and froze the picture. It now only resumes on the dead Stopped/Ended states and leaves Buffering
> alone. (The old check also compared the player *state* against libvlc *event* constants — 0x104
> Playing — which never matched; dedicated `LIBVLC_STATE_*` constants are added.)
>
> **Video didn't replay after finishing** — libvlc's ENDED state ignores `set_time`, and a bare
> `play()` in libvlc 3.0 is not guaranteed to restart the finished media. `beginSeek` now calls
> `stop()` first (returning the player to STOPPED) then `play()` for the loop/replay path, so a
> finished video reliably restarts from the beginning instead of freezing on the last frame.
>
> **libvlc audio split: 3D DSP + Java Sound line restored (feat/libvlc)** — libvlc was playing
> audio through its default system-audio output, which bypassed the per-display 3D DSP chain
> (`AudioDspStage` / `AcousticsEngine`) entirely. That silently broke three features the old
> pipeline had: directional audio (facing west with a display to the north no longer panned to the
> right speaker — it came out both), occlusion (blocking the display with blocks no longer
> muffled/attenuated the sound), and own pacing (libvlc's default output often stopped the audio a
> couple of seconds before the video ended). The player now registers
> `libvlc_audio_set_format_callbacks` + `libvlc_audio_set_callbacks`, which delivers decoded PCM
> to a new `LibVlcAudioOutput` instead of the system speaker: every block is run through the
> display's `AudioDspStage` (direction-aware panning / binaural, occlusion filter + gain, reverb)
> and written to a Java Sound `SourceDataLine` on our own clock. Volume is applied as a PCM gain
> (`setVolume` stores the effective gain; the audio thread picks it up on every play callback)
> since libvlc's software volume is a no-op under audio callbacks. Also fixes the reported F3
> decoder line: `libvlc_media_player_get_video_decoder_info` is queried on PLAYING with a 500ms
> retry (it is often unavailable at the very first event), so the overlay shows the real decoder
> name (e.g. D3D11VA H.264) instead of a stale "software".
>
> **Stretch-mode black screen fixed (feat/libvlc)** — the LETTERBOX/CROP scaling path in frame
> publish had two bugs that made non-STRETCH modes render black: `spare.clear()` only resets the
> buffer position (it does NOT zero the memory, so letterbox bars showed garbage), and CROP's
> negative centring offset made `dst.position()` throw — which publishFrame's catch swallowed,
> dropping the whole frame. `fitFrame` now zero-fills the destination first (reusable chunk), clamps
> the blit coordinates so negative offsets are handled, and leaves the position at frameSize so the
> caller's `flip()` exposes every byte — LETTERBOX pads with true black bars, CROP center-crops, and
> STRETCH stays full-screen.
>
> **Loop/replay after end-of-stream fixed (feat/libvlc)** — a playback loop or replay after ENDED
> did nothing because libvlc's ENDED state ignores `set_time`: `beginSeek` now checks the player
> state and calls `play()` first (then waits 50ms for the new timeline) when the media has reached
> its end, so loop/replay restarts from 0 instead of freezing on the last frame.
>
> **libvlc audio-slave + clock + seek fixes (feat/libvlc)** — three playback defects resolved:
> DASH audio is now attached with `libvlc_media_player_add_slave` (full URI) instead of the
> string `:input-slave=URL` media option, whose option parsing truncated Bilibili audio URLs at
> `&` and left the audio track silent — which in turn stalled libvlc's audio-driven master clock,
> freezing both the video and the progress bar at a random 6–8s. libvlc `get_time`/`set_time`
> units were corrected to **milliseconds** (the port treated them as µs, so the progress bar sat
> near zero and seeks overshot by 1000×). Distance volume attenuation was eased (quadratic → 25%
> floor) and the libvlc software-volume mapping amplified 3× (`v*100` → `v*300`) to match the
> old pipeline's loudness. Hardware decoding (`--avcodec-hw=any` on instance and media, the
> VideoPlayer model) is re-enabled with copy-back: VLC decodes on the GPU and copies frames back
> to system memory for the vmem callbacks, so 4K H.264/HEVC no longer starves the CPU — the
> earlier "hw never reaches vmem" assumption was wrong and is retracted.
>
> **libvlc full VideoPlayer-model rewrite (feat/libvlc)** — the whole playback pipeline was
> rebuilt to mirror the VideoPlayer mod's low-level libvlc architecture, dropping the fragile
> vlcj wrapper entirely. A single libvlc instance + single media player are created once for the
> whole session-manager lifetime and never rebuilt; switching videos only calls `set_media` +
> `play` on the existing player, so no JNA callback trampoline is ever dropped while libvlc's
> async teardown could still touch it — `JNA: callback object has been garbage collected` spam,
> the green/frame flicker on switch, and the stutter are all eliminated by construction. Video is
> delivered through low-level lock/unlock/display/setup/cleanup callbacks into a triple-buffered
> pool (the VideoPlayer `TextureRenderCallback` model), and every libvlc control operation is
> serialised on one control executor. Audio is now left to libvlc's own default output (the
> `:input-slave` DASH audio stream is merged into the same player), removing the fragile Java PCM
> pipe that caused audio to fade after a few seconds. Volume is still controlled through libvlc.
>
> **libvlc low-level start fixes (feat/libvlc)** — first-start defects in the rewrite were
> fixed: every JNA callback signature was made nullable — libvlc passes a null `opaque`/`userData`
> context on the lock/unlock/display/event trampolines, and Kotlin's non-null checks crashed the
> callback thread with a JNA NPE until the types matched reality.
>
> **libvlc software-decode video fix (feat/libvlc, superseded)** — an early attempt claimed the
> low-level video callbacks (`libvlc_video_set_callbacks`, the vmem output) were incompatible
> with hardware-accelerated decoding and removed `--avcodec-hw`. That assumption proved wrong:
> VLC's avcodec module decodes on the GPU and copies the frame back to system memory for the vmem
> lock callback, exactly how the VideoPlayer mod runs. Hardware decoding is re-enabled (see the
> audio-slave/clock/seek section above); this paragraph only records the superseded interim fix.
> The obsolete `--plugin-path` instance option (removed in libvlc 3.0.21, only emitted a warning)
> stays dropped; libvlc finds its plugins via the default relative layout / `VLC_PLUGIN_PATH`.
>
> **libvlc vlcj removed entirely (feat/libvlc)** — the last two vlcj usages are gone: the
> scrub-preview `FrameExtractor` is rewritten on the low-level libvlc binding (a short-lived
> media player with strong-referenced callbacks decodes one frame per thumbnail), and the
> version probe reads `libvlc_get_version` directly instead of constructing a vlcj
> `MediaPlayerFactory`. The dead vlcj pipeline classes (`LibVlcAudioDecoder`, `LibVlcVideoPipe`,
> `LibVlcAudioProcess`) are deleted and vlcj/vlcj-natives removed from the build, shadow config
> and version catalog (an explicit JNA dependency replaces the transitive one; Minecraft already
> bundles JNA at runtime). This eliminates every remaining `JNA: callback object has been
> garbage collected` / `Invalid memory access` spam that the old vlcj `CallbackVideoSurface`
> produced on scrub requests.
>
> **libvlc reliability (feat/libvlc)** — removed the FFmpeg-era stall watchdog and
> CDN-failover recovery from `MediaPlayer`; libvlc now owns buffering, A/V sync and
> network recovery, so a healthy session is no longer misjudged as stalled and
> force-restarted (the old watchdog read a last-frame timestamp that was never
> stamped, producing a `No frames for … ms` restart loop that killed live playback).
> Separated DASH audio (Bilibili/YouTube) is now attached to the libvlc input via
> `:input-slave=<audioUrl>` instead of playing silently, and hardware-accelerated
> decoding (`--avcodec-hw=any`) is enabled when the config requests it.

> **libvlc callback GC fix (feat/libvlc)** — the audio callback and `CallbackVideoSurface`
> were created fresh on every `start()` without any strong reference, so JNA garbage-collected
> them. libvlc then spammed `JNA: callback object has been garbage collected` and the orphaned
> write end caused `AudioSink: Write end dead` — no audio was heard. Both objects are now held
> as instance fields and reused across restarts.

> **libvlc callback lifetime fix (feat/libvlc)** — playback still spammed
> `JNA: callback object has been garbage collected` and stuttered after a while, because every
> restart rebuilt the `MediaPlayerFactory` (a fresh libvlc instance), the `CallbackVideoSurface`
> (fresh lock/unlock/display/setup/cleanup trampolines) and a new event listener, dropping the
> strong references JNA needs (it only holds callbacks weakly). The factory, video surface and
> event listener are now session-manager singletons reused across restarts; `stop()` keeps the
> factory alive and only `cleanup()` releases it, so the libvlc instance and its callbacks are
> never collected mid-flight.

> **libvlc single-player model (feat/libvlc)** — audio still vanished after a few seconds and
> switching videos produced green/frame flicker then a hard stutter, because every restart rebuilt
> the `EmbeddedMediaPlayer` and `CallbackVideoSurface` while libvlc's async teardown could still
> touch the old JNA trampolines, and two players raced the same video surface. Now a single
> `MediaPlayerFactory` + `EmbeddedMediaPlayer` + `CallbackVideoSurface` is created once for the
> whole session-manager lifetime (the VideoPlayer single-instance model): switching videos just
> plays a new media on the existing player, `stop()` only stops playback, and `cleanup()` releases
> everything. All JNA callbacks are registered exactly once and held strongly until cleanup.

> **libvlc stability fixes (feat/libvlc)** — fixed a class of crash-on-play failures
> from the libvlc port: every session restart now tears down the previous
> `EmbeddedMediaPlayer` + video surface first (previously each stall restart leaked
> a fresh player, so multiple libvlc callback threads piled up and raced on shared
> render state → `BufferOverflowException` / `IndexOutOfBoundsException` and a JVM
> abort from GL cleanup on a non-render thread). The video render callback now reads
> its dimensions from the per-callback `BufferFormat` instead of shared fields,
> ignores stale callbacks from released players, and guards the frame path with a
> try/catch so a bad frame can never escape into JNA. GPU uploader teardown is
> deferred to the render thread, and the first-frame latch is re-armed per start so
> restarts genuinely wait for the next frame.

> **libvlc migration (feat/libvlc branch)** — the media pipeline is being ported
> from JavaCPP/FFmpeg to **libvlc** (vlcj). One unified `EmbeddedMediaPlayer`
> handles video + audio (no split channels); play/pause/seek/A-V sync are all
> delegated to libvlc instead of reimplemented pacing loops. The LibVLC + SQLite
> runtimes are **collected from official pre-built VideoLAN distributions** by the
> CI "Build Natives" workflow (7 target platforms: Linux/macOS/Windows ×
> x86_64/aarch64 + Windows x86) and **downloaded at runtime** into
> `./dreamdisplayx/natives/<os>/<arch>/` on first boot (never bundled in the jar,
> which would balloon to hundreds of MB). Collection sources (same strategy as
> VideoPlayer-Library): Flathub flatpak (Linux), official VideoLAN dmg/zip
> (macOS, Windows x86/x64), MSYS2 package (Windows aarch64, the only pre-built
> source since VideoLAN ships no win-arm64). A runtime bootstrap
> (`NativesDownloader`) fetches the pinned release assets (gh-proxy.com mirror
> with direct GitHub fallback), extracts them, and wires up
> `jna.library.path` / `VLC_PLUGIN_PATH` / `org.sqlite.lib.path`. On the client
> both runtimes are fetched at mod init; the dedicated server fetches only the
> SQLite runtime (it depends only on core+util). This work is in progress on the
> `feat/libvlc` branch.

## Highlights

- **JavaCPP audio decode** — the FFmpeg CLI subprocess for audio is replaced by an in-process `FFmpegFrameGrabber` (JavaCPP). No more external FFmpeg binary download for audio; `HlsAudioFeeder` removed.
- **ScrubPreview frame extraction** — migrated from FFmpeg CLI subprocess to `JavaCppFrameExtractor` (in-process `FFmpegFrameGrabber` frame grab to JPEG). No more FFmpeg binary needed for thumbnails.
- **FFmpeg CLI completely removed** — all FFmpeg binary download, probe, capabilities, and CLI process management code (`FFmpegBinary.kt`, `FFmpegCapabilities.kt`, `MediaProcess.kt`, `VideoFramePipe.kt`, `HlsAudioFeeder.kt`, `HlsSeekPlaylist.kt`) deleted. The entire mod is now fully self-contained: no external FFmpeg binary, no Rust toolchain, no Python.
- **JavaCPP video decode** — the Rust native library (`dreamdisplayx_native` + `dreamdisplayx_lav`) and FFmpeg CLI video pipelines are replaced by an in-process `FFmpegFrameGrabber` (JavaCPP / org.bytedeco:javacv). No more Rust build toolchain, no more external FFmpeg binary for video; the decode library is bundled from Maven as `ffmpeg-platform:8.1.2`.
- **yt-dlp removed** — the `yt-dlp` subprocess, binary downloader/updater, and all YouTube resolution (NewPipeExtractor) are gone. No external Python/toolchain dependency remains; Twitch, Vimeo, Kick, and Bilibili all keep their in-process resolvers.
- **Direct search service** — pasting a URL into search now shows its info card directly, and Bilibili `BV` / `av` IDs resolve straight to the video.
- **Bilibili multi-CDN smart switching** — stream resolution now collects every CDN backup URL from the Bilibili API; when a CDN keeps failing, the player automatically switches to the next backup before re-resolving.
- **Seek-jump fix** — watching a video, then loading a new one, no longer restores the previous video's old saved position (which could jump to a stale ~8s timestamp).
- **A/V drift auto-resync** — if the video drifts more than 5s behind the audio clock, frames are dropped to let the decoder catch up (like online players), instead of drifting unboundedly.
- **F3 debug overlay** — shows mod version, FFmpeg version, display count, frame stats. Works on 1.21.1 (mixin) and 26.x (native DebugScreenEntry).
- **Fixed A/V sync** — `Frame.timestamp` (µs) is now correctly converted to nanoseconds before comparing with the audio clock, eliminating the spurious 1s/s A/V drift that caused ~85% frame drops and video stutter.
- **Fixed `pixel_format: bgr24` log spam** — switched to `imageMode = RAW` + `sws_scale` for all video conversion, so javacv no longer sets a pixel_format codec option on `avformat_open_input`.
- **Fixed silent audio on S16 sources** — `JavaCppAudioDecoder` now handles both `FloatBuffer` (FLTP) and `ShortBuffer` (S16) source formats.
- **Stretch mode** — choose how video fits the display: **Fit** (letterbox, black bars), **Stretch** (fill/distort), or **Crop** (zoom fill, no bars). A GUI slider in the display settings menu, per-display persistence, and a server config default (`display.default_stretch_mode = "LETTERBOX"` in `config.toml`).
- **Fixed green/pink preview** — the U/V plane swap in `i420ToRgba` was caused by `ByteBuffer.duplicate().position().get()` reading from the buffer start instead of the set position; now uses absolute offsets.
- **Login/logout i18n** — the `/display login` and `/display logout` commands now use translatable server-language keys instead of hardcoded English strings.
- **Full Chinese server translation** — new `zh.json` covers every server command (`/display help`, `/display info`, `/display video`, stretch-mode GUI options 适配/拉伸/裁剪, etc.); locale mapping recognizes `zh_cn` / `zh-cn` / `zh-tw` / `zh-hant` variants.
- **Bilibili search infinite scroll** — "load more" now fetches real next pages from the Bilibili search API once the locally buffered ranking is exhausted, instead of silently stopping after the first ~60 results.
- **Seek fixes** — a normal video end (`grabImage()` returning null) is now treated as a clean EOS so replay loops instead of erroring with `Unrecoverable: Unknown error`; entering the error state freezes the playback clock so the progress bar no longer advances over a frozen frame; VOD seeks hint the HTTP protocol that the server answers Range requests (`seekable=1`) so seeks jump instead of downloading from the start.
- **In-place seek** — seek no longer creates a new FFmpegFrameGrabber (which re-opens the network connection and re-probes the stream). Instead the reader thread calls `g.setTimestamp()` directly on the same open grabber, the prebuffer is reset, and the surface is cleared — no re-connect, no re-probe. Audio still gets a fresh decoder at the new offset. This makes seeks fast and reliable (no connection failure during re-open).
- **Thumbnail decode off the main thread** — `ImageIO` decode + pixel loops + ambient-grid work moved out of `Minecraft.getInstance().execute`; the main thread now only registers the already-decoded texture, removing thumbnail-related frame hitches.
- **F3 debug overlay counters** — `framesToGpu` / `framesDropped` are now collected unconditionally (they were gated behind a debug flag and always showed 0); the mod name renders in blue and the version in green.
- **`/display info` duration** — displays in any mode now report their resolved duration to the server (previously only SYNCED/BROADCAST sent it, so other displays showed "Unknown").
- **FFmpeg info-log filter** — global `av_log_set_level(AV_LOG_ERROR)` in the JavaCPP pipelines (video, audio, frame extractor), silencing the noisy `avformat_open_input rejected some options` stream dumps.
- **Audio CDN failover on A/V drift** — the prebuffer now reports genuine drift resyncs (video falling > 5 s behind the audio clock); after 3 of them the player switches the audio stream to the next backup CDN instead of staying on a bad line. The normal pacing skips (fresh frame ready) do not count.
- **Bilibili unreleased content filtered** — search results whose badge says 预告 / 未上映 / 敬请期待 / 即将上映 / 尚未上映 are no longer offered, so clicking them can't fail with "Bilibili video/room could not be reached" and then confuse the display.
- **Bilibili resolution not cached** — VOD stream resolution is resolved fresh on every play (no 30-minute cache), so after a re-login the player immediately picks the credential's highest quality instead of stale anonymous 480p.
- **Commit ID in build & F3** — every build stamps `assets/dreamdisplayx/commit.txt` with `git rev-parse --short HEAD`; the startup log prints `v1.9.4.1 Developer (abcd123)` and the F3 debug overlay shows a `Commit: <hash>` line. Works for developer, preview, and release builds alike.
- **Fabric Cloth Config screen** — the Fabric ModMenu "Configure" button now opens a proper Cloth Config screen (boolean toggles, int/double fields, enum selector). Cloth Config is an optional dependency: when it is not installed, the button is hidden and the game is unaffected. The old hand-drawn `ClientConfigScreen` was removed.
- **Seek to 0 / EOS reliability** — the in-place seek request sentinel is now `Long.MIN_VALUE` so seeking to the very beginning is honored (previously 0 was indistinguishable from "no request": video never moved, audio gate never opened → fast-forwarded silent video); `grabImage()`'s `@NotNull`-induced `NullPointerException` ("must not be null") at EOF is treated as a clean end, and an in-place seek that fails releases the audio start gate so sound can never be stuck muted.
- **Bilibili CDN mirror selection + startup bandwidth ranking (based on [PiliPlus](https://github.com/piliplus/piliplus))** — the old TTFB-based probe only measured first-byte latency, which tells nothing about download speed. Bilibili streams now go through a bandwidth probe and the `upos-*` host is rewritten to the fastest mirror from the PiliPlus-derived mirror list (`ali` / `cos` / `hw` / `hwb` / `08c` / `akamai` / overseas mirrors, ...). The startup pre-probe downloads **8 MB** per mirror — the same chunk size PiliPlus uses for its CDN speed test (`maxSize = 8 * 1024 * 1024`) with a 15 s per-host timeout, so TCP leaves slow-start and the measured throughput reflects the real achievable download speed instead of a sub-256 KB connection-setup artifact. A new `bilibili-cdn-mirror` config entry (default `auto`) lets the player pin a specific mirror, `BASE_URL` (keep the API URL) or `BACKUP_URL` (use the first backup URL). The ranking is warmed up at game startup against a sample public video and re-measured every time the config screen is opened — the config page also shows the mirror dropdown exactly like PiliPlus. Credit: mirror list and host-replacement logic ported from PiliPlus (and BiliRoaming).
- **Bilibili session refresh removed** — Bilibili's passport web-cookie refresh endpoint (`/x/passport-login/web/cookie/refresh`) consistently returns `-400 请求错误` regardless of device-fingerprint parameters (`buvid3`), so the automatic refresh sweeps (`BilibiliSessionRefresher.kt`, `refreshAllBilibili()`, hourly jobs in the Paper and vanilla bootstraps) are all removed. When a stored SESSDATA expires, the user simply logs in again via `/display login` — consistent behavior with several other Bilibili clients.
- **Fixed 4K blur / decode cap** — the decode target and GPU texture size were derived from the user's quality setting (Auto → 1080p), so a 4K stream was downscaled by `sws_scale` to the 1080p texture and looked identical to true 1080p close up. The player now reports the resolved stream's actual pixel height (`PlaybackHost.videoContentHeight`), the texture allocation uses `max(quality, sourceHeight)`, and decode follows the texture size — a 4K source now stays 4K on screen even when the quality setting is Auto.

## CI / Build

- **SQLite native build separated** — the 6-platform sqlite-jdbc C compilation is moved to a standalone workflow (`sqlite-natives.yml`, `workflow_dispatch`). The main CI downloads pre-built natives from the corresponding GitHub Release, cutting ~3 minutes per CI run. The release tag is pinned in `.github/sqlite-natives-tag.txt`.
- **CI skip on doc/workflow changes** — pushes that only touch `*.md`, `docs/`, `.github/`, `native/sqlite/`, or `.gitignore` no longer trigger a build.

## Server

- Default volume in the server config is now divided by 100 (config stores 0–100 percent) instead of 200, so a configured default volume is applied to new displays at the correct level.
- Server language JSONs are no longer overwritten with the bundled defaults on every startup / reload — they are only restored when a file is missing or corrupt.

## Media player

- JavaCPP (`org.bytedeco:javacv:1.5.14` + `ffmpeg-platform:8.1.2`) replaces the Rust native pipeline for video decode. The `FFmpegFrameGrabber` opens URLs in-process, supports I420 planar output for GPU YUV rendering, and provides warm park, brightness, and popout.
- Removed Rust native crates (`native/`), `NativeMedia.kt`, `LavFfmpeg.kt`, `NativeVideoFramePipe.kt`, `LavGlSurfaceTextures.kt`.
- `PlaybackSessionManager.VideoChannel` simplified to a single `JavaCppVideoPipe` path (no more in-process libav vs native process vs JVM process fallback).
- Audio pipeline migrated to JavaCPP: `JavaCppAudioDecoder` (FFmpegFrameGrabber grabSamples → S16LE PCM via PipedInputStream) replaces the FFmpeg CLI subprocess. `HlsAudioFeeder` removed (FFmpegFrameGrabber opens HLS URLs directly). No more FFmpeg binary download for audio.
- Removed `FFmpegBinary.prewarmAsync` from client startup (JavaCPP handles its own native loading).
- Bilibili DASH / durl / live streams keep every backup CDN URL; on repeated session stalls the player tries the next CDN before invalidating caches and re-resolving.
- Fixed stale `savedTimeNanos` reuse on video swap that caused the first-second reload-and-seek jump.
- `FramePrebuffer` drops frames more than 5s behind the audio clock so a slow decoder resyncs instead of drifting forever.
- Fixed `JavaCppVideoPipe` GPU YUV (planar) decode path: javacv's `imageMode = RAW` only fills the Y plane (frame.image[0]) and leaves U/V null, so `frameToI420` rejected every frame. Rewrote the converter to use the standard FFmpeg `sws_scale_frame` API on the underlying AVFrame (`frame.opaque`), which handles pixel format conversion and resolution scaling in one call — exactly how `ffmpeg -vf scale` works. The YUV pipeline now works with any source resolution and pixel format.
- **In-place seek** — `JavaCppVideoPipe` now supports `requestInPlaceSeek()`: the reader loop checks a volatile `AtomicLong` at each iteration, and when set, calls `g.setTimestamp()` on the same grabber, resets the prebuffer and surface, and continues reading. No new channel, no re-open, no re-probe. `PlaybackSessionManager.beginSeek` tries the in-place path first when the URL and dimensions match the active pipe, falling back to the full channel swap only when the source changes.
- **Hardware-accelerated video decode (FFmpeg hwaccel)** — `JavaCppVideoPipe` now tries FFmpeg's `hwaccel` option before falling back to software decode. The video pipeline accepts a priority-ordered list of backend names from the client config; backends are verified against the running FFmpeg build (`av_hwdevice_iterate_types`) and a single-frame probe, so unavailable driver or unsupported codec silently falls back to the next candidate and then to software. The `hwaccel_output_format` is set to `yuv420p` so hardware-decoded frames are transferred back to system memory in the planar format the converter already understands.
- **GPU vendor detection (cross-version)** — `GpuVendorProbe` detects the active GPU vendor (NVIDIA, Intel, AMD, unknown) through the Minecraft render device (`DeviceInfo.vendorName()` on 26.2) or the LWJGL OpenGL renderer string (1.21.x). Combined with the OS and the active render backend (Vulkan/OpenGL), `HwAccelCandidateResolver` produces the optimal hwaccel candidate list: Windows Intel → QSV, NVIDIA → CUDA (NVDEC), AMD → AMF, global Windows fallback → D3D11VA; Linux → VA-API (Vulkan preferred when the Vulkan renderer is active); macOS → VideoToolbox.
- **Audio decoder parallel open** — `PlaybackSessionManager.start()` and `beginSeek` now open the audio decoder (`CompletableFuture.supplyAsync`) concurrently with the video channel, reducing the initial A/V open gap from `audio_open + video_open` to `max(audio_open, video_open)`. Audio seek timeout also reduced via `probesize=64K`.
- **Configurable video decoder** — the new `hwaccelDecoder` option (`"auto"` / `"software"` / specific FFmpeg backend name) lets the user choose the preferred decode backend. A dropdown in the Cloth Config screen lists every available FFmpeg hwaccel type. Default: `"auto"` (OS + GPU vendor detection).
- **Cloth Config screen localisation** — the Fabric and NeoForge Cloth Config screens now use translatable `Component`s (`dreamdisplayx.config.*` keys) instead of hardcoded strings. en_us and zh_cn keys added; missing locales fall back to English automatically.
- **Faster stall detection** — network `rw_timeout` reduced from 5s to 3s and reconnect back-off cap from 10s to 5s in both the video and audio JavaCPP grabbers, so a dead CDN connection trips the failure path sooner and the player switches to the next CDN / restarts faster on slow networks.
- **CDN speed probe** — before playback starts, `CdnSpeedProbe` sends a tiny Range request (32 bytes) to each distinct CDN edge hostname and reorders the stream URLs fastest-first. Results are cached per hostname for the session, so subsequent videos skip the probe. The total budget is 6 s and each host gets at most 2.5 s; if the budget is exhausted the original order is kept. This reduces the chance of stall-driven failover during playback by picking the quickest CDN upfront.
- **Bilibili CDN mirror selection + bandwidth probe (based on [PiliPlus](https://github.com/piliplus/piliplus))** — the old 32-byte TTFB probe picked the edge with the best first-byte latency, which tells nothing about download speed, so the fastest-looking host could still stall. Bilibili stream URLs now go through a bandwidth probe (256 KB Range request, ranked by MB/s) and the `upos-*` / `bilivideo.com` host is rewritten to the fastest mirror — the same approach PiliPlus uses. A new `bilibili-cdn-mirror` config entry (`auto` default) lets the user pin a specific mirror (`ali`, `cos`, `hw`, `hwb`, `akamai`, ..., or `BASE_URL` / `BACKUP_URL`) instead of probing each time. Credit: CDNService mirror list and host-replacement logic ported from PiliPlus (and BiliRoaming). Non-Bilibili streams keep the TTFB latency reorder.
- **Fix black video with AMF hwaccel on unsupported sources** — when a hwaccel backend ignores `hwaccel_output_format=yuv420p` (seen with AMF on Bilibili 4K VOD), the decoded AVFrame stays on the hardware device with invalid data pointers, and feeding it to sws_scale produced `bad dst image pointers` + "Skipped frame" on every frame = black video. The grabber now probes one frame and verifies it actually transferred to system memory before accepting the hwaccel; the converters also guard against hardware frames at runtime. Falls back to next candidate / software decode.
- **Fix sws_scale `bad dst image pointers` spam with AMF + RGB24 path** — every video rendered through the non-YUV (RGB24) pipeline (e.g. with an Iris shader pack active, which disables GPU YUV) failed: `sws_scale` reads up to 4 entries from the src/dst pointer arrays, but the converters passed only 1–3 entries, so the extra slots read stale heap memory as a fake plane with stride ≤ 0 and every conversion was rejected. All `sws_scale` call sites now pass exactly 4 entries (null / 0 for unused planes), and conversion failures are returned as skip instead of CollectionRandom crash. All `AVFrame.data()` accesses are guarded so a freed frame on shutdown no longer throws `NullPointerException`.
- **Fix shader-pack detection forcing the slow RGB24 path with Iris installed** — `ShaderPackCompat` conservatively returned `true` whenever Iris was present even when no pack was actually loaded or shaders were disabled (`enableShaders=false` in iris.properties). That disabled the GPU YUV pipeline and pushed every video through the CPU-side RGB24 path, causing 72-frame-then-stutter playback and stall-driven restarts. The detector now trusts a definitive "no" from any successful API call (e.g. `isShaderPackInUse()` returning false) and only falls back to the conservative `true` when every detector throws. Iris packs still disable YUV; Iris with shaders off now uses the fast YUV path again.
- **Fix watchdog firing a false stall right after a restart** — `StreamWatchdog.start()` captured the old last-frame timestamp as its baseline, so a restart immediately after a stall inherited the previous session's ~45 s silence and re-reported "No first frame after …" within seconds. The baseline is now the current time at `start()`, giving the new session a fresh startup grace period.
- **Fix hwaccel fallback regression (AMF / D3D11VA rejected then software decode lags)** — the earlier sws_scale probe pass on the grabbed probe frame rejected valid backends: `sws_scale` treats null entries in the src/dst pointer arrays as errors, so the probe always failed and every session fell back to slow software decoding under Iris. The probe pass is removed; the existing `hw_frames_ctx` / data-null checks plus the 4-element pointer-array fix are what actually protect the converters. AMF / D3D11VA now decode on the GPU again.
- **CDN probe logging** — the CDN speed-probe result (per-host latencies and the chosen order) is now logged at INFO instead of DEBUG, and probe failures/slow-rejection lines are at WARN, so playback sessions show which CDN edge was picked and why.
- **Fix display staying black after disabling an Iris shader pack** — the YUV render pipeline was cached indefinitely, so after a shader/backend change the old shader program was still referenced. The pipeline is now rebuilt whenever the shader state version changes, and the frame uploader requests a fresh RGBA fallback texture when it detects such a change.
- **Fix ExecutionException during seek with audio decoder open failure** — the fast-path seek previously crashed out instead of gracefully failing when the audio decoder failed to start (e.g. TLS timeout), leaving the seek half-applied. The exception is now unwrapped and the seek returns false so the caller falls back to the full-swap path.

## Sources

- Bilibili quality selector now shows canonical resolution labels (360P / 480P / 720P / 1080P / 4K) instead of the actual encoded heights. Movie & bangumi DASH streams report heights like 808 or 538, which previously rendered as "808p" / "538p"; the qn (`id`) is now mapped to the standard height.
- Removed `yt-dlp` orchestrator, binary bootstrap/self-update, client race, output parser, format & search caches, and cookie manager.
- Removed YouTube resolver chain (`NewPipeExtractor`) and YouTube-specific UI paths (chapters, related videos, title/metadata caches).
- Removed `newpipeExtractor` (and its transitive `nanojson` / `jsoup` / `rhino`) dependencies and shadow relocations.
- `MediaSearchService` is now backed by `DirectSearchService`: URL paste → info card, `BV`/`av` → Bilibili video, no text search or related videos.
- Removed `ytdlp-proxy` / `ytdlp-cookies-from-browser` client & NeoForge config entries.

# 1.9.3.3 Release

Based on Dream Displays [`86ba1b61`](https://github.com/arnodoelinger/dreamdisplays/commit/86ba1b61).

## Highlights

- **`/display create` and `/display rename` on Paper** — create a display by name, and rename an existing display by id / prefix from the console, matching the UI-driven workflow on other platforms.
- **QR login poll fix** — Bilibili QR login now correctly recognizes `expired` / `scanned` states from the poll response's top-level `code`, so the login screen no longer lingers or mis-handles the QR lifecycle.
- **SQLite storage fix** — the bundled SQLite JDBC driver is relocated for mod isolation and its native library is rebuilt with matching JNI symbols, so singleplayer / integrated servers that force SQLite start without crashing.
- **Flashback replay compat** — a Flashback replay server is detected (by world path) and skips opening its SQLite database, so replaying / exporting no longer leaves `dreamdisplayx.db` locked and Flashback can clean up its temp folder without errors.

## Server

### Features

- Paper `/display create <name>` and `/display rename <id> <new_name>` subcommands now work from the console.
- Custom JDBC URL support in the storage config.

### Fixes

- Bilibili QR poll now reads the result `code` from the top level of the response, so `86038` (expired) and `86090` (scanned) are classified correctly instead of being treated as pending.
- Flashback replay servers now skip the SQLite / credential init entirely, so no `dreamdisplayx.db` is ever opened on a replay world.

# 1.9.3.2 Release

Based on Dream Displays [`86ba1b61`](https://github.com/arnodoelinger/dreamdisplays/commit/86ba1b61).

Bilibili playurl / metadata requests keep sending the login cookie, so VIP movies, bangumi, and videos still play at their allowed quality when logged in.

## Highlights

- **Bilibili bangumi / movie playback** — paste `https://www.bilibili.com/bangumi/play/ep<id>` (episode) or `.../ss<id>` (season) and it resolves the season's episode, pulls its DASH stream, and shows the episode title / cover.
- **Cached displays are scoped to their creation dimension** — softly-unloaded displays only restore when you're in the same dimension, so displays don't leak across nether / end / overworld.

## Client

### Features

- Bilibili bangumi episode (`ep`) and season (`ss`) URLs now resolve to playable streams, with the series + episode title and episode cover shown as metadata.
- Bangumi `video_info` low-quality fallback now keys its progressive stream from `durls` (matching upstream).
- Softly-unloaded (render-distance-cached) displays are tagged with their creation dimension and only restore when the player is back in that dimension.

### Fixes

- Metadata cache keys now cover bangumi episodes (`ep:<id>`) and seasons (`season:<id>`), so their titles / thumbnails persist in the metadata cache.
- Removed the automatic "load Bilibili home recommendations when the panel is empty" behavior — an empty suggestions panel now stays blank until you search or play a video, since the recommendation feed did not work reliably.

# 1.9.3.1 Release

Based on Dream Displays 1.9.3 (https://github.com/arnodoelinger/dreamdisplays).

## Highlights

- **QR login auto-close** — the login screen now closes itself once the QR scan completes.
- **QR logout reliability** — `/dlogoff` now reliably deletes the saved server-side credential, even when the integrated server is on 1.21.1.
- **Danmaku overlay cleanup** — disabling a display's danmaku toggle now clears the overlay immediately so stale lines don't stay stuck on screen.
- **Danmaku UI tuning** — danmaku display area is now fixed to 25 / 50 / 75 / 100 %, and font size is now small / medium / large (0.5x / 1x / 1.5x).

## Client

### Features

- QR login screen auto-closes on successful login.
- Danmaku display area selector now uses fixed presets: 25 / 50 / 75 / 100 %.
- Danmaku font size selector now uses fixed presets: small (0.5x) / medium (1x) / large (1.5x).
- Suggestions panel now searches Bilibili exclusively (video, bangumi, movie).
- Bilibili search results are ranked: bangumi/movies first, then uploader-name matches, then title matches.
- Bilibili media-type filter added to the suggestions panel: all / video / bangumi / movie.
- Bilibili search loads in pages of 20 results on scroll.
- Search result cards now show a pink **大会员** tag for VIP-only content and a yellow **付费** tag for pay-per-view; free Bilibili results drop the redundant platform tag.
- Removed the view-count popularity floor so bangumi/movie results (which carry no `play` count) always show up.
- Scrolling right / down to the end of the loaded cards now correctly pages in the next 20 Bilibili results, with id + title deduplication so nothing repeats.
- Debug logs added to the Bilibili search, filter-change, pagination, and card-append paths.

### Fixes

- `/dlogoff` now sends the server logout command on all supported versions; singleplayer worlds no longer re-remember the Bilibili account after logout.
- Disabling danmaku on a single display now immediately clears any visible overlay instead of leaving stale lines on screen.
- Bangumi and movie/TV search now return results: movie/TV uses the correct `media_ft` search type (as PiliPlus does), and results show the real series title.

Based on Dream Displays 1.9.3 (https://github.com/arnodoelinger/dreamdisplays).

## Highlights

- **Merged upstream 1.9.3**: pull in all upstream changes (upstream commit `622e4278`).
- **Per-display danmaku settings** — opacity, font size, speed, display area, type filters.
- **Global Bilibili login** — single account per server/network, broadcast to all players, OP-only,
  with LuckPerms support and cross-server credential sync (SQLite/MySQL).
- **Bilibili account info** — avatar, nickname, and VIP badge at display config top-right.
- **Bilibili bangumi / movie** URL support (`/bangumi/play/ep<id>` and `/ss<id>`).
- **Pause reliability improved** — warm park works with external-process FFmpeg.
- **Fork**: renamed mod/plugin to **Dream DisplaysX**; built-in `zh_cn.json`.

## Client

### Features

- Per-display danmaku settings (opacity, font size, speed, display area, type filters).
- Bilibili account label (avatar, nickname, VIP badge with official image).
- `/dlogoff` command (OP-only); `/dlogin` is now OP-only.
- `zh_cn.json` with full Simplified Chinese translation.
- Bilibili bangumi / movie playback (`ep` / `ss` URLs).
- Bilibili search now covers movies and bangumi in the suggestion grid.
- Quality capped at 1080p; 60fps toggle and 2160p/1440p tiers removed.

### Fixes

- Pause reliability: `canHoldWarm()` instead of `canPark()` — works with external FFmpeg.
- Bilibili 60fps / CDN streams no longer 403 (expanded Referer allow-list).
- Danmaku text HTML-unescaped (`&lt;` → `<`, etc.).
- Danmaku font size only affects new messages (like Bilibili).
- Danmaku track spacing scales with font size.
- SettingsSection scissor no longer clips preview buttons and suggestions.
- DanmakuFilterBar and toggle tooltips now properly translate enabled/disabled.
- VIP badge uses official Bilibili image (`img_label_uri_hans_static`).
- Fixed VIP field names (`vipType` → `type`, `vipStatus` → `status`).
- Downgraded noisy "Seek can't go in place" log to debug.
- Bilibili VOD danmaku now uses protobuf segment API (`/x/v2/dm/list/seg.so`) for full danmaku coverage (same as Bilibili's own clients, schema from [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus)).
- Fixed danmaku fetch for cids where the V2 JSON segment endpoint returns 404.
- Fixed Bilibili danmaku JSON parsing compilation errors in segment API implementation.
- TOP/BOTTOM danmaku padding now aligns with SCROLL padding (uses 24px top margin).
- Danmaku overlay clears when toggled off or when a live stream is loaded.
- Restored recommendations panel size (was compressed by danmaku settings rows).
- Fixed SettingsSection areaBottom and owner-action Y placement using wrong padding constant.

## Server

### Features

- **Global Bilibili login**: OP-only `/display login` / `/display logout` commands;
  broadcasts `PlatformCredentials` to all online v2 clients.
- **Cross-server credential sync**: SQLite/MySQL via `SqlCredentialSyncBackend`.
- **LuckPerms support**: `dreamdisplayx.login` and `dreamdisplayx.logout` nodes (default OP).
# 1.9.4 Release

## Highlights

- Added support for Bilibili bangumi URLs (episodes and seasons / movies)
- Some visual and config fixes

## Client

### Improvements

- Added support for Bilibili bangumi URLs (episodes and seasons / movies) ([#188](https://github.com/arnodoelinger/dreamdisplays/issues/188))

### Fixes

- Fixed displays created in one dimension appearing at the same coordinates in every other dimension ([#192](https://github.com/arnodoelinger/dreamdisplays/issues/192))

## Server

### Fixes

- Fixed server config default volume not applied correctly ([#190](https://github.com/arnodoelinger/dreamdisplays/issues/190))
- Fixed user-edited language files being overwritten on every server start / reload

# 1.9.3 Release

## Highlights

- Hotfix: fixed displays being visible through blocks and losing their fog
- Fixed "Couldn't place player in world" error on some `Fabric` / `NeoForge` servers
- Live streams fixes

## Client

### Improvements

- Enlarged default Picture-in-Picture mode from 25% to 33% of screen width
- Improved shader support (now displays use shader's fog)
- Update `yt-dlp` binary every day instead of once per weak
- Shortened the drop-out when a live stream stops serving segments
- Bumped `NewPipeExtractor` version

### Fixes

- Fixed displays being visible through blocks and losing their fog
- Fixed live streams jittering in `Synced` / `Broadcast` playback mode ([#191](https://github.com/arnodoelinger/dreamdisplays/issues/191))
- Keep live streams playing past the first manifest and stop timeline drift correction on them ([#191](https://github.com/arnodoelinger/dreamdisplays/issues/191))

## Server

### Fixes

- Fixed "Couldn't place player in world" error on some `Fabric` / `NeoForge` servers

# 1.9.2 Release

## Highlights

- Performance improvements to frame scaling and pause / resume
- Displays now render on top of shaders instead of being affected by them
- Some improvements like a crash on newer `Velocity` versions
- Minor fixes and codebase refactoring

## Client

### Improvements

- Displays are no longer affected by shaders
- Pausing and resuming playback is now instant
- YouTube connections are pre-warmed on server join, cutting the delay before the first frame resolves
- Frame scaling now runs band-parallel across cores instead of on a single core
- Added `LuckPerms` as an optional dependency in the release workflow
- Removed unused suppression annotations and refactored minor stuff ([#183](https://github.com/arnodoelinger/dreamdisplays/pull/183))
- General codebase improvements ([#184](https://github.com/arnodoelinger/dreamdisplays/pull/184))

### Fixes

- Fixed clock jumps when pausing
- Fixed a frame nudge / jump right after resuming from pause
- Fixed duplicated translations on Crowdin

## Server

### Fixes

- Fixed `Velocity` support by using the correct inject annotation for newer versions ([#182](https://github.com/arnodoelinger/dreamdisplays/pull/182))

# 1.9.1 Release

## Highlights

- Native playback improvements, more smooth playback and near-instant seeking
- Low connection client improvements
- Minor fixes and improvements

## Client

### Improvements

- Native playback improvements
- Seeking is now near-instant, even when jumping far ahead or across a long video
- Videos now start playing with a small head start instead of racing the stream from the first frame, so an
  uneven connection no longer shows up as stutter
- Reduced the CPU work a playing display costs per video frame
- Stability improvements for low connection clients
- Security improvements for custom media
- Updated project dependencies and its usages
- Simplified codebase documentation
- Some codebase improvements and refactoring

### Fixes

- Fixed stuttering during the first seconds of playback, and again for several seconds after each seek
- Fixed the picture freezing, or crawling at a few frames per second, whenever playback fell behind the sound
- Fixed playback occasionally stopping with an unrecoverable stream error after seeking

## Server

### Improvements

- Security improvements for custom media

# 1.9.0 Release

## Highlights

- Custom media support: paste a direct link, or file-host (Google Drive, Dropbox, imgur, etc.) share link, and play it on
  a display
- Full Twitch, Vimeo, Kick, and Bilibili support
- `NeoForge` server support, including single-player
- New `Fullscreen` and `Borderless` display modes, with a new `/display fullscreen` command for events and presentations
- Proxy support: `Bungeecord` & `Velocity` for all popular server software
- 3D acoustics for displays: occlusion, air absorption, and raytraced room reverb
- Audio track selector: select your language right in the display menu!
- Filter button: filter out unwanted suggestions
- New `/display schedule` and `/display name` commands
- Full `LuckPerms` support on `Fabric` / `NeoForge` servers
- Added YouTube chapter markers
- Improved `Dream DisplaysX` wiki
- Various other fixes and improvements

## Client

### Features

- `NeoForge` server support (including single-player) ([#95](https://github.com/Aruvelut-123/dreamdisplaysx/issues/95))
- Full Twitch support
- New Borderless and Fullscreen display modes, with a new `/display fullscreen` command for events and presentations
  ([#135](https://github.com/Aruvelut-123/dreamdisplaysx/pull/135))
- Added custom video support and file-host share link support (Google Drive, Dropbox, imgur, etc.) — paste a direct link
  to any video and play it on a display (server must be 1.9.0 or higher)
- Added Twitch, Kick, Vimeo, and Bilibili support ([#129](https://github.com/Aruvelut-123/dreamdisplaysx/pull/129), [#129](https://github.com/Aruvelut-123/dreamdisplaysx/pull/156), [#173](https://github.com/Aruvelut-123/dreamdisplaysx/issues/173))
- Added 3D acoustics for displays: sound is muffled by walls (occlusion), loses highs over distance (air absorption),
  and picks up room / cave reverberation raytraced from nearby blocks and their material; e.g., stone reflects, wool
  absorbs ([#147](https://github.com/Aruvelut-123/dreamdisplaysx/pull/147))
- Added an audio track selector, so you can pick your language right in the display menu
  ([#149](https://github.com/Aruvelut-123/dreamdisplaysx/pull/149))
- Added `/display schedule` command to schedule a video to play at a specific time
- Added `/display name` command ([#151](https://github.com/Aruvelut-123/dreamdisplaysx/pull/151))
- Changed some display commands syntax: now you can choose the display by its ID or by looking at it and typing "this"
- Added support for saving and restoring the last known playback position everywhere, and each display's custom render
  distance across game restarts
- Added distance-based quality: a display now steps its video quality down as you move away from it (one step at 66% of
  its render distance, two steps at 75%)
- Added YouTube chapter markers
- Added a filter button
- Added click sounds when clicking buttons in the display menu
- Gray-out only seekbar and near buttons when the display isn't ready yet, instead of the whole menu
- Added [Crowdin](https://crowdin.com/project/dreamdisplayx) integration
  ([#141](https://github.com/Aruvelut-123/dreamdisplaysx/pull/141))
- Brought back `NeoForge` 1.21.11 releases to the [corporate ad dispenser](https://www.curseforge.com/)

### Improvements

- Improved displays performance ([#131](https://github.com/Aruvelut-123/dreamdisplaysx/pull/131))
- Reduced native decode-path overhead on every frame, for smoother in-process playback
- Increased stall watchdog threshold from 30 to 45 seconds to avoid false positives on slow networks
- Added scrubbing preview on the seek bar (frame preview on hover)
- Repeat all videos for all platforms on every playback mode (server must be 1.9.0 or higher)
- Renamed the `Synchronization` setting to `Playback mode` and its tooltip now briefly explains each mode (`Local`,
  `Synced`, `Broadcast`)
- Default video quality is now 1080p instead of 720p, falling back to the closest available lower quality when 1080p
  isn't offered
- Raised the `Broadcast` quality cap from 360p to 720p
- The quality performance warning in the display menu now only appears above 1080p instead of at 1080p and up
- Enhanced UI components ([#148](https://github.com/Aruvelut-123/dreamdisplaysx/pull/148))
- Now recommendations are endlessly scrolling
- Enhanced ambient grid ([#136](https://github.com/Aruvelut-123/dreamdisplaysx/pull/136))
- All sliders now have snap behavior and fixed subdivisions for better precision
- Improved popout context menu positioning
- Improved scrollbars: now you can drag them
- Retry on "Not all references are available" error instead of fatal erroring
- Removed the greyed-out buttons state while a display isn't ready yet
- Added an author's avatar and a verified badge next to their name
- Enhanced cursor handling for 1.21.11
- Improved `Gradle` build system, so it looks less like a frankenstein
  ([#150](https://github.com/Aruvelut-123/dreamdisplaysx/pull/150))
- Improved platform resources structure
- Codebase improvements: more Kotlin analogues instead of Java imports, optimized imports
- Improved KDoc documentation in the codebase
- Improved wiki

### Fixes

- Fixed video sometimes freezing indefinitely
- Fixed a `Synced` / `Broadcast` display sometimes getting stuck on "Waiting for video..." forever
  ([#138](https://github.com/Aruvelut-123/dreamdisplaysx/issue/138))
- Fixed "Unrecoverable stream failure" error when using Iris shaders
  ([#146](https://github.com/Aruvelut-123/dreamdisplaysx/issue/146))
- Fixed live resume, live quality switches, and stall recovery blocking every other play / pause / seek / etc. action on
  the display for the whole network re-resolve
- Fixed some videos getting a permanently broken stream (403 Forbidden) instead of falling back to a working one
- Fixed retries being silently unlimited when a resolved stream failed to open right away
- Fixed the background quality refresher endlessly restarting a live stream when the closest available rendition didn't
  exactly match the requested quality
- Fixed video getting stuck when seeking right after changing quality
  ([#121](https://github.com/Aruvelut-123/dreamdisplaysx/issues/121))
- Fixed a failed quality switch permanently blocking re-selecting that same quality
- Loop `Local` displays on instead of freezing
- Fixed disappearing suggestions in some cases after a stutter / lag spike, requiring a seek to unstick it
- Fixed disappearing video preview when pausing and returning to the menu
- Fixed restoring display snapshots from prior sessions
- Fixed a display first sighted outside the client's render distance staying invisible until the server's next periodic
  broadcast
- Fixed a stale pre-seek frame occasionally slipping through and briefly rewinding the picture right after a seek
- Fixed the reappearance bridge occasionally playing audio from just before a seek instead of the resumed position
- Fixed occasional stutter and dropped opening frames right after opening or seeking a video, caused by a leftover
  `FFmpeg` buffering flag
- Fixed the warm-park pool TTL being too short
- Fixed the display menu's video preview being fit to the display's own block shape instead of the video's aspect ratio
- Fixed audio diagnostics from a just-ended session occasionally being spliced into the next one's error report
- Suppressed repeat media errors for 15 s after retry

## Server

### Features

- Support `Bungeecord` and `Velocity`
- `NeoForge` server support ([#95](https://github.com/Aruvelut-123/dreamdisplaysx/issues/95))
- New command: `/display fullscreen` for events and presentations
  ([#135](https://github.com/Aruvelut-123/dreamdisplaysx/pull/135))
- Full `LuckPerms` support on `Fabric` / `NeoForge` servers
  ([#128](https://github.com/Aruvelut-123/dreamdisplaysx/pull/128))
- Added a `[custom_media]` config section and a `dreamdisplayx.custom` permission to control whether players may play
  their own links (Vimeo, Kick, and direct files), with optional per-host allow / blocklists
- `/display video` now accepts any supported link, not only YouTube URLs
- Added `max_displays_per_player` config limit and wired up the `create_bypass` permission and
  `fullscreen.quality_cap` setting
- Added explosion protection for `Fabric` and `NeoForge` servers

### Improvements

- Reduced per-player memory overhead on long-running servers with many unique joins
- `Dream DisplaysX` security improvements
- Added a `storage.use_ssl` option in `config.toml` to enable TLS on the `MySQL` connection (was hardcoded off)
- Repeat all videos for all platforms on every playback mode
  ([#127](https://github.com/Aruvelut-123/dreamdisplaysx/pull/127))
- `Fabric` / `NeoForge` server display deletion now notifies nearby clients itself, matching `Paper`, so a future caller
  can't forget to broadcast
- `/display delete` is now available to everyone for their own displays
- Added hover and preview fade effects, plus a ghost handle, to the seekbar
- More modularization and cleanup, more Kotlin analogues instead of Java imports, optimized imports
- Improved KDoc documentation in the codebase
- Improved wiki

### Fixes

- Fixed display areas not being protected from enderman pickup, wither / silverfish breaking blocks, fire, and
  self-exploding blocks (beds, respawn anchors)
- Fixed some display commands being accepted from a player who isn't actually near the display
- Fixed a forced-locked `Broadcast` display being switchable back to another mode
- Fixed a reported video duration being trusted from any player anywhere on the server
- Fixed the Picture-in-Picture pin packet being able to flood the server with disk writes
- Now fullscreen command flags are fixed to stop command-tree blowup on join
  ([#164](https://github.com/Aruvelut-123/dreamdisplaysx/issue/164))
- Fixed displays removed by the startup material-validation sweep not telling online players to forget them, leaving
  ghost displays until reconnect
- Fixed deleted displays leaking their legacy v1 sync state, which kept being carried by the periodic broadcast
- Fixed the staggered display list send to a joining player not stopping when the player disconnected mid-send

# 1.9.0 Preview 5

## Highlights

- Hotfix: fixed explosion display protection mixin crash
- Fixed audio issues in `Fabric` 1.21.1 & 1.21.11 versions

## Client

### Fixes

- Fixed audio issues when in `Enhanced` / `Advanced` 3D audio mode for `Fabric` 1.21.1 & 1.21.11 versions
- Fixed playback picked the lowest-bitrate rendition of an untagged track

## Server

### Fixes

- Fixed explosion display protection mixin crash ([#161](https://github.com/Aruvelut-123/dreamdisplaysx/issue/161))

# 1.9.0 Preview 4

## Highlights

- Custom videos: paste a direct link to any video and play it on a display
- File-host share link support — Google Drive, Dropbox, imgur, etc.
- Kick and Vimeo support
- Filter button
- Some fixes and improvements

## Client

### Features

- Added custom video support, so you can paste a direct link to any video and play it on a display (server must be 1.9.0
  or higher)
- Added file-host share link support — Google Drive, Dropbox, imgur, etc. (server must be 1.9.0 or higher)
- Added Kick support
- Added Vimeo support
- Added filter button

### Improvements

- Repeat all videos for all platforms on every playback mode (server must be 1.9.0 or higher)

## Server

### Features

- Added a `[custom_media]` config section and a `dreamdisplayx.custom` permission to control whether players may play
  their own links (Vimeo, Kick, and direct files), with optional per-host allow / blocklists
- `/display video` now accepts any supported link, not only YouTube URLs
- Added `max_displays_per_player` config limit and wired up the `create_bypass` permission and `fullscreen.quality_cap`
  setting
- Add explosion protection for `Fabric` and `NeoForge` servers

### Improvements

- Repeat all videos for all platforms on every playback mode

### Fixes

- Fixed the Picture-in-Picture pin packet being able to flood the server with disk writes
- Fixed a reported video duration being trusted from any player anywhere on the server
- Fixed some display commands being accepted from a player who isn't actually near the display
- Fixed a forced-locked `Broadcast` display being switchable back to another mode
- Fixed display areas not being protected from enderman pickup, wither / silverfish breaking blocks, fire, and
  self-exploding blocks (beds, respawn anchors)
- Fixed HTTP responses (media metadata, thumbnails, resolved segments) being buffered into memory with no size limit
- Fixed Velocity / proxy disconnect from unsynced fullscreen command argument type
  ([#138](https://github.com/Aruvelut-123/dreamdisplaysx/issue/153))

# 1.9.0 Preview 3

## Highlights

- 3D audio support
- Language selector right in the display menu
- Now recommendations are endlessly scrolling
- [Crowdin](https://crowdin.com/project/dreamdisplayx) platform integration
- Fixed some bugs, including crash on `Fabric` 1.21.11
- New Gradle build system
- Some other minor improvements

## Client

### Features

- Added 3D acoustics for displays: sound is now muffled by walls (occlusion), loses highs over distance (air
  absorption), and picks up room / cave reverberation raytraced from nearby blocks and their material; e.g., stone
  reflects, wool absorbs ([#147](https://github.com/Aruvelut-123/dreamdisplaysx/pull/147))
- Added audio track selector, so you can select your language right in the display menu
  ([#149](https://github.com/Aruvelut-123/dreamdisplaysx/pull/149))
- Added subtitles support ([#151](https://github.com/Aruvelut-123/dreamdisplaysx/pull/151))
- Added click sounds when clicking on buttons in the display menu
- Added [Crowdin](https://crowdin.com/project/dreamdisplayx) integration
  ([#141](https://github.com/Aruvelut-123/dreamdisplaysx/pull/141))

### Improvements

- Enhanced UI components ([#148](https://github.com/Aruvelut-123/dreamdisplaysx/pull/148))
- Now recommendations are endlessly scrolling
- Enhanced cursor handling for 1.21.11
- Improved popout context menu positioning
- Improved scrollbars: now you can drag them
- Retry on "Not all references are available" error instead of fatal erroring
- Removed greying out buttons feature when the display is not ready yet
- Added author's avatar by their name
- Added verified badge by author's name
- Improved Gradle build system, so that looks less like a frankenstein
  ([#150](https://github.com/Aruvelut-123/dreamdisplaysx/pull/150))
- Improved platform resources structure
- Use more Kotlin analogues instead of Java imports
- Optimized imports

### Fixes

- Fixed "Unrecoverable stream failure" error when using Iris shaders
  ([#146](https://github.com/Aruvelut-123/dreamdisplaysx/issue/146))
- Fixed a `Synced` / `Broadcast` display sometimes getting stuck on "Waiting for video..." forever
  ([#138](https://github.com/Aruvelut-123/dreamdisplaysx/issue/138))
- Fixed disappearing video preview when pausing and returning to the menu
- Fixed video sometimes freezing indefinitely
- Fixed disappearing suggestions in some cases after a stutter / lag spike, requiring a seek to unstick it
- Fixed retries being silently unlimited when a resolved stream failed to open right away
- Fixed some videos getting a permanently broken stream (403 Forbidden) instead of falling back to a working one
- Loop `Local` displays on instead of freezing

## Server

### Fixes

- Fixed crash on `Fabric` 1.21.11 caused by invalid `BareTokenArgumentType` registration
  ([#137](https://github.com/Aruvelut-123/dreamdisplaysx/issue/137))
- Use more Kotlin analogues instead of Java imports
- Optimized imports

# 1.9.0 Preview 2

## Highlights

- Hotfix: fixed an issue that could significantly increase world loading times for all players
- Brought back `NeoForge` 1.21.11 releases to the [corporate ad dispenser](https://www.curseforge.com/)
- Now you can set video URL in `/display fullscreen` command
- Some other minor improvements

## Client

### Features

- Brought back `NeoForge` 1.21.11 releases to the [corporate ad dispenser](https://www.curseforge.com/)

### Improvements

- Renamed the `Synchronization` setting to `Playback mode` and its tooltip now briefly explains each mode
- (`Local`, `Synced`, `Broadcast`)
- Fullscreen displays in Picture-in-Picture mode are now 33% bigger
- Now fullscreen and Picture-in-Picture displays survive rejoins
- Updated messages mentioning `YouTube` to also reflect `Twitch` support

## Server

### Improvements

- Enhanced target selector format for `/display fullscreen` (e.g. `@a` no longer needs quotes)
- Added `url` option for `/display fullscreen`
- Added `/display fullscreen` to `/display help`, and incomplete `/display fullscreen` commands now show its usage
  instead of a generic error
- Updated messages mentioning `YouTube` to also reflect `Twitch` support
- Added hover and preview fade effects to seekbar
- Added ghost handle when seeking

### Fixes

- Fixed an issue that could significantly increase world loading times
- Fixed players being unable to join at all right after the previous fix, caused by the new `/display fullscreen`
  selector format

# 1.9.0 Preview 1

## Highlights

- `NeoForge` server support, including single-player
- Full Twitch support alongside YouTube
- New `/display fullscreen` command, great for events and presentations
- Scrubbing preview on the seek bar
- Full `LuckPerms` integration
- UI, resolving, codebase improvements and some bugfixes

## Client

### Features

- `NeoForge` server support (including single-player) ([#95](https://github.com/Aruvelut-123/dreamdisplaysx/issues/95))
- Full Twitch support ([#129](https://github.com/Aruvelut-123/dreamdisplaysx/pull/129))
- New Borderless and Fullscreen display modes ([#135](https://github.com/Aruvelut-123/dreamdisplaysx/pull/135))
- New command: `/display fullscreen` for events and presentations
  ([#135](https://github.com/Aruvelut-123/dreamdisplaysx/pull/135))
- Added support for saving and restoring the last known playback position everywhere
- Added support for saving and restoring each display's custom render distance across game restarts

### Improvements

- Enhanced UI components
- Now all sliders have snap behavior and fixed subdivisions for better precision
- Improved displays performance ([#131](https://github.com/Aruvelut-123/dreamdisplaysx/pull/131))
- Added scrubbing preview on the seek bar (frame preview on hover)
- Reduced native decode-path overhead on every frame, for smoother in-process playback
- Increased stall watchdog threshold from 30 to 45 seconds to avoid false positives on slow networks
- Enhanced ambient grid ([#136](https://github.com/Aruvelut-123/dreamdisplaysx/pull/136))
- Codebase improvements

### Fixes

- Fixed restoring display snapshots from prior sessions
- Fixed the background quality refresher endlessly restarting a live stream when the closest available rendition didn't
  exactly match the requested quality
- Fixed the display menu's video preview being fit to the display's own block shape instead of the video's aspect ratio
- Fixed video getting stuck when seeking right after changing quality
  ([#121](https://github.com/Aruvelut-123/dreamdisplaysx/issues/121))
- Fixed a stale pre-seek frame occasionally slipping through and briefly rewinding the picture right after a seek
- Fixed a failed quality switch permanently blocking re-selecting that same quality
- Fixed the reappearance bridge occasionally playing audio from just before a seek instead of the resumed position
- Fixed live resume, live quality switches, and stall recovery blocking every other play / pause / seek / etc. action on
  the display for the whole network re-resolve
- Fixed a display first sighted outside the client's render distance staying invisible until the server's next periodic
  broadcast
- Fixed audio diagnostics from a just-ended session occasionally being spliced into the next one's error report
- Fixed occasional stutter and dropped opening frames right after opening or seeking a video, caused by a leftover
  `FFmpeg` buffering flag
- Fixed the warm-park pool TTL being too short

## Server

### Features

- `NeoForge` server support ([#95](https://github.com/Aruvelut-123/dreamdisplaysx/issues/95))
- Full `LuckPerms` support on `Fabric` / `NeoForge` servers
  ([#128](https://github.com/Aruvelut-123/dreamdisplaysx/pull/128))
- New command: `/display fullscreen` for events and presentations
  ([#135](https://github.com/Aruvelut-123/dreamdisplaysx/pull/135))

### Improvements

- `/display delete` is now available to everyone for their own displays
- Codebase improvements: more modularization and cleanup
  ([#127](https://github.com/Aruvelut-123/dreamdisplaysx/pull/127))
- `Fabric` / `NeoForge` server display deletion now notifies nearby clients itself, matching `Paper`, so a future caller
  can't forget to broadcast
- Added a `storage.use_ssl` option in `config.toml` to enable TLS on the `MySQL` connection (was hardcoded off)
- Reduced per-player memory overhead on long-running servers with many unique joins
- Dream DisplaysX security improvements
- Codebase improvements

### Fixes

- Fixed displays removed by the startup material-validation sweep not telling online players to forget them, leaving
  ghost displays until reconnect
- Fixed deleted displays leaking their legacy v1 sync state, which kept being carried by the periodic broadcast
- Fixed the staggered display list send to a joining player not stopping when the player disconnected mid-send

# 1.8.8 Release

## Highlights

- UI improvements like gradient fill, shimmer effects, and more place for suggestions
- Enhanced media player stability and performance; now seeks are smoother and faster
- Enhanced native logging and error handling
- Fix `Fabric` 1.21.1 display rendering
- Fixed some minor issues and edge cases

## Client

### Features

- Added gradient fill and shimmer effects for thumbnails and loading states

### Improvements

- Pausing now keeps the decoder warm on every pipeline, so resume is instant instead of restarting the stream
- Seeking no longer tears the whole session down before reconnecting: the picture holds its last frame while the target
  position warms up in the background, then jumps — including the loop wrap-around in synced / broadcast modes
- The first decoded frame is now shown immediately on start and seek instead of waiting for the playback cushion to fill
- Stream startup got faster: `FFmpeg` no longer probes the container with its default 5 MB / 5 s window
- Stall recovery now reconnects in the background while the picture holds its last frame instead of blanking
- Reduced render overhead on 26.x versions
- Removed unreachable frame-resize handling from the video reader loop
- Updated the in-process YouTube resolver `NewPipeExtractor`
- Enhanced `LAV` decoder with cached packets
- Enhanced natives logging and error handling
- Enhanced thumbnail's quality

### Fixes

- Fixed `Fabric` 1.21.1 display rendering
- Fixed two different videos sharing the same thumbnail when their IDs differed only by letter case
- Fixed a failed thumbnail registration being able to crash the game
- Fixed a rare race during quality switches that could destroy the live display textures and leave the screen rendering
  through dead handles
- Fixed display teardown leaving already-freed textures reachable, which could lead to rendering through dead handles
- Fixed player initialization callbacks getting lost when registered right as initialization finished
- Fixed the reappearance audio bridge potentially starting mid-sample, which could produce noise
- Fixed several retrying displays being able to block every other display's initialization
- Fixed leaked `yt-dlp` subprocesses when the fast in-process resolver crashed mid-race

## Server

### Fixes

- Fixed displays with long URLs on `MySQL`
- Fixed display deletion logic and enhanced `Multiverse`-like projects compatibility
- Fixed legacy sync packets being able to store an arbitrarily large video duration on the server

# 1.8.7 Release

## Highlights

- Hotfix for 1.21.1 servers
- Fixed video freezing / losing audio after seeking
- Fixed default volume protocol
- Better shaders compatibility

## Client

### Improvements

- Enhanced compatibility with specific shaders

### Fixes

- Fixed default volume protocol that was not working
- Fixed video freezing / losing audio after seeking or pausing when the selected quality isn't actually available
  ([#121](https://github.com/Aruvelut-123/dreamdisplaysx/issues/121))
- Fixed video restarting right at the end of a video when the audio track finished a moment before the video did
- Fixed z-fighting when player is very far away from the display

## Server

### Fixes

- Fixed Java 25 conflict in 1.21.1
- Fixed default volume protocol that was not working
- Fixed doubled message about plugin's startup

# 1.8.6 Release

## Highlights

- Hotfix for `Fabric` 1.21.11 & 26.1.2

## Client

### Improvements

- Enhanced versionizing for Modrinth and GitHub releases

### Fixes

- Fix critical crash for `Fabric` 1.21.11 & 26.1.2 ([#118](https://github.com/Aruvelut-123/dreamdisplaysx/issues/118))

## Server

No changes.

# 1.8.5 Release

## Highlights

- 1.21.1 support
- All displays now default to 50% volume
- Fix critical crash on `Fabric`
- Some fixes and codebase improvements

## Client

### Features

- Added support for Minecraft 1.21.1

### Improvements

- All displays now default to 50% volume
- Previews have replaced snapshots
- Now versions have pretty style format
- Discord publisher integration
- Replaced `GSON` library with `kotlinx.serialization` for better maintainability and performance
- Enhanced safety comments in unsafe blocks in Rust natives
- Changed author's name arsmotorin to arnodoelinger
- Some dependecies updates

### Fixes

- Fixed Picture-in-Picture playing ahead of the in-world display; it now stays in sync
- Fixed critical crash when trying to delete an invalid display in single-player on `Fabric`

## Server

### Features

- Added support for Minecraft 1.21.1
- Added `default_volume` option in `config.toml`, so server owners can now set the default volume for all players

### Improvements

- Previews have replaced snapshots
- Now versions have pretty style format
- Replaced `GSON` library with `kotlinx.serialization` for better maintainability and performance

### Fixes

- Fixed critical crash when trying to delete an invalid display on `Fabric` servers

# 1.8.4 Release

## Client

### Improvements

- Improved experimental API
- Readded 26.2 version to `Paper` building system
- Improved media playback smoothness, especially around frame pacing and short playback stalls
- Improved pause and resume behavior, including warm resume for supported sessions
- Improved video loading, thumbnails, search suggestions, and replay caches for faster repeated loads
- Improved media links, network requests, and JSON handling for more consistent video resolving
- Improved local display settings saving so settings survive crashes and future updates better
- Reduced extra background threads in media tasks
- Synced and broadcast displays now default to 50% volume instead of 100%
- Improved Dream DisplaysX security

### Fixes

- Fixed incompatibilities with high-quality shaders ([#108](https://github.com/Aruvelut-123/dreamdisplaysx/issues/108))
- Fixed unnecessary sync corrections while media is paused or parked
- Fixed a rare internal service lookup issue that could affect features with multiple service implementations

## Server

### Improvements

- Improved experimental API
- Improved report cooldown handling under repeated report attempts
- Improved media links, network requests, and JSON handling for server-side media features
- Improved saved display storage so display data is safer across restarts and crashes
- The mod update notification is now shown once per server session
- Improved Dream DisplaysX security

### Fixes

- Fixed several report cooldown edge cases
- Fixed the mod update notification formatting on `Fabric` servers
- Fixed several packet protocol v2 validation edge cases during connection and packet decoding
- Fixed audio-language validation before saving and rebroadcasting it
- Fixed a rare internal service lookup issue that could affect features with multiple service implementations

# 1.8.3 Release

## Client

### Improvements

- Improved experimental API
- Hardened background maintenance tasks against hanging the game on exit
- Reworked background networking, thumbnail, and cache work onto a unified coroutine scheduler for cleaner shutdown and
  fewer idle threads
- Display targeting now only triggers on the screen's own block face instead of the whole block
- Enhanced documentation in codebase
- Updated version dependencies
- Improved Dream DisplaysX security

### Fixes

- Fixed 360p quality lock in some cases
- Fixed the display menu preview blitting a just-released texture during a quality switch, causing repeated "Missing
  resource" warnings and a GL error

## Server

### Improvements

- Improved experimental API
- Improved display data saving
- Improved version parsing
- Moved webhook reports and `Fabric` database saves off the main server thread
- Enhanced documentation in codebase
- Updated version dependencies
- Improved Dream DisplaysX security

### Fixes

- Fixed periodic display / player update ticks running on an async scheduler on `Paper` servers
- Fixed unsafe async `Bukkit` / `Paper` API usage
- Fixed displays not being saved until the server shuts down cleanly, so a crash could lose newly created or edited
  displays
- Fixed display owners on `Paper` servers needing extra permission to delete their own display, unlike `Fabric`
- Fixed a malformed legacy network packet being able to crash decoding instead of being safely rejected
- Fixed broadcast displays briefly losing their quality clamp right after reconnecting until the server resent it
- Fixed the display cache file being able to get corrupted if the game / server crashed mid-save
- Fixed a race that let concurrent reports slip past the report cooldown
- Fixed default permissions; (local), synced and broadcast are for all players, no only for OPs

# 1.8.2 Release

## Client

### Improvements

- YouTube videos now load a bit faster
- Smoothed out a brief stutter that could happen right when a video changed
- Tightened how video links are handled, with length limits and network-only access to keep them from being abused
- Enhanced error screen when video loading fails
- Enhanced video loading animation
- Added 26.2 version to Paper building system
- Improved Dream DisplaysX security

### Fixes

- Fixed audio cutting out after about 10 seconds ([#107](https://github.com/Aruvelut-123/dreamdisplaysx/pull/107))
- Fixed repeating video playback in local playback mode

## Server

### Improvements

- Players can no longer spam the report system
- Improved Dream DisplaysX security

# 1.8.1 Release

## Client

### Features

- Added experimental support of native optimizations for 1.21.11

### Improvements

- Improved translations for Russian and Ukrainian languages
- Improved `FFmpeg` download logging and unpacking flow
- Adopted Rust 2024 edition for natives and enhanced log handling

### Fixes

- Fixed vertex format crash on `Fabric` 1.21.11
- Reduced log spam

## Server

### Improvements

- Improved translations for Russian and Ukrainian languages

### Fixes

- Single-player displays are now stored per-world instead of the global database
- Replaced hardcoded max dimensions with placeholders

# 1.8.0 Release

## Highlights

- Added support for Minecraft 26.2
- Brought back Minecraft 1.21.11 support ([#91](https://github.com/Aruvelut-123/dreamdisplaysx/pull/91))
- Added a native Rust media pipeline with `FFmpeg` and in-process LAV decoding
- Added stable `Vulkan` support for display rendering (`OpenGL` rendering is still supported)
- Replaced the old synchronization mode with new local, synced, and broadcast playback modes
- Added a new packet protocol v2
- Reduced CPU usage by up to 50–70× on tested hardware scenarios (Java 25 required)
- Improved video stream resolving speed by up to 10–12× in supported cases

## Client

### Features

- Added support for Minecraft 26.2
- Brought back Minecraft 1.21.11 support ([#91](https://github.com/Aruvelut-123/dreamdisplaysx/pull/91))
- Added a new packet protocol v2
- Added fallback support for protocol v1, but v1 is now deprecated and will be removed in the future
- Introduced an unstable client-side API that will be scaled in the future
- Switched the multiversion system to `Stonecutter`, so old versions will be supported too
- Added stable `Vulkan` support for display rendering (`OpenGL` rendering is still supported)
- Replaced the old synchronization mode with new playback modes (server 1.8.0+ required)
- Added local, synced, broadcast playback modes (server 1.8.0+ required)
- Support vertical displays (server 1.8.0+ required)
- Added a native Rust media pipeline
- Integrated `FFmpeg` into the native media pipeline
- Added in-process LAV backend for video decoding
- Added GPU YUV / NV12 rendering path
- Added planar display textures for native video frames
- Added dynamic frame format support for native video frames
- Added improved cursor handling in the display menu
- Increased the default render distance to 96 blocks
- Switched display visibility logic from block-based checks to chunk-based checks
- Increased the effective display rendering range from 2 chunks to 12 chunks
- Reduced CPU usage by up to 50× on tested mid-range hardware scenarios (Java 25 required)
- Reduced CPU usage by up to 70× on tested low-end hardware scenarios (Java 25 required)
- Improved video stream resolving speed by up to 10–12× in supported cases
- Added seamless and faster video quality changes
- Improved shader compatibility
- Added more anonymous telemetry data to improve development, compatibility, and stability
- Added a fresher mod icon
- Improved several menu icons

### Improvements

- Improved media player performance thanks to the native media pipeline
- Improved video frame processing stability
- Improved brightness handling in the video frame pipeline
- Added a more efficient native video frame path
- Reduced expensive CPU-side frame conversion work
- Improved GPU upload behavior for video frames
- Improved realtime-safe stream selection
- 60 FPS stream selection is now opt-in
- Improved `yt-dlp` quality fallback logic
- Improved `yt-dlp` resolver failure handling
- Improved video startup behavior when stream resolving fails
- Improved detection of DRM-protected videos
- DRM-protected videos now fail faster and more gracefully
- Improved cookie handling
- Improved process management for external media tools
- Improved display rendering stability on larger displays
- Improved display rendering stability at longer distances
- Improved compatibility with shader mods
- Improved compatibility with `VulkanMod`
- Improved Picture-in-Picture display sizing logic
- Improved display menu behavior on different GUI scales
- Improved display menu icon behavior
- Improved locked display handling
- Improved temporary focus mute behavior
- Improved unsafe filename handling for server display cache files
- Improved client texture creation validation
- Replaced the old `AbstractConfig` usage with the default config implementation
- Replaced custom logging usage with LoggerFactory
- Reorganized the project structure
- Improved Gradle configuration
- Improved workflows
- Improved the publishing system
- Removed old Gradle cache configuration
- Removed INotSleep's utils
- Simplified multiple internal code paths
- Cleaned up old compatibility code
- Updated dependencies
- Added many small internal cleanups, simplifications, and stability improvements

### Fixes

- Fixed a critical crash on `Fabric` 1.21.11
- Fixed a critical `Quilt` entry point crash
- Fixed an ancient `NeoForge` and IntelliJ IDEA compatibility issue
- Fixed `NeoForge` client shutdown on normal server disconnect
- Fixed FFmpeg extraction on Linux ([#93](https://github.com/Aruvelut-123/dreamdisplaysx/issues/93))
- Fixed incompatibility between the popout window and `Vivecraft`
- Fixed GUI scale handling in the display menu
- Fixed several shader compatibility issues
- Fixed `VulkanMod` compatibility issues
- Fixed strange red and green screen blinking while loading videos
- Fixed quality fallback to 360p when `yt-dlp` fails
- Fixed incorrect waiting behavior for DRM-protected videos
- Fixed Picture-in-Picture mode display size calculation
- Fixed render distance localization
- Fixed locked display abuses
- Fixed the false locked display icon in the display menu
- Fixed temporary focus mute overwriting the user's mute setting
- Fixed unsafe server display cache filenames breaking on some systems
- Fixed invalid display sizes creating broken client textures
- Fixed several display menu edge cases
- Fixed several native frame pipeline edge cases
- Fixed several video resolver edge cases
- Fixed several display rendering edge cases
- Fixed multiple small stability issues

## Server

### Features

- Added support for Minecraft 26.2 `Fabric` servers
- Implemented Minecraft 1.21.11 support for `Fabric` servers
- Added support for the new playback modes
- Added Java 21 support for Minecraft 1.21.11 servers
- Added a new packet protocol v2
- Added fallback support for protocol v1, but v1 is now deprecated and will be removed in the future
- Added `dreamdisplayx.local`, `dreamdisplayx.synced`, `dreamdisplayx.broadcast`, `dreamdisplayx.lock`,
  `dreamdisplayx.delete.others`, and `dreamdisplayx.create.bypass` permissions
- Added more anonymous telemetry data to improve development, compatibility, and stability

### Improvements

- Simplified server-side display storage updates
- Removed the old display validator flow
- Improved server-side handling of display-enabled state updates
- Removed the useless report button in single-player
- Improved Gradle configuration
- Improved server module structure
- Updated dependencies
- Added multiple small server-side cleanups and simplifications

### Fixes

- Fixed `MariaDB` compatibility issue ([#88](https://github.com/Aruvelut-123/dreamdisplaysx/pull/88))
- Fixed sending display enabled packets to clients
- Fixed several `Fabric` server compatibility issues
- Fixed several small server-side stability issues

# 1.7.1 Release

## Client

### Features

- A bit fresher mod icon

### Improvements

- Better version publishing on Modrinth
- Reduce JAR size by ~50%

### Fixes

- Fabric config parsing error
- NeoForge `set_locked` packet error

## Server

### Improvements

- Some code refactoring
- Reduce JAR size by ~50%

### Fixes

- `FabricDisplayData` error when server shutdowns

# 1.7.0 Release

## Highlights

- Support 26.1.2 version and Java 25
- Support `Fabric` servers
- Support YouTube shorts
- Windowed and Picture-in-Picture mode
- Hardware-accelerated `FFmpeg` video decoding
- Show max 72 recommended videos based on the current video instead of 24
- Switch from RGBA to RGB24 for improved rendering performance
- Fix the "You have to look at the display block" error when there is actually display
  ([#79](https://github.com/Aruvelut-123/dreamdisplaysx/issues/79))

## Client

### Features

- Support 26.1.2 version and Java 25
- Support `Fabric` servers
- Support YouTube shorts
- Windowed and Picture-in-Picture mode
- Hardware-accelerated `FFmpeg` video decoding
- Show max 72 recommended videos based on the current video instead of 24

### Improvements

- Switch from RGBA to RGB24 for improved rendering performance
- Videos now stop rendering (but still play) when Minecraft is minimized
- Enhance watchdog logic for low-connection networks and stability
- Enhance YouTube's cache for stability
- Skip restoring saved time if sync is active
- Preserve sync mode when switching videos
- Reduce maximum brightness from 200% to 100%
- Deprecate `/display` command (will be replaced by direct interaction with displays in future versions)
- Add dynamic material messages
- Update dependencies and replace some of them with better alternatives

### Fixes

- Fix cropping at display edges
- Fix mute logic and allow players to mute displays in sync mode
- Fix admins can't delete displays through the menu
- Fix the "You have to look at the display block" error when there is actually display
  ([#79](https://github.com/Aruvelut-123/dreamdisplaysx/issues/79))
- Fix a strange version number in the menu ([#81](https://github.com/Aruvelut-123/dreamdisplaysx/issues/81))
- Fix version semantic versioning parsing for mod updates
- Fix tiled thumbnail rendering in the menu
- Fix texture race crash in some rare cases
- Fix a locked quality bug ([#80](https://github.com/Aruvelut-123/dreamdisplaysx/issues/80))
- Fix seek time overwriting the current playback time
- Fix hanging `yt-dlp` when cookies are unavailable

## Server

### Features

- Support `Fabric` servers
- Follow client's feature of lock / unlock displays
- Deprecate `/display` command (will be replaced by direct interaction with displays in future versions)

### Improvements

- Preserve sync mode when switching videos
- Broadcast synced display state every 2 seconds
- Add dynamic material messages
- Update dependencies and replace some of them with better alternatives

# 1.6.3 Release

## Mod

- Faster YouTube web operations and video loading
- Show max 24 recommended videos based on the current video instead of 12
- Load 3 displays simultaneously instead of 4 to avoid `yt-dlp` overloading
- Don't prefetch suggestions videos to avoid unnecessary `yt-dlp` calls
- Use different browser list for macOS for better compatibility
- Add `yt-dlp` proxy option in config
- Fix critical bug where displays prefetching even far away from the player
- Standardize logs, warnings and errors
- Reformat codebase

## Server

- Standardize logs, warnings and errors
- Reformat codebase

# 1.6.2 Release

## Mod

- Switch from `GStreamer` to `FFmpeg` which is more reliable and performant library for video playback
- Rewrite mod in Kotlin for better maintainability
- Huge mod optimizations and stability improvements
- Reduced CPU / GPU resource usage and improved performance significantly
- Allow seeking to any position on the progress slider
- Add `FFmpeg` automatic HTTP reconnection flags for resilient streaming over unstable networks
- Add watchdog timer that detects stalled `FFmpeg` processes and restarts streams automatically
- Retry on all transient errors (403, 404, 429, 5xx, connection resets, timeouts)
- Add error handling for expired YouTube URLs
- Fix brightness not saving properly
- Fix client null error in window focus handling
- Fix list of available qualities
- Fix `BufferOverflow` in specific edge cases
- Fix some edge cases of audio desynchronization after long playback
- Fix suggestion scroller not showing up when in large menu mode
- Fix language selector ([#73](https://github.com/Aruvelut-123/dreamdisplaysx/issues/73))
- Fix volume reset after leaving active display distance
  ([#76](https://github.com/Aruvelut-123/dreamdisplaysx/issues/76))
- Enhance project structure and code quality in some places

## Server

- Rate-limit sync packet broadcasting to prevent flooding when owner seeks rapidly
- Batch display info packets on player join to prevent client overload on servers with many displays
- Validate sync packet time values to reject out-of-range data

# 1.6.1 Release

## Mod

- Correct suggestion translations
- Fix video playback failing with a 403 Forbidden error when cached YouTube URLs expire – the player now automatically
  invalidates the stale cache entry and re-fetches fresh URLs from `yt-dlp` instead of permanently marking the screen as
  errored
- Reduce format URL cache TTL from 5 hours to 2 hours to avoid serving near-expired YouTube CDN links
- Improve error handling and timeout management in `yt-dlp` process execution

## Server

- No changes

# 1.6.0 Release

## Highlights

- Switch mod channel from Beta to Release
- Support YouTube livestreams (live, première, and regular streams)
- Direct searching and playback of YouTube videos without leaving the game
- Switch to Paper plugin, drop Bukkit and Spigot support
- Progress slider with seeking support
- Single unified pipeline for all content (merged video + audio)
- Rewrite seek and quality-change to use a single reliable pipeline rebuild
- Improved video quality and format detection

## Mod

- Switch mod channel from Beta to Release
- Support YouTube livestreams (live, première, and regular streams)
- Direct searching and playback of YouTube videos without leaving the game
- Suggested videos based on current video
- Progress slider with seeking support
- Mute and unmute buttons
- Improved display configuration UI
- Better UI icons in configuration
- Improved video quality and format detection
- Faster video loading and seeking with improved buffering and caching
- Rewrite seek and quality-change to use a single reliable pipeline rebuild
- Single unified pipeline for all content (merged video + audio)
- Better synchronization for video playback
- Video metadata caching system
- Some stability improvements
- Various optimizations and some small bug fixes
- Update dependencies

## Server

- Switch to Paper plugin
- Drop Bukkit and Spigot support
- Inform player about a display if they don't have the mod installed when they try to touch it
- Various optimizations and some small bug fixes

# 1.5.0 Release

## Highlights

- Switch YouTube playback to `yt-dlp`
- Improve video playback stability and reduce some lags
- Improve seeking, synchronization and buffering behavior
- Better detection of system GStreamer library path on macOS and Linux

## Mod

- Switch YouTube playback to `yt-dlp`
- Improve video playback stability and reduce some lags
- Improve seeking, synchronization and buffering behavior
- Improve video quality detection
- Better detection of system GStreamer library path on macOS and Linux
- Update Gradle to 9.4.0

## Server

- No changes

# 1.4.4 Release

## Mod

- Add Spanish, French and Italian translations

## Server

- Add `/display info` command for quick display information
- Add `/display list` filters (`mine`, `world <name>`, `owner <name>`, `sync`)
- Add translation for `/display list` command
- Improve `/display video` error feedback (separate invalid URL/not owner/wrong target block)
- Add total value output to `/display stats`
- Add admin target mode for `/display on|off <player>`
- Improve `/display reload` output with what was reloaded

# 1.4.3 Release

## Mod

- Update concurrency settings in build workflow
- Update dependencies
- Improve media player initialization handling and quality parsing
- Use thread-safe `ConcurrentHashMap` for display management
- Improved display sync stability

## Server

- Improved `/display video` URL parsing: now accepts direct video IDs and more YouTube link formats
  (watch/shorts/embed/live/youtu.be).
- Add paginated display listing with improved formatting
- Improved tab-completion: now it's case-insensitive
- Language suggestions for `/display video` when typing language parameter
- Add permission and validation checks for display deletion
- Better config mapping
- Improved display sync stability
- Player-only `/display` subcommands now return a clear console message instead of failing silently
- Fixed scheduler timing mismatch between Bukkit and Folia

# 1.4.2 Release

## Mod

- Update dependencies
- Fix remaining displays when world resets
- Fix floating displays without base material
- Remove unnecessary warnings and logs

## Server

- Fix remaining displays when world resets
- Fix floating displays without base material
- Handle failed config gracefully
- Remove unnecessary warnings and logs

# 1.4.1 Release

## Mod

- Fix releasing snapshots when pull requesting
- Add Kolyakot33 as a contributor
- Cleanup codebase

## Server

- Fix Bukkit/Spigot server support
- Fix selection visualizer for Folia servers
- Temporary disabled mod detection for Folia servers due to Folia scheduler problems
- Fix releasing snapshots when pull requesting
- Add Kolyakot33 as a contributor

# 1.4.0 Release

## Highlights

- Support Quilt
- Fix display directions not being created properly in some cases

## Mod

- Support Quilt
- Update dependencies
- Improve building workflow
- Cleanup codebase

## Server

- Fix display directions not being created properly in some cases
- Cleanup codebase

# 1.3.2 Release

## Mod

- Fix display deletion not working properly

## Server

- No changes

# 1.3.1 Release

## Mod

- Fix displays disappearing permanently when player walks out of render distance
- Displays now load immediately when entering render distance
- Fewer logs
- Updated dependencies

## Server

- Detect snapshot versions correctly

# 1.3.0 Release

## Highlights

- We've created a [Discord server](https://discord.gg/uwMMZ2KWk6)!
- Video brightness control
- Change maximum of render distance to 128 blocks ([#59](https://github.com/Aruvelut-123/dreamdisplaysx/issues/59))
- Change maximum volume to 200% ([#60](https://github.com/Aruvelut-123/dreamdisplaysx/issues/60))
- Support CurseForge releases
- Smoother video playback and some optimizations

## Mod

- We've created [Discord server](https://discord.gg/uwMMZ2KWk6)!
- Smoother video playback and some optimizations
- Video brightness control
- Store paused state of display
- Change maximum of render distance to 128 blocks ([#59](https://github.com/Aruvelut-123/dreamdisplaysx/issues/59))
- Change maximum volume to 200% ([#60](https://github.com/Aruvelut-123/dreamdisplaysx/issues/60))
- Fix playing videos after changing quality
- Support CurseForge releases
- Documentation in codebase of the mod

## Server

- Refactors and small improvements
- Documentation in codebase of the plugin
- Improve update logic and fix ignoring mod versions ([#63](https://github.com/Aruvelut-123/dreamdisplaysx/issues/63))

# 1.2.0 Release

## Highlights

- New, refreshed logo
- All messages from plugin are in client's language now
- New languages: Belarusian, Czech, German and Hebrew for plugin messages
- Add `/display help` and `/display stats` commands
- Fix an issue when after re-enabling displays they don't load until relog

## Mod

- New, refreshed logo
- All messages from plugin are in client's language now
- Add missing messages for some commands
- Remove client command `/displays` and move its functionality to plugin's `/display` command
- Improve README and wiki
- Show report button only if server has configured webhook URL
- Fix an issue when after re-enabling displays they don't load until relog

## Server

- New languages: Belarusian, Czech, German and Hebrew for plugin messages
- Improve permissions handling for `/display create` and `/display video`
- Add permission message when player lacks permission
- Improve `/display list` command output
- Add `/display help` and `/display stats` commands
- Add links to some messages
- Fix reporting message not showing correctly
- Fix wrong command usage message logic

# 1.1.3 Release

## Mod

- Fix sync packet registration issues
- Fix video playback time saving for non-synced displays
- Fix texture errors when changing video quality
- Fix NeoForge screen loading on server join

## Server

- No changes

# 1.1.2 Release

## Mod

- Fix missing translations
- Fix snapshot version detection as stable
- Better releases system of mod
- Update mappings

## Server

- Add message when client doesn't have the mod installed
- Better releases system of mod

# 1.1.1 Release

## Mod

- Fix display desynchronization with server and client
- A bit improved screen rendering
- Less logging
- Code cleanup

## Server

- Fix display desynchronization with server and client

# 1.1.0 Release

## Highlights

- Support 1.21.11 version
- Support NeoForge
- Huge reduction of CPU usage, more stable and optimized
- Store all displays from the servers
- Support more YouTube links
- Switched to Mojang mappings
- Plugin rewritten in Kotlin
- bStats

## Mod

- Support 1.21.11 version
- Support NeoForge
- Huge reduction of CPU usage, more stable and optimized
- Store all displays from the servers
- Support more YouTube links
- Don’t mute displays on alt-tab by default
- Better volume UI
- Switched to Mojang mappings
- Improved overall code quality
- Enhanced logging
- Improved wiki

## Server

- Fixed repeated update notifications when switching dimensions
- Refined, new configuration
- Enhanced particle effects for selections
- Created messages for empty report, display deletion, etc.
- Separated update logic between mod and plugin
- Plugin rewritten in Kotlin
- Improved overall code quality
- Corrected premium permission name
- Removed hourly update notifications from the console
- bStats

# 1.0.8 Release

## Mod

- Expanded max quality from 1080p to 4K
- Tips for removing and reporting display
- Warn player when switching to 1080p+

## Server

- Support Spigot and Bukkit servers
- New commands: /display list and /display reload
- More languages for plugin configuration
- .toml format for configuration files

# 1.0.7 Release

## Mod

- Discontinue FrogDisplays channel support

## Server

- Folia support
- Better comments in plugin configuration
- Discontinue FrogDisplays channel support

# 1.0.6 Release

## Mod

- Added Hebrew, Czech and Belarussian languages support
- Disabled volume relativity to Minecraft's volume
- Vanilla language system
- Improved volume configuration options
- Default video quality is now 720p instead of 480p
- Fixed GStreamer dead link

## Server

- Bump version

# 1.0.5 Release

## Mod

- Added multi-language support for Russian, Ukrainian, Polish and German

## Server

- Bump version

# 1.0.4 Release

## Mod

- Release channel is now Beta for Fabric
- Project is now pen-source with LGPL-3.0 license
- English is now the default language instead of Russian
- New documentation with proper project information
- Cleaned up redundant code and improved code quality
- Added support for old mod versions
- Added mod information
- New icon

## Server

- Release channel is now Release
- English as the default language
- New configuration
- New mod name Dream DisplaysX
- Added support for old mod clients
- Added plugin information

# 1.0.3 Release

## Mod

- Ignore GStreamer library if macOS

## Server

- First public version

# 1.0.2 Release

## Mod

- Added other languages for videos

## Server

- Bump version (not public)

# 1.0.1 Release

## Mod

- Fix client crash

## Server

- Bump version (not public)

# 1.0.0 Release

## Highlights

- First version

## Mod

- First version

## Server

- First version (not public)
