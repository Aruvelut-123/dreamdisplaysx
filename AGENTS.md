# Dream DisplaysX — AGENTS.md

## Project Overview

**Dream DisplaysX** is a fork of [Dream Displays](https://github.com/arnodoelinger/dreamdisplays) that puts video displays inside Minecraft — watch YouTube, Twitch, Bilibili, or any video on a wall in your world.

## Key Architecture

### Multi-Version Build
- Uses **Stonecutter** for multi-version builds (1.21.1, 1.21.11, 26.1.2, 26.2, 26.3)
- Active version in `versions/active.txt`
- Version gates: `//? if >=1.21.11 {}`, `//? if >=26 {}`, `//? if >=26.2 {}`
- Chisel generates per-version sources into `build/<ver>/generated/chisel/`

### Module Structure
- `api/` — Common API (serializable models, settings, services)
- `core/` — Protocol (packets, registry, PacketType enum)
- `media/` — Media playback (player, sources, runtime, audio)
- `platform/` — Platform-specific code
  - `client/` — Client-side (Fabric, NeoForge, common)
  - `server/` — Server-side (Fabric, NeoForge, Paper)
  - `proxy/` — Proxy (BungeeCord, Velocity)
  - `resources/` — Shared resources (lang, config)
- `util/` — Shared utilities (JSON, HTTP, etc.)

### Key Platforms
| Platform | Client Entry | Server Entry |
|----------|-------------|-------------|
| NeoForge | `platform/client/neoforge/.../Client.kt` | `platform/server/.../NeoForgeServerMod.kt` |
| Fabric | `platform/client/fabric/.../Client.kt` | `platform/server/.../FabricServer.kt` |
| Paper | — | `platform/server/.../PaperServer.kt` |

### Protocol
- v2/v3 protocol: V2 keeps the single `Envelope` (type id + protobuf bytes) over `dreamdisplayx:v2`; V3 adds the explicit-generation, batch-capable `V3Envelope` over `dreamdisplayx:v3` and automatically falls back to V2. Legacy V1 traffic is detected and reported in chat, but never handled. `ProtocolRouter` is the client routing seam.
- `PacketType.kt` — append-only enum, never reuse ids
- `PacketRegistry.kt` — register all packet serializers
- Client→Server packets: `ClientHello`, `PlaybackCommand`, `SetVideo`, etc.
- Server→Client packets: `ServerHello`, `PlatformCredentials`, `DisplayInfo`, etc.

### Series Configuration
- Server config: `config.toml` (TOML format)
- Client config: `config/dreamdisplayx/config.toml` (legacy `config.yml` migrates once)
- Client display settings: `client-display-settings.json` (per-display JSON)
- Permissions: LuckPerms support with `VanillaPermissions.Fallback` (EVERYONE/OP/NOBODY)

## Bilibili Login System

### Architecture
- **Client-side**: `BilibiliLoginManager` handles QR code login → sends to server via `/display login bilibili <sessdata>||<refresh>`
- **Server-side**: `CredentialActions.globalLogin/globalLogout` stores encrypted credential in `CredentialStore`
- **Broadcast**: `PlatformCredentials` packet sent to ALL online v2 clients on login/logout/refresh
- **Sync**: `SqlCredentialSyncBackend` stores encrypted credentials in `credentials` table (SQLite or MySQL)

### Key Files
| File | Purpose |
|------|---------|
| `credential-store/CredentialStore.kt` | AES-256-GCM encrypted at-rest store |
| `credential-store/CredentialActions.kt` | Login/logout/refresh logic |
| `credential-store/CredentialSyncBackend.kt` | Sync interface |
| `credential-store/SqlCredentialSyncBackend.kt` | SQLite/MySQL implementation |
| `credential-store/BilibiliSessionRefresher.kt` | Periodic SESSDATA refresh |

### Commands
- `/display login <platform> <token>` — OP-only, global login (broadcasts to all players)
- `/display logout <platform>` — OP-only, global logout
- `/dlogin` — Client-side, OP-only, opens QR login screen
- `/dlogoff` — Client-side, OP-only, logs out

### Permission Nodes
- `dreamdisplayx.login` — Default OP (LuckPerms supported)
- `dreamdisplayx.logout` — Default OP (LuckPerms supported)

## Changes & Commits

### Chisel gate syntax (hard rule)
- A `//?} else` branch MUST start its commented body on the next line with `/*` on that first line and close with `*/`. `Chisel.kt`'s `takeCommentedElse()` scans forward for `*/`; if the body never opens with `/*` it runs to EOF and every `chiselSource` task dies with `Unclosed /* else-branch */ starting at line N`.
- Never nest a `//? if` directive inside a commented else body. Chisel parses the inner directive as top level and the enclosing block stops matching. Extract the inner choice into a sibling function with its own gate instead (`VanillaSelectionVisualizer.cyanVector()`).
- Import and declaration gates must cover the same version range. `MinecraftScreenUtil` was imported inside `//? if <1.19.4 {` while `/dlogin` (gated `>=1.19`) calls it, so Chisel stripped the import from every generated source at 1.20.1+ and all seven version jobs died with `Unresolved reference 'MinecraftScreenUtil'`. Before pushing, grep the generated `build/<ver>/generated/chisel/` tree for the symbol, or check that its import sits in the ungated block.
- Legacy API shims belong in the gate of their *callers*, not of their users. `literalText`/`withChatColor` exist at every version (Texts.kt gates their bodies), but `ChatFormatting` and the helpers themselves only compile against the 1.19+ Component API, so both the imports and `sendClientChat` need `//? if >=1.19`.
- Gate the helper itself at the same bounds as its imports: a helper only referenced from an `else` body still gets compiled for every version that satisfies its own gate, so an unguarded helper that names a since-removed type breaks the modern versions it was never meant for (`Vector3f` above 1.21.11).

### Legacy (<1.20.5) server command trees
- `paper-api`'s `io.papermc.paper.command.brigadier.Commands` only exists from 1.20.5. `CommandRegistrar` and `GroupCommand` keep calling `Commands.literal` / `Commands.argument` verbatim; only their Paper imports sit inside `//? if >=1.20.5 { ... //?}`.
- Below that gate both names resolve from `registrar/LegacyPaperCommands.kt`, which lives in the same package (so no import is needed) and is itself wrapped in `//? if <1.20.5 {`.
- The shim's signatures MUST mirror paper-api exactly: `literal(name)` returns a **non-generic** `LiteralArgumentBuilder<CommandSourceStack>`, and `argument(name, type)` returns `RequiredArgumentBuilder<CommandSourceStack, T>`. Paper pins `S` to `CommandSourceStack`; a generic `fun <S> literal(...)` instead loses the inferred sender type and every `.requires { it.sender ... }` fails with `Cannot infer type for type parameter 'S'` across all versions — that mistake turned the whole CI matrix red once (commit `f6600d2e`, reverted by `c9b24e68`). Do not "simplify" the shim back to brigadier's generic factories.
- Paper-only argument types must not leak below the gate either: `CustomArgumentType` (1.20.5+) is replaced by a plain brigadier `ArgumentType<String>` implementation.

### `/display` is answered by the server (never wire a custom ArgumentType)
- `BareTokenArgumentType` (and any future custom type) MUST stay server-side. `ServerAnswered.kt`'s `answeredByServer()` wraps the root literal into `/display <command: greedyString()>` and answers every completion itself, so a client only ever sees vanilla `greedyString`.
- Both `/display` roots must call it: `VanillaCommandTree.build()` (Fabric/NeoForge/Forge) and `CommandRegistrar.buildDisplayCommand()` (Paper). Missing either one reintroduces the bug.
- Do NOT add a registry entry, a `NETWORK_FALLBACK`, a platform registrar or a wire-fallback mixin for `BareTokenArgumentType`. That whole apparatus existed only to compensate for the missing wrapper; Forge never got a registrar and crashed on world join with `Unrecognized argument type ... BareTokenArgumentType`. Fabric/NeoForge registered it and still shipped the type to clients.
- `ServerAnsweredTest` pins the contract: the client-facing tree carries exactly one vanilla `greedyString` node and no custom type.
- Consequence to accept: completions round-trip to the server (slight latency) instead of being computed client-side.

### SQLite JDBC must be a `shadow` dependency on every platform
- `org.xerial:sqlite-jdbc` is un-relocated (absent from `dreamDisplaysShadedPackages`) and only reaches a fat jar through `shadow(libs.sqliteJdbc)`. `runtimeOnly` is not enough: `ShadowJar` only unpacks the `shadow` configuration.
- Forge has no jarJar on 1.18.2-1.20.1, so a nested `META-INF/dreamdisplayx/sqlite-jdbc.jar` is dead weight; the `trimmedSqliteJdbc` + `isolatedSqliteJdbc` pair existed only to build that nested jar and was removed. Forge now mirrors Fabric: `shadow(libs.sqliteJdbc)` plus `excludeDreamDisplaysXSqliteNativeExtras()` inside `tasks.shadowJar`.
- Symptom of a regression: `StorageManager` dies on server start with `java.sql.SQLException: No suitable driver`, and the jar has zero `org/sqlite/` entries plus no `META-INF/services/java.sql.Driver`. Verify an artifact with `unzip -l <jar> | grep -c 'org/sqlite/'` (expect >0) instead of trusting the build script.
- `vendoredLibraries(libs.sqliteJdbc)` stays: it feeds the `runClient`/`runServer` dev classpath and is a different configuration from `shadow`. Do not delete it as a "duplicate".

### Per-platform GUI sprite assets must be real PNGs
- `platform/client/{fabric,neoforge,forge}/src/main/resources/.../textures/gui/sprites/widgets/` each hold their own `panel.png`/`dropdown.png`. The Forge copies were committed as **zero-byte files**, and pre-1.20.2 `LegacyGuiSprites` reads a sprite with `NativeImage.read` to learn its size, so opening the display menu threw `IOException: Image not of any type, or corrupt` on the render thread.
- `LegacyGuiSprites.load()` wraps those reads in `runCatching` and degrades to a 16x16 fill; a broken sprite must never crash rendering.
- `GuiSpriteAssetsTest` asserts every platform's `panel.png`/`dropdown.png` exists, is non-trivial and carries the PNG magic bytes. Extend its list when adding a per-platform sprite.
### Optional animated video-loading screen
- `animated-loading-screen` is a client-only TOML/Cloth toggle, default false; missing or invalid values must not opt existing users in.
- `LoadingScreenAnimation` and `LoadingScreenClock` own JVM-only timing/layout, while `LoadingScreenRenderer` batches static icon/glyph/round-brush textures on both flat and conforming world displays. Keep the real first frame and legacy error/disabled paths higher priority than the intro.
- The per-display clock uses media generations and monotonic time; no loading animation may delay playback, invoke native players, or rasterize fonts at runtime. Static PNG assets can be regenerated on Windows with `scripts/generate_loading_assets.ps1`; atlas metrics are validated by the common-module tests.
- Fabric and NeoForge Cloth settings are hand-built; adding a `configEntries()` descriptor alone does not expose a setting. Both platform screens must wire the toggle.

### 2026-10-04 — Playback review hardening
- Audio-track changes replace the desktop dedicated audio media in place; Android restarts with fresh native players and keeps muxed media on the video player's OpenSL ES path to avoid duplicate output, while delayed events from retired players are ignored.
- Dynamic subtitle textures release their texture-manager entries on replacement and disposal.
- Direct media, subtitle, selected HLS child-rendition, and scrub-thumbnail URLs pass `MediaHostGuard`; unused master children stay lazy, and a `/cast/` path is not a trust boundary.
- Playlist initialization clears rows removed from persistence before restoring the current snapshot.

### 2026-10-05 — Android renderer, decoder, and overlay-text hardening
- Pojav/FCL Android defaults to explicit libvlc `avcodec` software decoding because the desktop JVM cannot provide `android.media.MediaCodecList`; a non-empty `dreamdisplayx.hwDecode` remains an opt-in escape hatch for launchers with a real MediaCodec bridge.
- MobileGlues is detected from launcher EGL/GL library properties and receives a bounded avcodec thread pool; Android player replacement waits briefly for the old vout to report paused without reintroducing the unsafe stop/release teardown.
- MobileGlues bypasses the direct PBO/BGRA upload path and uses Minecraft's command encoder because the emulated desktop extension path can otherwise yield black textures.
- Android cleanup roots retired session managers process-wide so delayed vmem/JNA callbacks and direct buffers cannot be garbage-collected while libvlc workers still reference them; vmem uses aligned strides, per-format generations, and leased callback slots, while scrub sessions retain their callback owners after pause-only teardown.
- Subtitle and danmaku rasterizers use an isolated optional Cacio/AWT backend with real runtime probing and mixed-script logical-font fallback; runtimes without `java.desktop` skip the optional texture safely.

### 2026-10-04 — Per-viewer tracks, native-size direct links, and playlist guards
- Resolver subtitle renditions are carried through `PreparedMedia`/`MediaPlayer`; the client persists stable audio and subtitle identities per display.
- Subtitle downloads are bounded by `MediaHostGuard`, byte limits, timeouts, and HLS VOD segment-count limits; the render thread only rasterizes the current cue.
- Unknown direct-stream heights use the display texture ceiling instead of the `Int.MAX_VALUE` quality sentinel's 4K mapping, avoiding needless decoder upscaling.
- Playlist EOS and skip paths validate the current URL/language and reject pending queue rows before playback; automatic NEXT carries an item/revision token so duplicate or stale callbacks are idempotent, and client media generations reject retired-player EOS tasks.

### 2026-10-04 — Linux VOD loop and video-switch lifecycle hardening
- Normal desktop VOD loops restart an ended libvlc input with a zero-position `play()` path instead of synchronously stopping both video and dedicated audio inputs on every loop. Non-zero seeks retain the stop/play/set-time fallback.
- A delayed, generation-guarded check revives only the video or audio input that remains `STOPPED`/`ENDED`, covering silent loops without stopping healthy channels.
- The legacy `StateManager` reset/tick path no longer participates in v2/v3 timelines; `TimelineManager` resets before publishing a new `DisplayInfo` so playlist switches cannot be rewound by `serverTime=0` packets.
- Playlist snapshots carry an append-only playback generation so a same-URL item still forces a fresh player swap; queue edits do not increment it.
- Verbose libvlc logging rotates a previous `libvlc.log` to a timestamped gzip archive (keeping the newest ten) before opening a fresh UTF-8 log.
- Player teardown wakes the first-frame wait immediately and exposes an asynchronous stop path for display swaps, so a stream that never delivers its first frame cannot hold later video changes behind the ten-second start timeout.
- `DisplayMediaController` coalesces duplicate pending URL/language loads, discards stale player errors before installing a newer generation, and waits for asynchronous teardown before creating the replacement.

### 2026-10-29 — Upstream ab46074 sync (lineage + hand-ported patches)
- Branch `merge/upstream-ab46074`, PR #7. Upstream was **92 commits** ahead of `42af16c3`.
- **A plain `git merge ab46074` was attempted and rejected**: after normalising every path/content back to `dreamdisplays`, the merge produced **289 conflicting files / 419 conflicting hunks** across all modules, and sampling showed genuine divergence, not rename noise — `util/UpdateCheck.kt` conflicts on the repo URL, `api/DisplayId.kt` is `value class` here vs `data class` upstream. Upstream's overhaul (data-class ids, the new `shared` module, addons, Forge/Quilt platforms) is a deliberate fork divergence; merging would overwrite both sides.
- Procedure used instead: `git merge -s ours ab46074` to record lineage, then port individual commits by hand. **Do not** try the normalise-then-merge route again for this fork.
- Ported: `ab46074` (`DisplayScreen.publishStartedFlip()` publishes state on the first frame), `5c794ade` (`TimelineFollower` keeps the paused frame when a seek lands during pause), `1d9120aa` (render path: `ScreenRenderer` raw translation + allocation-free quad UVs, `TextureUploadUtil` `writeToTexture` overload cache, `GpuFrameUploader` hoisted lambdas, fabric `Client` `bufferSource` reflection cache).
- Skipped as absent in this fork: `bc27d4f2` (no `SIZE_LOD_*`/`lodRungFor`), `b0fca428` (no `platform/client/common/.../utils/Sounds.kt`). Always `grep` for the target symbols before porting a patch.
- Local `--offline` builds cannot verify: the cached paper dev-bundle and the fabric-loom 1.17.13 plugin are missing on this machine. CI is the verification gate for these render-path changes (no pure-JVM seam for a unit test). `gh pr edit` fails with a Projects-classic GraphQL error — patch PRs via `gh api -X PATCH repos/<owner>/<repo>/pulls/<n> --input file.json` instead.
- Follow-up round on `ab46074..ae856ad6` (18 upstream commits) after PR #7 opened: ported `74f488e1` + `81e196f8` + `aceb49e5`, `023357e3` (`DisplayVeto`), `ae856ad6` (`MediaHostGuard` KDoc only — no `Processes.kt` here), and `cbc80455` (Gradle wrapper 9.8.2). Skipped as absent or architecturally unrelated: `e2874cc5`/`5b08eed8`/`6a69d892`/`053a8b54` (ffmpeg, `MediaPlayer.kt` rewrite, size LOD), `6e8476de`/`afd1c192`/`05bbd756` (Events trio needs upstream `ServerDisplay`), `1fb86950` (API stable-isation), and the YouTube/YtDlp/`shared` work. Gradle 9.8.2 builds clean against fabric-loom 1.17.13 on 1.18.2, so the wrapper bump carries no version risk.
- **PR #7 was merged into `main`** (`4f54c20d`) on 2026-10-31 after the branch reached `fe34f1ed` (version bumped to `1.10.6.1-dev`, unreleased `1.10.0.5` CHANGELOG section renamed and its lineage line repointed to `ab46074`). Pre-merge `main` is preserved as the remote branch `backup/main-pre-pr7` (`4edfa622`). Post-merge `main` CI was 10/10 green; the local `main` is reset to `origin/main` and `merge/upstream-ab46074` is kept for follow-up work.

### 2026-11-01 — Upstream c1824ffa sync (1.10.8)
- Branch `merge/upstream-c1824ffa`. Upstream was **21 commits** ahead of `ab46074`, i.e. two release cycles: `1.10.7` and `1.10.8`.
- Same procedure as before: `git merge -s ours c1824ffa` to record lineage (tree unchanged, verified with `git diff --stat`), then port by hand. `c1824ffa` **is** `upstream/main`.
- **Only one commit in the whole range was worth porting**: `f7f0cba8` (`fix: stop selections from protecting blocks before display creation`, upstream issue #272). Everything else is either already here or architecturally absent.
- Ported `f7f0cba8` in full: protection predicates (`ProtectionListener` ×3 call sites, `ExplosionProtectionMixin` ×2 branches, `PistonProtectionMixin`, Forge `ForgeListeners.onPiston`) now check only `DisplayManager.isContains`; `SelectionManager.isLocationSelected` (both overloads) and the private `PaperSelectionData.contains` helper are gone with their last callers; `SelectionManager.resetSelection` is called from both quit paths (**this half is NOT in the upstream patch** — upstream resets the selection elsewhere, here the fork needed it explicitly, otherwise a stale selection survives a reconnect); `/display create` rejects an oversized selection *before* the surface census via a new `internal isOversized`, covered by `CreateCommandOversizeTest`.
- Skipped as **architecturally absent** (verified by file existence, not by assumption): `5b08eed8`/`d131688f` (drop decode paths, add media cache/platforms — no `LavGlSurfaceTextures.kt`), `6a69d892` (`PlaybackSessionManager`/`SubtitleController` do not exist), `e2874cc5` (no `FFmpegBinary.kt`, no ffmpeg in this fork), `053a8b54` (no `SIZE_LOD_*`), `6e8476de`/`afd1c192`/`05bbd756` (Events trio needs upstream `ServerDisplay`/`ServerEvents`; the fork has `DisplayService` + `DisplayMutationPort` instead), `1fb86950` (API stable-isation would rewrite the fork's whole `api` surface).
- Skipped as **already present**: `cbc80455` (wrapper is 9.8.2), `aceb49e5` (`ThreadUtil` docs already carry the upstream wording), `74f488e1` (`_build.yml` already lists forge → neoforge → fabric), `ae856ad6`, `023357e3` (`DisplayVeto` present), `81e196f8` (`RenderBackend` docs already match), version bumps `140d22d4`/`ffb52f6a`/`3e9b02f9`/`c1824ffa` (the fork tracks its own version line).
- Consequence to remember: because the upstream commits are **not** ancestors of this branch, `git log <commit>..HEAD` will keep listing them every time. The skip list above is the record of why each one is not ported; re-check it before re-investigating a "missing" commit.

### 2026-11-01 — libvlc mixer resets on the Android OpenSL ES `aout`

A user report ("the video sometimes gets quieter, replaying restores it, then it drops again") traced to libvlc's own software volume, not to our gain code:

- `integer_mixer` volume belongs to the **audio-output instance**. The Android `opensles_android` aout is rebuilt on every input seek — each Bilibili DASH fragment seek triggers one, and a single `libvlc-1.log` showed `creating audio output` **18 times** in one playback, each preceded by `seeking to sidx moof` / `discarded audio buffer`. Every `libvlc_audio_set_volume` pushed before that rebuild was silently discarded, so the value set on attach evaporated a few seconds in. That is exactly "replay fixes it, then it degrades again": the replay re-attaches and re-pushes the value.
- Two layers fix it in `LibVlcSessionManager`: `reassertVolume()` runs on every PLAYING event (the common rebuild case), and `verifyVolume()` polls `libvlc_audio_get_volume` once per second from `MediaPlayer.tick` (covers a seamless re-seek that rebuilds the aout without emitting PLAYING again). `verifyVolume` treats a **negative** return as "no aout yet" and does nothing — writing there races the aout setup.
- `percentFor()` maps 0.0-2.0 to 0-200 percent. The previous `coerceIn(0.0, 1.0)` threw away every legitimately boosted value; libvlc's own ceiling is 200%, and clamping to 100 made a loud nearby display sound capped.
- `VolumeController` gained `forceApply()`/`currentEffective()` and moved the curve into `attenuationFor()`. The old `0.25 + 0.75 * t^2` bottomed out at **25%** and already sat at ~67% a quarter of the way out, so walking around a screen sounded like the knob was being turned. The new `0.5 + 0.5 * t^2` is full next to the display, floors at 50% at the audio radius, and disables attenuation entirely for an unbounded or non-positive radius (the popout passes `Double.MAX_VALUE`).
- `primeNewPlayerVolume()` used to `return` when `Minecraft.getInstance().player` was null, leaving `VolumeController` at its 100% default until the first `tick()` corrected it: the display started loud and then audibly dropped. It now seeds distance 0 in that case and still pushes the user volume.
- `VolumeControllerTest` (9 cases) covers the curve, monotonicity, the popout bypass, the clamping and the `forceApply` re-assert. The native rebuild count above is the only manual evidence; that path cannot be unit-tested.

### 2026-11-01 — Silent dedicated audio player, frozen CROP picture, oversized decoded frames

Three reports shared one theme: state that only ever existed on the video player.

- **CROP froze the picture while sound and the progress bar kept running.** The old `fitFrame` scaled into a `fitW`x`fitH` intermediate and then copied its centered sub-rectangle. On the CROP branch `fitW` exceeds `dstW`, and the copy loop narrowed `scratch.limit()` once per row, so the second row's `scratch.position(srcOff)` landed past the limit and threw `IllegalArgumentException` on every frame. `publishFrame` wraps the publish in `catch (Throwable)` and only logs at debug, so nothing reached `surface.publish` while libvlc kept decoding. Letterbox/crop now map each destination pixel straight back to a source pixel in one pass (letterbox bars are a zeroed row), so there is no intermediate buffer to mis-slice. `TODO.md` had carried this as "needs a reproduction that states whether the picture freezes or playback stalls" since `096bddae`; the answer is the picture freezes and playback continues.
- **A display could come back silent after a world reload.** A Bilibili DASH rendition is split: the video player gets `:no-audio` and the dedicated audio player owns the only audible output. But `audioPlayer()` attached **no** events on Android, and `verifyVolume()` read only `mediaPlayer` — which answers `libvlc_audio_get_volume() == -1` because no audio output is attached, so the drift check returned early forever. Any audio-output rebuild on the audio player therefore stayed silent until the user touched the volume slider. Now the audio player subscribes to `PLAYING` only (its `END_REACHED` must still never end the session, because a DASH audio slave is shorter than the video), `handleEvent` routes that one event to `reassertVolume`, and `verifyVolume` repairs both mixers through `repairMixer`.
- **60-minute videos turned into a slideshow.** The log showed `libvlc video setup: 2560x1442 ... (expected 1080x1080)` followed within seconds by `Can't keep up! Running 2004ms or 40 ticks behind`: a 1440p rendition was decoded at full size for a 1080x1080 texture and resized per frame on the vmem thread with four single-byte `ByteBuffer` accesses per output pixel (~4.7M per frame). `setup()` now writes the requested size back into libvlc's `width`/`height` out-parameters so libvlc's own SIMD swscale stage performs the downscale, and `resizeRgba` gained bulk-row and integer-downscale paths. `capToTexture` keeps the aspect ratio, rounds to even sizes, leaves anything within 10% of the texture alone, and can be disabled with `-Ddreamdisplayx.capSourceToTexture=false`.
- Tests: `LibVlcFitWindowTest` (geometry invariants for both modes, the 2560x1442 and 1088x1922 cases from the log, degenerate sizes) and `LibVlcSourceCapTest` (the capping rule). The native audio path itself cannot be unit-tested.
- Reminder for future edits to `LibVlcSessionManager.kt`: `edit_file` repeatedly mis-indents inserted blocks (it re-indents `/**` and `private val` to column 0). Re-read the region afterwards; a brace/paren balance check that strips comments and string literals catches the damage quickly.

### 2026-02-DD — Upstream 42af16c3 sync (1.10.0.3): curved displays, keyboard shortcuts, protocol v1 drop
- Synced upstream `42af16c3` (= tag `v1.10.0-preview.3`) on top of `6102d461`, then ported the changes worth having into the fork's own paths.
- **Sync procedure** (the fork's package rename makes a plain merge unusable):
  1. `git fetch upstream main`
  2. `git merge -s ours --no-commit upstream/main` — records the upstream commit as a second parent while the tree stays ours.
  3. Commit the merge, then port the selected upstream changes by hand into the `com.dreamdisplayx` paths, as a normal commit on top.
  Never run a plain `git merge`: upstream's `com.dreamdisplays` tree would silently overwrite ours.
- Ported: curved displays (stairs/slabs) end to end — `api/display/geometry/{ConformingMesh,DisplaySurfaces}`, client `ConformingWorldMesh`/`ConformingScreenDraw`/`DisplayGeometry` clearance, `FullDisplayData.depth`/`conforming`, `DisplayInfo` proto fields 24/25, server surface census + `conforming` stored in bit 16 of the existing `facing` column (no migration), client `DisplayScreen`/`DisplayLifecycleManager`/`DisplayApiMapper`/`ScreenRenderer`/`DisplayRegistry`.
- Ported: keyboard shortcuts in `DisplayMenu` (`Space`, arrows, `M`, `0`–`9`, `F`, `P`; `Shift` = coarse seek/volume steps, ignored while typing), draw-time frame picking + audio master clock smoothing, `FrameSurface` display queue, `LastFrameCache` store throttle, capped libvlc stderr buffers, resolver candidate-job cancellation, the end-of-stream pause fix, emoji stripping in titles, GL texture binding cache, and the 12-language string updates.
- Ported: protocol v1 drop. Only the frozen `version`/`premium` handshake stays (`LegacyProbe`): every client probes with `version`, a v1-only server answers `premium` and the client reports an outdated server in chat; a v1-only client is told to update (`outdatedClient`). The 8-channel `LegacyV1Payload` is gone; all three server platforms register the probe instead.
- Deliberately NOT ported: upstream's `supportsVertical`/`canReceive` recipient gating (this fork's net layer has no vertical gate), and the `FramePrebuffer`/`AudioSink` pacing changes (those files are orphaned here — the live path is `LibVlcSessionManager` + `FrameSurface`).
- Conforming support is now advertised as ClientHello field 29; server packet facades downgrade `conforming` to a flat representation for legacy or unadvertised clients, while the client projects subtitle and danmaku overlays through the same fitted mesh.
- Removed dead resources: the stale `assets/dreamdisplays/lang/{client/zh_cn,server/zh}.json` leftovers from the fork rename — loaders only ever read `assets/dreamdisplayx/` (`ServerConfigModel` for server lang, the mod id for client lang).
- **PlatformWeaver is incremental and its stale cache produces phantom errors.** `:platform:client:fabric` adds `:platform:server`'s chiseled output to its own source set and the fabric-target PlatformWeaver pass strips the `@PaperOnly`/`@NeoForgeOnly` declarations. If server files are edited and only an incremental compile runs, the stripping is not re-applied: the Paper-only classes stay stripped while their referencing files get recompiled, producing a burst of bogus `Unresolved reference 'PaperServer'` / `'PaperV2Networking'` / `playBidirectionalCompat` errors across the server module. Always `gradlew :platform:client:fabric:clean :platform:client:neoforge:clean :platform:server:clean` (or `--rerun-tasks`) before judging such an error burst — a clean 26.3 fabric compile of the same tree is green.
- **Language-file edits must use explicit UTF-8 I/O** (`[System.IO.File]::ReadAllLines(path, UTF8)`): reading a UTF-8 lang JSON through `Get-Content` under a non-UTF-8 Windows code page double-encodes every non-ASCII string and silently corrupts all 12 files.

### 2026-02-DD — Synchronous native stop on video switch; playlist requester names
- `MediaPlayer.stop()` calls `LibVlcSessionManager.stopNow()` on the caller's thread BEFORE queueing the executor teardown: the single vlc-ctrl executor can sit blocked behind a slow media-attach task, which left the old player's vout alive past `awaitStopped()` and stacked a second native player on the display (two "libvlc video setup" lines ~1s apart for different URLs in the 2026-09-06 22:28 log). The previous load()-side bounded-retry fix remains as a second layer.
- Playlist "by" tag resolves the adding player's NAME end-to-end: `PlaylistItemRecord.requesterName` (+ wire `PlaylistItem.requesterName`, proto field 7, + `requester_name` column auto-migrated by `MigrationUtils`), filled by `PlaylistManager` from the command sender, shown by `PlaylistPanel` with a UUID-prefix fallback for legacy rows. Permission checks still compare UUIDs — only the display changed.

### 2026-02-DD — Playlist UX fixes: playlist mode toggle, tab visibility, auto-queue, danmaku gating, player stacking
- `DisplayPlaylist.enabled` persisted in DB (`enabled` column, default true); `PlaylistCommandAction.SET_ENABLED` (owner/admin only) plus wire fields `PlaylistState.enabled` (proto field 6) / `PlaylistCommand.enabled` (proto field 10)
- Server auto-plays the first non-pending item when the queue gains items while disabled; `PlaylistManager` skips auto-advance when `enabled=false`
- Two-tab menu now paints a proper panel background per tab and per-frame toggles child visibility (`playlist.children.visible` / `settings.setControlsVisible`) so no controls bleed across tabs
- Clicking a suggested video enqueues it when playlist mode is on (`PlaylistCommandAction.ADD`), otherwise plays directly
- Danmaku overlay is gated on `isVideoStarted && hasTexture` so comments never appear before the first frame
- `DisplayMediaController.load()` now retries `awaitStopped()` with a bounded loop (15s total) instead of creating a replacement immediately, preventing multiple native players stacking during rapid video switches

### 2026-02-DD — Database-backed per-display playlists
- Playlist packets (V3 `PlaylistState` / `PlaylistCommand`, ids 30/31) and wire enums (`PlaylistEndBehavior`, `PlaylistEnqueuePolicy`, `PlaylistCommandAction`)
- Server `PlaylistManager` with end-behavior auto-advance and enqueue-policy permission model; `PlaybackTransport` gains `loadAllPlaylists`/`savePlaylist`/`deletePlaylist`/`notifyVideoChanged`
- `playlists` + `playlist_items` tables in SQLite/MySQL; cleanup on display delete; playlist snapshot sent on `RequestSync`
- Client `PlaylistStateStore` mirror + two-tab display menu (playlist / settings) with `PlaylistPanel`

### 2025-01-DD — Global Bilibili login + cross-server sync
- Global credential (single account per server/network)
- Broadcast to all v2 clients on login/logout/refresh
- SQLite/MySQL credential sync backend
- OP-only commands with LuckPerms support
- Removed screenshare entirely (packets, CastManager, config)

### 2025-01-DD — Danmaku settings UI + Bilibili account display
- Per-display danmaku controls in DisplayMenu
- DanmakuAreaSlider, DanmakuFilterBar widgets
- BilibiliAccountLabel (avatar, name, VIP badge)
- /dlogin now OP-only, /dlogoff added
- Pause fix (canHoldWarm instead of canPark)

### 2025-01-DD — Initial fork + Android/screenshare removal
- Removed Android native builds, CI jobs, code paths
- Removed client-side screenshare code
- Bilibili account label + dlogin/dlogoff commands

### 2026-02-DD — Upstream 1.9.5 merge + build pipeline changes
- Merged upstream `8ccaf45`; fork version bumped to 1.9.5.1-dev
- Multi-version properties now live in `versions.json` (upstream 3859aeba); `versions/*/gradle.properties` removed
- `versions/active.txt` still selects the active version for stonecutter
- sqlite-jdbc ships as a NeoForge jar-in-jar (upstream aca3c564), un-relocated in Fabric/Paper fat jars
- The relocated-JNI sqlite native build was removed entirely: no `native/sqlite/`, no sqlite job in
  `natives.yml`, no sqlite entries in `natives-manifest.json`, no `Component.SQLITE` in `NativesDownloader`
- `natives.yml` now only collects the official LibVLC runtime (7 platforms)
- Render-distance menu slider removed (upstream 4b053fb9); unload distance follows the client's
  own chunk render distance (`DisplayScreen.clientRenderDistanceBlocks()`)
- Ported upstream water/glass render fix (d72d6e0a) + PBO client-mapped-buffer barrier (9cc61b27)
- Module READMEs (api/core/media*) removed (upstream 6b4eac4f); root README rebuilt on upstream layout

### 2026-02-DD — Bilibili danmaku overlay
- Viewer-local Bilibili VOD danmaku uses six-minute protobuf segments with XML fallback, lane-based timing, transparent text glyph rendering, and per-display/global settings.

### 2026-02-DD — Android support restored
- Platforms: `android-aarch64` / `android-x64` (PojavLauncher / FCL / Zalith; ARM64 + x86_64)
- Detection: `OsInfo.isAndroid` (heuristic, was already present) — every Android branch keys off it
- Natives: `natives.yml` collects the official VLC-Android APK per ABI and ships its four `.so`
  (monolithic `libvlc.so`, plugins statically linked — no plugins dir); manifest gains the two
  android keys; `NativesDownloader`/`LibVlcNativesLoader` resolve `Android`/`aarch64|x86_64` dirs
- noexec: `AndroidPaths.nativesCacheRoot()` redirects the cache from the (noexec) game dir into
  app-internal storage (`java.io.tmpdir` → `user.home` → `/data/user/0/<pkg>/cache`)
- Audio: `javax.sound` does not exist on Android, so `LibVlcSessionManager.audioOutput` is
  nullable and NOT instantiated there (`systemAudio` flag). Muxed media keeps audio on the video
  player; separate renditions create a dedicated OpenSL ES audio player, while volume goes through
  `libvlc_audio_set_volume`. Desktop keeps the Java Sound callback path.
- Video/instance: Android passes `--aout=opensles` + `--codec=avcodec` (explicit software
  decoding by default; a non-empty `dreamdisplayx.hwDecode` can opt into a launcher MediaCodec
  bridge); MobileGlues also receives a bounded `--avcodec-threads` value. No `--avcodec-hw`
  (desktop-only concept). **Never pass `--plugin-path` on Android**: the
  monolithic libvlc-all AAR builds VLC with loadplugins
  disabled, so the option is compiled out entirely and `libvlc_new` returns null on an unknown
  option (observed: "vlc: unknown option or missing mandatory argument `--plugin-path=...'"
  → `LibVLC low-level load failed`). Also note the aout module's real name is `opensles`
  (OpenSL ES), not `opensl`. **Do NOT pass `--http-proxy` either** — this AAR compiles that
  option out too (it logs "Warning: option --http-proxy no longer exists"), and VLC-Android's
  http access module unconditionally calls `vlc_getProxyUrl()` regardless, so the option can
  neither skip nor redirect the JNI system-proxy probe. The probe itself is what must be safe:
  it crashes the JVM from inside (`SIGSEGV in libjvm.so`, thread `config_GetGenericDir`)
  because libvlc's `JNI_OnLoad` bails out with `JNI_ERR` at its very first
  `FindClass("android/os/Environment")` on Pojav-style game JVMs (desktop OpenJDK, no
  `android.*` framework classes) — so the cached `java/lang/System.getProperty` jclass +
  jmethodID stay NULL, and `vlc_getProxyUrl` later calls `CallStaticObjectMethod` with NULL
  class/method. Fix: the mod ships its OWN `android.os.Environment` stub (see
  `media/player/src/main/kotlin/android/os/Environment.kt` — the same trick squi2rel/VideoPlayer
  uses, independently implemented): `FindClass` then succeeds, `JNI_OnLoad` completes and caches
  `System.getProperty`, `vlc_getProxyUrl` reads `http.proxyHost` → null (unset on a game JVM) →
  "no proxy" → direct connection. `ensureAndroidEnvironmentStubVisible()` pre-warms the stub
  before injecting `JNI_OnLoad`.
- Native loading: Android JVMs report `os.name=Linux-Android`, so JNA's generic-Linux name
  mapping turns `Native.load("libvlc", ...)` into a doubled `liblibvlc.so` lookup. `LibVlc`
  therefore loads the exact extracted `libvlc.so` path on Android via
  `LibVlcNativesLoader.jnaLoadTarget()` (desktop keeps the plain `"libvlc"` name). The Android
  linker ALSO does not search a library's own directory for its `DT_NEEDED` deps, so the ONLY
  runtime dependency that must be preloaded first with `RTLD_GLOBAL` (`System.load`) in
  `LibVlcNativesLoader.preloadAndroidCompanions()` is the renamed libc++
  (`libc++_dreamdisplayx.so`); without it dlopen dies on
  `cannot locate symbol _ZTTNSt6__ndk118basic_stringstream...`.
- Android native cache hygiene: `NativesDownloader.flattenPlatformDir()` wipes the destination
  before overlaying the extracted archive, and on every Android startup
  `cleanAndroidStaleNatives()` deletes any `.so` outside the AAR-era whitelist
  (`libvlc.so` / `libc++_dreamdisplayx.so`). Without this an old APK-era `libc++_shared.so` /
  `libmla.so` could survive beside the renamed libc++, and two libc++ copies loaded
  `RTLD_GLOBAL` corrupt each other's C++ vtables / thread-start pointers — observed crash
  signature when a video starts: `SIGSEGV in __pthread_start` (thread jumps to an unmapped
  address).
- **NEVER preload `libvlcjni.so`** (also excluded from the stale-clean whitelist): it is the
  org.videolan Java-layer JNI glue, NOT a dependency of `libvlc.so` (the AAR's DT_NEEDED lists
  only libc++/libm/libEGL/libGLESv2/liblog/libc/libdl/libandroid/libmediandk; the ten
  "libvlcjni" strings inside libvlc.so are build-path macros, not runtime dlopens). If it is
  ever `System.load`'ed its `JNI_OnLoad` calls `FindClass("android/os/Build$VERSION")` — a
  class that does not exist on Pojav-style game JVMs (desktop OpenJDK, no `android.*` framework
  classes) — so it throws `NoClassDefFoundError`, the JVM dlcloses the library, but
  `JNI_OnLoad` has already spawned native worker threads whose start routines point into
  libvlcjni's own text; once unmapped those threads jump into a hole and crash with `SIGSEGV in
  __pthread_start`. This was the TRUE root cause of the repeated Android crashes (both the
  dirty-cache run and the post-hygiene run showed the same `NoClassDefFoundError:
  android/os/Build$VERSION` → `Unloaded shared library` → `SIGSEGV` sequence); the double-libc++
  mixing was only the extra corruption visible in the first log. squi2rel/VideoPlayer never
  loads libvlcjni.so either.
- Android JNI bridge: plain JNA `Native.load` only `dlopen`s `libvlc.so` and does not reliably run
  its `JNI_OnLoad`, so VLC-Android's AndroidBridge never learns the JavaVM and `libvlc_new`
  returns null. `LibVlc.ensureLoaded()` first calls
  `LibVlcNativesLoader.loadAndroidLibraryBeforeJna()` so `System.load` runs `JNI_OnLoad` before
  JNA creates its callback TLS key; `injectAndroidJniOnLoad()` remains the direct-load fallback.
  The fallback obtains the live JavaVM via `JNI_GetCreatedJavaVMs` from `libjvm.so` (`java.home`)
  and invokes `libvlc.so`'s exported `JNI_OnLoad(JavaVM*, NULL)` through JNA
  `NativeLibrary` — the same bridge step squi2rel/VideoPlayer performs with its
  `libvlc_jvm_bridge.so` shim, done in pure JNA. libvlc.so's JNI_OnLoad stores the JavaVM in a
  global on entry; it does NOT gracefully skip when a class is missing — its very first
  `FindClass("android/os/Environment")` throws on a Pojav-style game JVM (no `android.*`
  framework classes), it bails with `JNI_ERR`, and the AndroidBridge caches it would have
  filled (including `java/lang/System.getProperty` jclass+jmethodID) stay NULL — that NULL
  cache is what later crashes `vlc_getProxyUrl` (`CallStaticObjectMethod` with NULL class/
  method → `SIGSEGV in libjvm.so`, thread `config_GetGenericDir`). So `injectAndroidJniOnLoad`
  first calls `ensureAndroidEnvironmentStubVisible()`, which pre-warms the mod's OWN
  `android.os.Environment` stub (see `media/player/src/main/kotlin/android/os/Environment.kt`,
  an independent implementation of the trick squi2rel/VideoPlayer ships: their mod source tree
  includes a stub `Environment` class so their bridge's `JNI_OnLoad` completes) — with
  `FindClass("android/os/Environment")` satisfied, `JNI_OnLoad` runs to completion (caching
  `System.getProperty`), `vlc_getProxyUrl` reads `http.proxyHost` → null → no proxy → direct.
  The stub is inert on desktop (never referenced outside the Android bridge). Best-effort:
  bridge failures are logged and swallowed so playback is still attempted.
- Android teardown ordering (thread-exit crash): VLC-Android registers a `pthread_key` whose TLS
  destructor is `jni_detach_thread` (`modules/video_output/android/utils.c`, `pthread_key_create`
  around libvlc.so+0xef73e0). Binary + source audit (vlc 3.0.x utils.c): the key's value is a
  plain `JNIEnv*` stored by `android_getEnvCommon` (the only `pthread_setspecific` caller for
  `jni_env_key`, at libvlc.so+0xef63e8; the other three `pthread_setspecific` sites use unrelated
  keys). When a VLC native worker thread exits, `pthread_key_clean_all` runs the destructor which
  does `(*env)->GetJavaVM(env, &jvm)` — dereferencing `env->functions` (offset 0). On this build
  the value sometimes points at freed-and-zeroed scudo heap memory (`[R0]=0`, crash at
  `ldr x8,[x8,#0x6d8]` = `libvlc.so+0xef7418`, `si_addr=0x6d8`), so the JNIEnv becomes invalid
  while the thread is still alive. Active Android teardown still avoids `libvlc_media_player_stop` and
  `libvlc_media_player_release` because they force-tear input/vout/aout while worker threads wind
  down, but the supplied natural-EOF crash evidence does not prove that stop caused that crash.
  The EOF path can exit workers even when players are never released; the observed
  `SIGSEGV at libvlc.so+0xef7418` therefore requires JNI TLS ordering protection as well.
  Therefore on Android EVERY teardown path MUST avoid
  `libvlc_media_player_stop`, `libvlc_media_player_release`, and reusing a player after media has
  been attached: `LibVlcSessionManager.stop()`/`cleanup()` pause the players instead
  (`libvlc_media_player_set_pause(p,1)`), while `start()` and the ENDED restart in `beginSeek`
  retire the old players and bind fresh ones. `LibVlcFrameExtractor`'s scrub-session `close()`
  pauses too. Pausing protects active teardown only; natural EOF can still exit workers. The
  System.load-before-JNA ordering plus explicit `CallbackThreadInitializer(true, false)` coverage
  is the JNI TLS mitigation, while paused players are still retained until process exit (players
  accumulate per session — acceptable). Desktop has no Android TLS destructor and keeps the
  serialised stop→release ordering. Also: `libvlc_media_player_get_video_decoder_info`
  / `libvlc_media_decoder_info_release` are NOT exported by the libvlc-all AAR's monolithic
  `libvlc.so` (desktop-only 3.0.7+ API) — `LibVlc.videoDecoderName()` must not call them on
  Android (UnsatisfiedLinkError per F3 refresh); it reports the configured MediaCodec chain.
  Finally, do not stack multiple native players on one display: `DisplayMediaController.load()`
  waits for the old player's `MediaPlayer.awaitStopped()` before constructing a replacement.
- Android natives source: the official `org.videolan.android:libvlc-all` Maven AAR (3.7.7)
  instead of the VLC-Android APK — the library-form runtime squi2rel/VideoPlayer also uses
  (`libvlc.so` + `libvlcjni.so` + full `libc++_shared.so`; no `libmla.so`). `native/libvlc/build.sh`
  renames libc++ to a unique SONAME (`libc++_dreamdisplayx.so`) and rewrites `libvlc.so`'s
  `DT_NEEDED` via `patchelf` so the Android linker can never deduplicate it against the
  same-named `libc++_shared.so` that Pojav-style launchers (Zalith/FCL) already loaded from
  their own runtime dir — that stale copy silently bound before and lacked the stream vtable
  symbol libvlc needs. `NativesDownloader.hasLibVlc()` additionally requires the renamed
  libc++ on Android, so old APK-format caches are invalidated and re-downloaded.
- AWT guards: `VideoPopoutWindow.isAvailable` returns false on Android and `ModTitleLabel`
  catches `LinkageError`; subtitle/danmaku overlays probe optional Cacio/AWT and load a system CJK
  font when available. Thumbnails/ScrubPreview decode paths remain `runCatching`-guarded and
  degrade gracefully when a launcher lacks `java.desktop`.
- Initializer no longer blocks Android startup; AWT headless override skipped on Android
- Complementary shader patching runs at client startup by scanning every ZIP in `shaderpacks`: only Complementary Reimagined/Unbound r5.8.1 is copied to a checksummed `DreamDisplaysX-*` archive before patching. It must not edit `options.txt` or force a selection; BSL, Bliss, Photon, unknown packs, and original archives must remain untouched. Patching must fail closed and preserve the original shaderpack.

### Android SQLite (`SqliteAndroidCompat`)
- The stock `org.xerial:sqlite-jdbc` jar bundles natives for desktop OSes only — its Android
  (`Linux-Android`) builds ship in the `-sources` artifact but not the runtime jar, so any
  `jdbc:sqlite:` connect on Android dies in `SQLiteJDBCLoader` with
  `NativeLibraryNotFoundException` (`StorageManager` Hikari pool init → server crash on world open)
- Fix: `util` bundles the official Bionic `libsqlitejdbc.so` (16 KB page aligned, from xerial's
  `-sources` jar) under `dreamdisplayx/natives/sqlitejdbc/{android-aarch64,android-x64}/`; at
  runtime `SqliteAndroidCompat.ensure()` (only active when `OsInfo.isAndroid`) copies the
  arch-appropriate `.so` into `AndroidPaths.nativesCacheRoot()/sqlitejdbc/` (exec-friendly) and
  sets `org.sqlite.lib.path` + `org.sqlite.lib.name`, which `SQLiteJDBCLoader` honors BEFORE its
  jar-resource fallback — classloader-independent, so it works through the NeoForge jar-in-jar
  module layer. Desktop platforms short-circuit (no-op); jar resources are inert elsewhere.
- `StorageManager` calls `SqliteAndroidCompat.ensure()` from its companion `init` (runs during
  class init, before the instance `dataSource` Hikari pool — also covers `SqlCredentialSyncBackend`
  and Paper, both of which construct storage after `StorageManager`)

### 2026-11-01 — Android AudioTrack budget ("sound works, then goes silent for good")
- Report: on 1.20.1 (running under an Android launcher) the display has sound at first, then loses it
  permanently. The attached `libvlc.log` is decisive: `creating audio output` **3354** times, only **25**
  reaching `using audio output module "opensles_android"`, then a single
  `FindClass(android/media/AudioTrack) failed` followed by **3329** consecutive `AudioTrack jni init
  failed` with no recovery, and **zero** `destroying audio output` lines.
- Cause: `retireAndroidPlayers()` only *pauses* retired players (it must — `stop`/`release` hit VLC-Android's
  `pthread_key_clean_all` TLS UAF, see the Android teardown notes above), so every retirement leaks one
  OpenSL ES aout **and its AudioTrack**, permanently. `start()` retires a player pair on every load, Bilibili
  DASH fragment seeks make loads frequent, and the process eventually hits Android's hard cap of **32
  AudioTrack** instances. From then on `FindClass(android/media/AudioTrack)` fails inside a game JVM that
  has no `android.*` framework classes and never recovers — hence permanent silence rather than a flicker.
- Fix, in this order of importance: (1) `AudioTrackBudget` caps retirements (`32 cap - 4 reserved slots) / 2
  slots per retirement = 14`); (2) once the budget is spent, `start()` reuses the live player pair through
  the **same** attach path instead of a parallel branch, so `setVolume` re-assertion, `isPlaying` and the
  `started` latch all still run; (3) `retireAndroidPlayers()` pauses (does not retire) the live pair before
  that reuse, because `set_media()` on a player whose old input is still draining is the crash the class
  otherwise avoids; (4) retirements are logged as `n/budget (m AudioTrack slots left)`.
- `AudioTrackBudget` is a pure object precisely so `AudioTrackBudgetTest` can pin the arithmetic; the native
  aout itself stays untestable. Keep the budget strictly below the cap: the game's own sound engine also
  allocates AudioTracks in the same process.
- Do not "fix" this by calling `libvlc_media_player_release` on retirement. That is the documented crash.
  The only safe levers are *retire less often* and *reuse in place*.

### 2026-11-04 — libvlc versions: Android 3.7.7, desktop stays on Flathub for Linux
- Android bumped `org.videolan.android:libvlc-all` **3.7.5 → 3.7.7**. Verified before the bump by
  unpacking both AARs (`jni/arm64-v8a/libvlc.so`, `jni/x86_64/libvlc.so`): all 45 `libvlc_*`
  functions the mod calls are still exported, the exported `libvlc_*` count is unchanged (338), the
  9 `DT_NEEDED` entries are identical, `JNI_OnLoad` is present in both (960 bytes), and
  `libc++_shared.so` is byte-identical (`md5 7cedaf42…`) — so `build.sh`'s `patchelf`
  `--replace-needed libc++_shared.so libc++_dreamdisplayx.so` still matches. `libvlcjni.so` is also
  byte-identical (97,736 bytes). Do not "upgrade" the Android artifact further without redoing this
  check: `libvlc_media_player_get_video_decoder_info` and `libvlc_media_decoder_info_release` are
  still not exported by the AAR (they never were — see the Android section above).
- **Do NOT replace the Linux branch of `native/libvlc/build.sh` with Debian `.deb` packages.**
  `VLC_VER` never controlled Linux anyway — that branch installs Flathub
  `org.videolan.VLC//stable` and copies the runtime out of it, while the version knob only drives
  the Windows zip and the macOS dmg. That is deliberate: the Flathub build ships its own glibc in
  `org.freedesktop.Platform`, so one artifact runs everywhere.
- Measured, in case this is ever revisited: Debian trixie `3.0.24` needs **GLIBC_2.38**
  (`libvlccore.so.9.0.1` plus 74 of its 348 plugins, including `libadaptive_plugin` — the HLS/DASH
  path Bilibili depends on), so it would break any user on Ubuntu 22.04 or Debian 12. Debian
  bookworm only has `3.0.23` and does fit GLIBC_2.34, but that is a *downgrade* from what Flathub
  already delivers. Ubuntu's own apt is worse still: noble `3.0.20`, plucky/questing `3.0.21`.
  Flathub stable is on `3.0.24` (`org.videolan.VLC.yaml`, source `vlc-3.0.24.tar.xz`).
- The `Cache libvlc build` step in `natives.yml` is a **no-op today**: nothing in `build.sh` or
  `collect.sh` ever writes to `native/libvlc/.build-cache`. Its key now names both version knobs
  (`vlc3.0.22-android3.7.7`) so a future bump invalidates it correctly if the cache is ever wired
  up. Note that `get.videolan.org` returned **403** for `win64/vlc-3.0.24-win64.zip` and
  `macosx/vlc-3.0.24-arm64.dmg` while `win32`/`winarm64`/`intel64` answered 200, so a desktop
  `VLC_VER` bump to `3.0.24` is not proven safe to ship either.

### 2026-11-04 — The native runtime cache is stamped with its release tag
- `NativesDownloader` used to answer "is the runtime cached?" with `hasLibVlc()` alone, which only
  probes for `libvlc.so`/`libvlc.dll`/`libvlc.dylib` (plus, on Android, the renamed
  `libc++_dreamdisplayx.so`). A file that existed was reused **forever**, so shipping a newer natives
  release fixed clean installs while every existing install silently kept the old libvlc — the classic
  "it works on a fresh instance but not on mine" report.
- The gate is now `!hasLibVlc(dir) || !isCacheCurrent(dir, manifest)`, and a successful
  `downloadAndExtractTarGz` is followed by `writeStamp()`. The stamp lives at
  `<natives>/libvlc/natives-stamp.txt` and holds the release tag taken from the manifest's
  `release_base` URL (its last path segment, e.g. `natives-7f303c9-1787719531`).
- `NativesCacheStamp` is a pure, IO-free object so the decision is unit-testable
  (`NativesCacheStampTest`, 11 cases). The downloader owns all reading/writing.
- Deliberate bias: an **unreadable or absent** stamp never matches, so a cache written before stamping
  existed re-downloads exactly once. Paying one download is cheaper than a user reporting a bug that
  was already fixed upstream.
- Deliberate exception: when `tagOf()` cannot derive a tag (blank URL, trailing slash, a bare
  `.../releases`/`download`/`latest` page, a scheme-only string) the old behaviour is kept and the
  on-disk cache is trusted. Re-downloading on every launch behind an unusual manifest URL would be
  worse than a possibly-stale runtime.
- `flattenPlatformDir()` wipes the destination before overlaying, so the stamp must be written
  **after** extraction — writing it before would delete it. `cleanAndroidStaleNatives()` only removes
  `.so` files, so the `.txt` stamp survives that pass.
- A failed `writeStamp()` only logs at warn: a missing stamp costs one extra download next launch and
  never correctness. Do not turn it into an error.
- **Why the tag is a reliable cache key — the whole chain matters.** `natives.yml` generates a tag
  `natives-<sha7>-<unix_ts>`, creates the release and writes it to `.github/natives-tag.txt`.
  `_build.yml`'s `download-natives` reads that file (falling back to `gh release list` sorted by the
  timestamp segment) and exposes it as `natives_release`; the `Inject natives release tag into
  manifest` step then rewrites `media/player/.../natives-manifest.json`'s `release_base` to
  `https://github.com/<repo>/releases/download/<tag>` **before** `Build loaders with Gradle`. So the
  tag ships inside every jar, and a new natives release changes it. That is what makes
  `tagOf()` a real version, not a constant. `release_base` is deliberately never
  `releases/latest`, because a mod release would steal that endpoint.
- Because the tag also carries a Unix timestamp, two different natives builds can never collide even
  if they come from the same commit, so the stamp cannot accidentally consider a rebuilt runtime as
  already cached.

### 2026-11-04 — A DASH fragment boundary is not the end of the stream
- Follow-up to the budget above, found in a 1.10.8.2 field log: one display, `startStreams() cold start`
  printed **once** (it is unconditional INFO at `MediaPlayer.kt:965`), zero `fell back to a full stream
  restart`, zero `dead session`, one display id — yet `Android native player retirements` climbed from `2/14`
  to `10/14` at a steady ~22 s per pair. `retireAndroidPlayers()` has exactly two callers: `start()`
  (reachable only through `startStreams`) and the `ENDED` branch of `beginSeek`. Since `start()` ran once,
  **the `ENDED` branch was firing on DASH fragment boundaries.**
- Symptom the user reports is "sound works for a few minutes, then gone for good, replay fixes it": every
  false end burns two AudioTrack slots (video + audio player) and never releases them, so the cap arrives
  faster. Each retirement also shows up as a main-thread `Can't keep up!` of ~1.5 s, because
  `pauseAndroidPlayerAndAwait` waits 750 ms per player.
- Fix: `AndroidEndedPolicy` (pure object, `media/player/.../managers/`) classifies the position before any
  teardown. `REAL_END` (position within `max(1% of duration, 1500 ms)` of the end) retires as before;
  `FRAGMENT_BOUNDARY` and `UNKNOWN_DURATION` go through `resumeAndroidPlayersInPlace()`, which only calls
  `set_time` + `play` on the existing players. **Never `stop`/`set_media`/`release` there** — that is the
  Android TLS destructor crash, not a teardown option.
- Bias the classifier towards resuming: a wrong "resume" costs one position reset, a wrong "real end" costs
  two permanently leaked AudioTrack slots. Hence `duration <= 0` (live) and `position < 0` (demuxer flush)
  are both `UNKNOWN_DURATION`, i.e. resume.
- `libvlc.log`'s `end of stream` (26 lines in the same log) is an **HTTP/2 stream close**, not a playback end:
  its context carries `out RST_STREAM (0x03)` and `content-range: "bytes .../199382"`, one per DASH fragment.
  Never read it as EOS. The only usable signal is `libvlc_media_player_get_state() == ENDED` together with a
  position that is actually near the duration.
- Diagnosing a "silent after N minutes" report: grep `Android native player retirements` — if the counter
  grows while `startStreams() cold start` appears only once, it is this bug, not the budget itself.
- `AndroidEndedDispositionTest` (18 cases) pins the slack window, both degenerate reads, the
  `shouldResumeInPlace` contract and the budget interaction (a 4-minute video at a 22 s fragment cadence must
  retire **zero** times, while a real end still retires its two slots).

### 2026-11-04 — A ship display that stays behind: `getMethod` does not accept a subtype
- Report: on a Valkyrien Skies ship the hull (and the block frame) moved with the ship but the
  **video stayed at the display's original world position**. The hull moving is VS's own chunk
  transform, so it proves nothing about the mod's render path — our quads are separate and must place
  themselves at `shipToWorld * pos`.
- Cause: `ValkyrienClientCompat.resolve()` resolved the VS level facade as
  `utils.getMethod("getShipObjectWorld", ClientLevel::class.java)`. `Class.getMethod` matches
  **declared** parameter types exactly and does not accept a subtype, and VS declares those helpers
  against the plain `Level` interface. The lookup threw `NoSuchMethodException`, the `catch
  (t: Throwable)` branch cached `Resolution.Absent`, and every later call short-circuited at
  `if (state === Absent) return null`. `transformFor()` therefore always returned
  `AffineTransform.IDENTITY`, `ScreenRenderer.shipTransformOf` reported `null`, and the fallback
  `stack.translate(pos - camera)` drew the picture in structure space — exactly "the picture did not
  follow".
- The server bridge had documented this same trap for `getAllShips` ("passing ServerLevel would fail
  resolution and silently disable the whole bridge") but still keyed `getShipManagingPos` on
  `ServerLevel`, and the client bridge inherited neither the fix nor the comment. Both now go through
  `ShipAnchorPolicy.levelFacadeCandidates()` (`ClientLevel`/`ServerLevel`/`Level`, distinct, never
  empty, plain `Level` last) and try each spelling in order.
- The failure was invisible in a shipped log because only a **failed** resolution logged, and at
  WARN. `resolve()` now logs INFO on success too ("Valkyrien Skies client bridge resolved"), so
  "mod absent", "mod present but inert" and "resolved and drawing correctly" are finally
  distinguishable in `latest.log`. Do not remove that line.
- `ShipAnchorPolicyTest` pins the candidate list invariants (the `Level` fallback must be present and
  last; no duplicates; never empty). The reflection calls themselves still need a real VS jar and stay
  manually verified.

### 2026-11-01 — Client commands must exist on every loader; Bilibili failures must name a cause
- `/dlogin` and `/dlogoff` are registered per loader. Fabric uses `ClientCommandRegistrationCallback`,
  NeoForge and Forge use `RegisterClientCommandsEvent`. Forge's `Client.kt` had registered **no**
  client command at all, so both commands answered "unknown or incomplete command" on
  1.19.4–1.20.1 Forge while the other two loaders worked. `RegisterClientCommandsEvent` extends
  `net.minecraftforge.eventbus.api.Event`, so it belongs on `MinecraftForge.EVENT_BUS`, not the mod bus.
- `ClientSourceHygieneTest`'s login-screen case must list every loader's `Client.kt` and assert
  unconditionally. It previously did `if (!text.contains("PlatformLoginScreen()")) continue`, which
  silently excused Forge exactly because the reference was missing — the check can never skip the
  thing it is supposed to detect.
- `BilibiliApi` reports failures through `failResolve(reason, url)`, never a bare `return null`, and
  every API response passes `biliRejectionReason(root)` first. Bilibili answers HTTP **200** with
  `{"code":-404}` (missing/region-locked), `{"code":-412}` (WAF block) or `{"code":-10403}`
  (entitlement), so reading only `data` collapses all of them plus real network timeouts into the same
  opaque "Bilibili video/room could not be reached." line. `getJson` logs at `warn`, not `debug`, or a
  shipped `latest.log` contains no cause at all.
- Bilibili needs its anonymous device fingerprint (`buvid3`/`buvid4` from
  `x/frontend/finger/spi`) or `x/web-interface/view` answers `HTTP 412 {"code":-412,"message":"request
  was banned"}` while WBI-signed search still works — the exact search-works/open-fails split a tester
  reported. `BilibiliApi.headers()` must send it, and `fetchView` must fall back to the WBI-signed
  `x/web-interface/wbi/view` when the plain endpoint is throttled. This is invisible without the
  `failResolve`/warn logging above: the old code just said "could not be reached".
- Never retry a WAF block immediately. A tester's `latest.log` had ~30 identical `view` requests in
  one second, each rejected; that keeps a short throttle alive and re-renders a display that cannot
  play. `getJson` parks only the WAF-prone endpoints (`isWafProneUrl`) for `WAF_COOLDOWN` after the
  first 412 — parking everything would break search, which kept working through the same ban. The
  ban arrives both as a 412 status and as `{"code":-412}` inside an HTTP-200 body, so
  `biliRejectionReason` records it too.
- That cooldown must not renew itself: `noteWafBlock` returns early while one is already running.
  Without the guard every 412 pushed `wafBlockedAt` forward, so a display that retried once a second
  parked Bilibili permanently instead of for a minute.
- `Duration` values carry no `.duration` selector. `DEVICE_COOKIE_RETRY_MS` is already a
  `Duration` from `5.minutes`, so `now - last < DEVICE_COOKIE_RETRY_MS` compares directly; writing
  `DEVICE_COOKIE_RETRY_MS.duration` fails with `Unresolved reference 'duration'` on every version.
- A Kotlin string literal that has to contain `"code":-412` must escape the quote: `"code\":-412"`.
  Writing `""code":-412"` (the escape lost when the file was generated by a script) reads as string
  concatenation and dies with `Unresolved reference 'code'` plus `Literals must be surrounded by
  whitespace` — a message that points at column numbers, not at the missing backslash.
- Scripted edits to `.kt` files must be verified with `read_file`, not with `grep`/`sed` output: a
  stale terminal transcript showed a KDoc line as broken when the file on disk was already correct,
  and `edit_file` cannot fix the whitespace it normalises away. Re-read the region instead of
  trusting the previous command's echo.
- Diagnosing a user report from logs: check the reported build hash first
  (`Dream DisplaysX v… (<hash>) starting...`) and `md5sum` the attachments. A `libvlc.log`
  byte-identical to an earlier round is a re-upload of the old scene, not evidence about a new build.

### 2026-11-01 — Mute and volume must be two separate libvlc knobs
- Issue #4's tester reported that after the level drift was fixed, the mute button and a 0 % slider "do
  nothing", and that the sound level wanders as the phone switches earpiece/speaker. The attached
  `libvlc.log` pins the cause: 19 `creating audio output` / 19 `using opensles_android` / **0**
  `destroying audio output`, and `integer_mixer` instantiated only on the 6 `reusing audio output`
  paths.
- `libvlc_audio_set_volume` is a property of the **audio-output instance**, so every write onto a
  freshly built aout lands in a mixer that does not exist yet and the aout default wins. Silence
  therefore cannot be expressed as `volume = 0` — it evaporates on the next rebuild.
- `libvlc_audio_set_mute` is stored on the media **player** and re-applied by libvlc to whatever aout
  is current, so it survives a rebuild. `LibVlcMutePolicy` (pure JVM, unit-tested by
  `LibVlcMutePolicyTest`) maps a level to the pair (`mixerPercent`, `muteFlag`). Silence maps to
  `mixerPercent == 200` **on purpose**: writing 0 as well would leave `get_volume() == 0` behind, and
  `verifyVolume`'s once-per-second poll would read that as drift, restore it and keep the display
  silent after an unmute.
- `LibVlcSessionManager` writes both knobs together through `applyAudioLevel(player)`, unconditionally
  re-asserts both in `reassertVolume` (every PLAYING event), and verifies both independently in
  `repairMixer(player, expected, expectedMute)`. `verifyVolume` feeds both the video player and the
  dedicated Android audio player, because a Bilibili DASH rendition splits them and the video player's
  `get_volume` answers -1 with no audio output attached.
- `libvlc_audio_get_mute` is not exported by every libvlc 3.0 build. The read is wrapped in
  `runCatching`, and a -1 result skips the check rather than writing every second; do not "fix" it into
  an unconditional write. If a shim is ever needed, probe once and cache the answer instead of probing
  per tick.

### 2026-11-01 — `DreamLog.Channel` swallows `warning`/`severe`/`fine`
- `DreamLog.Channel` implements only the four slf4j levels (`debug`/`info`/`warn`/`error`) with the
  overloads the mod actually calls, so any `logger.warning(...)` / `logger.severe(...)` call routed
  through a `Channel`-typed variable fails to compile. A `java.util.logging`-shaped call site must keep
  an slf4j `Logger`-typed or `java.util.logging.Logger`-typed variable.
- `PaperServer.Companion.logger` is a `Channel`. It looks like it would break `getInstance().logger.warning(...)`
  in `DisplayManager`, but it does not: `JavaPlugin.getLogger()` is an **instance** member and shadows
  the companion property, so those calls stay on `java.util.logging`. Verify this by the receiver
  expression, not by the property's declared type.
- `platform/proxy` keeps its own `java.util.logging` logger and calls `warning`/`fine` on it; it does not
  depend on `:util`, so it must not be migrated.

### 2026-11-01 — Scripted edits: three guard checks before committing
A batch `sed` that rewrote `LoggerFactory.getLogger(...)` into `DreamLog.of(...)` left 6 stray `)`
(paren) and mis-indented lines across `platform/*`. All of them were inside `Channel`-unaware files
whose compile path is not reachable offline, so they would only have surfaced in CI. Run these three
checks over `git status --porcelain`'s `.kt` files before committing a sweep:
1. **Balanced delimiters** — a state-machine strip of comments/strings, then `(`/`)` and `{`/`}` depth.
2. **`Channel` API** — every variable assigned from `DreamLog.of(...)` may only call
   `debug`/`info`/`warn`/`error`/`isDebugEnabled`/`isInfoEnabled`/`delegate`/`channel`. Match the bare
   identifier (`(?<![\w.])logger\.`), otherwise `plugin.logger.warning(...)` is a false positive, and
   skip comment lines.
3. **Chisel import placement** — no `com.dreamdisplayx.*` import may sit inside an unclosed `//?` block.
   Note that `//?} else` (no brace) does not change nesting while `//?} else {` does; treating them the
   same makes the scanner report every version-specific vanilla import.

### 2026-11-01 — Pose-aware scissor clipping and reachable permission nodes
- Every `GuiGraphics` scissor call in the client UI MUST go through
  `enableScissorPoseAware` (gated `//? if >=1.21.11 { g.enableScissor(...) //?} else
  /*g.enableScissorPoseAware(...)*/`). `UiScreenBase.renderScaled` renders the whole screen through a
  `PoseStack` scale whenever the window is smaller than `MIN_CONTENT_W` x `MIN_CONTENT_H` (640x410) —
  which every mobile GUI scale is — and `GuiGraphics.enableScissor` only multiplies by
  `Window.getGuiScale()`, ignoring that matrix. The clip rect then lands outside the real window and
  hides content while the (virtual-coordinate) hit boxes keep working: the classic "invisible but
  clickable, with a click sound" report. `UiScissorAndCommandGateTest` pins this.
- Never gate a user-facing subcommand with `.requires { ... }` when the desired UX is a permission
  message. Brigadier prunes a node whose requirement fails, so the player gets "unknown or incomplete
  command" — indistinguishable from the command not existing. Check inside `executes` and reply with
  `displayCommandMissingPermission` instead (`/display login`, `/display logout`, `/display stats`,
  `/display reload`). Same rule applies to the Paper `CommandRegistrar`.
- `Minecraft.execute` is NOT a deferral: on the render thread it runs the task immediately.
  Opening a screen from a command handler needs a real queue drained by the client tick
  (`Initializer.openScreenNextTick`).

### 2026-11-01 — Bilibili quality ladder, portrait frames, panorama refusal
- `qn` on `x/player/playurl` is a **request for one preferred rendition**, not a menu. The old fixed
  `qn=80` made Bilibili answer with only the renditions near 1080p, so most videos resolved to a
  single height, `MediaPlayer.getAvailableQualities()` returned a one-element list and the menu's
  quality slider had a single stop (which reads to a user as "only some videos can change quality").
  `BilibiliApi.resolveVod` now sends `QN_MAX` (127, Bilibili's 8K rung) and the PGC path sends `qn`
  too; entitlement stays server-side, and the client's own `cap` (2160 premium / 1080 otherwise) is
  the second gate. `fnval` is `FNVAL_DASH_ALL` (4048 = DASH+HDR+4K+Dolby audio/Dolby vision+8K+AV1)
  for VOD and `FNVAL_DASH_ALL_PGC` (12240) for PGC — withholding a bit silently drops those
  renditions from the response.
- **Never rewrite only one axis of a stream's size.** `QN_TO_HEIGHT` maps a qn to a canonical rung so
  a movie's encoded 808 becomes a labelable 1080, but Bilibili reports `height` as the long edge for
  portrait uploads: a 1080x1920 phone clip has `id=80`. Mapping the height alone produced
  1080x1080, `MediaStream.contentAspect()` returned 1.0 and `DisplayTextureResource` allocated a
  square texture, i.e. the picture was squashed. `buildStreams` now goes through `canonicalHeight()`
  + `canonicalWidth()`; the width is rescaled by the *encoded* aspect.
- Any "is this source too big for the texture" rule must compare the **long edge**, never the width.
  `LibVlcSessionManager.capToTexture` used `srcW <= limitW * 1.1`, which is always true for a
  portrait source whose width is already under the limit, so every vertical video skipped the cap and
  libvlc kept delivering 1080x1920 frames for a 1080-tall display. `LibVlcSourceCapTest` pins both
  the capped and the deliberately-not-capped portrait cases.
- Panorama detection is a **shape pre-filter plus a tag confirmation**, and the tag fetch only runs
  once the shape already matched, so an ordinary video pays no extra request. `dimension` comes from
  `x/web-interface/view` (the pre-rewrite size), tags from `x/tag/archive/tags`; a failed tag fetch
  leaves the video playable. Refusing a playable video is worse than missing a panorama, so do not
  promote the shape test on its own. `BilibiliPanoramaTest` pins "VRChat"/"360p" as non-markers.

## Workflow Rules

### Post-Change Checklist (MANDATORY)
Every code change MUST be followed by ALL of the below before the task is considered complete:
1. **Commit** — stage all changes and create a descriptive commit message
2. **Push** — push to the remote branch immediately after commit
3. **Documentation** — update `README.md` and `CHANGELOG.md` when the change is user-visible or affects behavior/features
4. **AGENTS.md** — if the change affects architecture, build process, or workflow rules, update `AGENTS.md` accordingly
5. **GitHub Actions verification** — after every push, use GitHub CLI (`gh run list` / `gh run watch`) to inspect the resulting workflow run and report failures before considering the change complete.
6. **Tests** — every added feature or behavior fix MUST ship with an automated test in the owning module when its logic is pure JVM (no Minecraft, libvlc, or GPU dependency). When a change genuinely cannot be unit-tested (native/rendering code), say so in the commit message.

### Testing
- Unit-test pure logic: policies (`RetryPolicy`), helpers (`MediaUtil`), parsers, state machines. Do not assert on native/libvlc behavior — those paths are covered by manual smoke tests only.
- When touching a module that has no tests yet, add at least one focused test for the new/changed behavior instead of leaving it uncovered.
- **CI runs unit tests** since the `unit-test` job landed in `_build.yml`. It currently covers `:api` and `:core` only — the two modules whose tests are pure JVM and were verified green (199 tests). The remaining modules (`:util`, `:media:*`, `:platform:*`, ~83 test files) still have **never-executed** test sources; enable them one module at a time only after `./gradlew :<module>:test` is green locally, otherwise the first red CI belongs to pre-existing breakage rather than to the change under review.
- That job deliberately has no `needs:` and no version matrix: `:api`/`:core` carry no chisel output, so it runs on a bare JDK 25 in ~1 minute, independent of the 10-version build. Do not fold it into `build`; that would pay the 10x matrix cost for a JVM-only check.
- `SchemaDriftTest` reads `src/main/proto/dreamdisplayx.proto` **relative to the working directory**, which Gradle sets to the module dir. Running it by hand from the repo root fails with a misleading "schema is out of date"; `cd core && ...` passes. Do not "fix" the schema in response to that failure.

### Task Tracking (MANDATORY)
Every task MUST be tracked in the session's `todo` task list at all times:
1. **Before starting** any work, create or update the `todo` list with all planned steps, each marked `pending`
2. **While working**, mark the current step(s) as `in_progress` — only one at a time for sequential work, several for genuinely parallel work
3. **On completion** of a step, mark it `completed` immediately (do not batch completions)
4. **At the end** of the task, every step must be `completed`; remove any abandoned steps from the list
5. **`todo.md`** — when the task produces a persistent artifact (config, code, docs), also update or create `todo.md` in the project root to track the broader project state. The session `todo` task list tracks the immediate session; `todo.md` tracks the project roadmap.
6. **`TODO.md` cleanup** — completed items in `TODO.md` must be **deleted** from the list, not left under a "已完成" section. The file should only contain items that are still pending or in progress; once an item is done, remove it entirely.

### Commit Message Format
- Use clear, descriptive messages following conventional commits when possible
- Always include the affected module/component in the message
- Example: `fix(danmaku): resolve segment API JSON parsing compilation errors`

### Changelog Format
- Follow the existing format in `CHANGELOG.md`
- **Keep entries SHORT** — one terse line per change, matching the upstream style; never write multi-sentence paragraphs or long-winded `Highlights` bullets. If in doubt, cut it down.
- **User-facing only** — never list development-cycle churn (dependency version bumps like libvlc `3.0.21 -> 3.0.22`, build/CI workflow splits, release-tooling fixes, internal refactors, JVM debug flags). These happened during the dev version and are not part of the release, so they do not belong in the changelog.
- Add entries under the current/next version heading
- Never merge upstream changelog into our version's section
- **Every fork version section MUST contain** a `Based on Dream Displays [<commit>](<url>)` line immediately after the version heading, linking to the upstream commit this fork version is based on. This line must never be removed or altered to point to a different commit.
- Only sync from the upstream `main` branch; never merge other upstream branches (e.g. `feat/new-readme`).

## CI/CD & Versioning Rules

- `_build.yml` — Main build workflow (multi-version)
- `release.yml` — Release pipeline (dev/preview/release)

### Which workflow triggers on what
- `ci.yml` runs on push/PR to `main`, but its `paths-ignore` covers `*.md`, `docs/**`, `SESSION_TASKS.md`, `.gitignore`, `.github/**` and `native/libvlc/**`. Two consequences that bite in practice:
  - A commit that only edits a workflow file, `AGENTS.md` or `CHANGELOG.md` **does not trigger CI at all**. Verify workflow changes with `gh workflow run ci.yml --ref <branch>` (or push a code change alongside) instead of waiting for a run that never appears.
  - Editing `gradle.properties` (a version bump) *does* trigger the full matrix.
- `_build.yml` holds all four jobs: `metadata`, `download-natives`, `unit-test`, `build` (10-version matrix). `unit-test` and `build` are siblings on purpose — see the Testing section.

### Version Format
- **Version in `gradle.properties`** always has `-dev` suffix (e.g. `1.9.3.1-dev`), matching upstream convention
- CI workflow auto-detects version type from the version string:
  - `1.9.3.1-dev` → **Developer** (pre-release)
  - `1.9.3.1-preview.1` → **Preview 1** (pre-release)
  - `1.9.3.1` → **Release** (full release)
- `pretty_version` output: `1.9.3.1 Developer` / `1.9.3.1 Preview 1` / `1.9.3.1 Release`
- GitHub Release tag: `v1.9.3.1` (no `-dev` suffix in tag)

### CHANGELOG Rules
- **Title** must include "Release" suffix: `# 1.9.3.1 Release` (even for dev/preview entries)
- Never split a version into multiple sections — all entries for one version go under one `#`
- When based on an upstream version, upstream's original changelog goes in a **separate section below**:
  ```
  # 1.9.3.1 Release
  ...our fork's changes...

  # 1.9.3 Release
  ...upstream's original changelog...
  ```
- Never merge upstream changelog into our version's section
- The CI release pipeline extracts the matching section from CHANGELOG.md by the `pretty_version` title

## Upstream developer API compatibility

`api/src/main/kotlin/com/dreamdisplays/` is a **verbatim copy** of upstream's `com.dreamdisplays.api` tree (101 files), and `core/src/main/kotlin/com/dreamdisplays/core/addon/` is the matching `AddonManager`/binders. The point is binary compatibility: an addon compiled against upstream references `com.dreamdisplays.api.addon.Addon` and friends, and those fully-qualified names must exist here at runtime too.

Rules that keep this honest:
- **Never rename or relocate `com.dreamdisplays`.** `dreamDisplaysShadedPackages` in `gradle/src/main/kotlin/support/shadow/DreamDisplaysXShadow.kt` must never gain a `com.dreamdisplays` entry; `relocateDreamDisplaysXSharedPackages()` runs on every loader's `shadowJar` and would silently rewrite the compatible package into `com.dreamdisplayx.libs.com.dreamdisplays`.
- The copy stays dependency-free: upstream's api imports only `java.*`, `kotlin.*` and `kotlinx.serialization`. Do not add Minecraft, libvlc or platform types to it, or `api` stops being a pure-Kotlin module that both loaders share.
- Bridging lives in `core/src/main/kotlin/com/dreamdisplays/core/bridge/`, not inside the copied tree. `ForkApiBackend` implements upstream's `ApiBackend` on top of the fork's `DisplayService`/`PlaybackService`; `CompatPlatform.kt` adapts `com.dreamdisplayx.api.platform.identity.Platform` to upstream's `Platform` (two *different* interfaces — passing one where the other is expected is a `ClassCastException`, hence `PlatformBridge`, `SchedulerBridge`, `TaskHandleBridge`, `LoggerBridge`, `PathsBridge`).
- `ForkApiBackend` takes an `addonSink: ((Any) -> Unit)?`. If it is absent, `registerAddon` logs and refuses; if present, every registered `@Addon` goes straight to `AddonManager.register`. There is exactly **one** addon annotation family (`com.dreamdisplays.api.addon.Addon`) and exactly one manager (upstream's `AddonManager`), so an upstream addon and a fork-only addon share the same dependency graph, enable order and reverse-order teardown.
- Registration matches upstream: there is **no addon scanning**. `DreamDisplays.register(addon)` → `ApiHolder.whenReady` → `ForkApiBackend.registerAddon` → `AddonManager.register`. `ServerHolder.attach` / `ApiHolder.attach` must run **before** `AddonManager.start()`, otherwise registrations made during boot stay queued forever (`ServerHolder.detach` only clears `current`, it does not drain).
- `kotlin`/`kotlinx` *are* relocated (`com.dreamdisplayx.libs.*`) — upstream does the same with its own prefix, so this is upstream-native behaviour, not a fork regression. Keep public signatures taking `Any`/`Consumer`/plain JVM types (as `DreamDisplays.register(addon: Any)` and `Events.on(T::class.java, Consumer)` do) instead of `Function0`/`Function1`, so relocated descriptors cannot differ from what an addon compiled against upstream expects.
- Server wiring is `ServerAddonRuntime` (`platform/server/api`): `start(host, resolver)` installs the `ServerVetoes.PositionResolver`, then `ServerAddonHooks.install(host)`, then builds `ServerAddonVetoBridge`, then `ServerHolder.attach(ServerAddonBackend(...))`, then `AddonManager.start()`. Both `PaperServer.doEnable` and `VanillaBootstrap.onServerStarted` call it; both `doDisable` / `onServerStopping` call `ServerAddonRuntime.stop()`. `AddonManager` is held in a `by lazy`, so a `/reload` keeps its registrations.
- An addon veto must land in the **same** `DisplayVetoRegistry` the built-in create / delete / set-video gates consult. `ServerAddonVetoBridge` exists for that: it lazily subscribes to `host.vetoes()`, converts the fork's `DisplayVeto` (flat `Target`) into upstream's `com.dreamdisplays.api.server.DisplayVeto` (which carries a `ServerDisplay`), runs the addon listeners, and writes a denial back with `forkVeto.deny(...)`. Registering the addon listeners beside the registry instead would let an addon veto a change that then happened anyway.
- **`com.dreamdisplays.api.*` and `com.dreamdisplayx.api.*` are different classes that share names.** `DisplayFacing`, `PlaybackMode` and `DisplayAccess` each exist twice with identical constants. `ServerAddonHost` / `ServerAddonDisplay` are declared in upstream's terms (an upstream-compiled addon must see the types it knows), while a stored row (`DisplayData`, `PaperDisplayData`, `VanillaDisplayData`) uses the fork's. Both hosts therefore import upstream's enums and translate explicitly — `toUpstreamMode` / `toUpstreamAccess` outwards, `toForkAccess` back — instead of assigning one to the other. Passing the wrong family compiles nowhere but produces `Class … is not abstract and does not implement abstract members` plus `Argument type mismatch`, because the override then matches no member at all.
- That mistake is invisible to a hand-rolled `kotlinc` check: compiling `api` + `core` + `platform/server` sources directly does not run chisel or PlatformWeaver, so the per-version generated tree never exists and the cross-package mismatch stays unresolved. Read the CI job log (`gh api repos/<owner>/<repo>/actions/jobs/<id>/logs`) rather than trusting a local green.
- Client wiring is `ClientApiBridgeModule` (`platform/client/common`), registered last in `ClientStartupManager.defaultModules` with `dependencies = listOf(CoreDisplayModule.id, CorePlaybackModule.id, MediaResolverModule.id)`.

## Display vetoes

`api/display/event/DisplayVeto` (port of upstream `023357e3`) lets a plugin refuse a display change before it happens. The fork could not take the upstream shape: upstream hangs `DisplayVetoes` off `DreamDisplaysServer` / `ServerDisplay` / `ServerEvents`, and this fork has none of those (it exposes `api.display.service.DisplayService` + `DisplayMutationPort` instead). So the fork ships a standalone `DisplayVetoRegistry` plus `platform/server/api/ServerVetoes`, which both loaders call directly.

- `DisplayVeto` carries a flattened `Target` (id, owner, size, url, name, virtual) rather than an API `Display`, because the world position/facing types (`PaperDisplayData` vs `VanillaDisplayData`) never unify across loaders. Do not try to build a full `Display` in the veto path.
- The three call sites are `DisplayActions` / `VanillaDisplayActions` `delete` and `setVideo`, plus `CreateCommand` (both the Paper and vanilla branch). `setVideo` sanitizes the language BEFORE asking, so a listener sees the value that would actually be stored.
- `ServerVetoes.ask*` short-circuits on an empty registry, logs a throwing listener instead of propagating, and returns `null` for allowed / a possibly-empty string for denied. The first denial wins.
- `DisplayVetoRegistryTest` covers dispatch, first-denial-wins, empty denials, throwing listeners, unsubscribe and clear. Keep it green: it is the only pure-JVM coverage of this path.

## Optional Valkyrien Skies ship anchoring

Ship support is **reflection only**; the fork must never take a `org.valkyrienskies` dependency, because one
artifact per Minecraft version times eleven targets is unmaintainable and VS does not support every version.

- API layer (`:api`) stays physics-agnostic: `ShipAnchor`/`AffineTransform`/`ShipPoseMath`/`ShipAnchorPolicy`
  traffic in bare `Long`/`Double`/`DoubleArray` only. `ShipTransformSource` is the seam — a `fun interface`
  returning the column-major `DoubleArray` (JOML layout, so no transpose).
- `platform/server/.../compat/ValkyrienCompat` resolves the mod lazily into a sealed `Resolution`
  (`Absent` / `Ready`) and every entry point is `runCatching`-wrapped: a broken or absent install must degrade
  to "no ships here" (`shipId = 0`), never throw. `ClassNotFoundException` is silent; anything else warns once.
- **Reflected signatures must come from the shipped jar, not memory.** `VSGameUtilsKt.getAllShips(Level)` takes
  `Level`, *not* `ServerLevel`, and `getMethod` matches declared parameter types exactly — passing `ServerLevel`
  throws, and the catch-all then marks the whole bridge `Absent` forever with only a `debug` line. Verify with
  `javap -classpath <vs.jar> org.valkyrienskies.mod.common.VSGameUtilsKt`. `Matrix4dc.get(double[])` is JOML's
  (from the Minecraft classpath, not VS). Ship lookup by id goes through `getAllShips` + `getId()` comparison
  rather than `QueryableShipData.getById`, whose generic return differs between VS releases; the registry is a
  `Collection`, so plain iteration works.
- `DisplayData.shipId` is a `var` with a no-op default setter on the interface, so `stampShipAnchor` can write it
  through the platform-neutral type; `PaperDisplayData`/`VanillaDisplayData` override it with stored properties.
  Both creation paths stamp it — `CreateCommand` (both branches) and the addon `create()` hosts.
- Coordinates in a ship-anchored row are **structure space**. Anything that samples the world at a stored
  coordinate must skip those rows: `DisplayManager.validateDisplaysAndCleanup` gates on
  `ShipAnchorPolicy.isShipAnchored`, otherwise the base-material census reads air at every ship display and
  deletes the lot on the next player join. `ShipAnchorPolicy.shouldDropAnchor` must stay in agreement with that
  predicate (`DropTest` pins it).
- Paper reaches NMS through `nmsLevelOf(world)`, which reflects `getHandle()` (CraftWorld's package carries the
  Minecraft version, so it cannot be hardcoded) and falls back to `getDeclaredMethod` with access forced open,
  because some Paper forks narrow that visibility.
- Verification without Loom: the bridge can be exercised against stub VS classes with a hand-built
  `K2JVMCompiler` classpath — compile once *without* the stub types (the absent path, which every non-VS server
  takes) and once *with* them (the resolution path, including a matrix round-trip).

### Client half: `ValkyrienClientCompat` and the render/aperture paths

`platform/client/common/.../render/ValkyrienClientCompat` mirrors the server bridge. Two reflection traps were
found only by running the bridge against stubs, so do not "simplify" them away:

- **`getShipToWorld()` must be resolved on the transform's runtime class, not on `Ship`.** `Ship` declares the
  same name, but invoking a `Ship`-typed `Method` with a `ShipTransform` receiver throws
  `IllegalArgumentException: object is not an instance of declaring class`. The bridge therefore resolves it per
  receiver class through `invokeMember`.
- **VS's ship-world implementation class is package-private.** `getAllShips()` is public on the interface but
  `Method.invoke` on the concrete class throws `IllegalAccessException: cannot access a member of class ...`,
  so `invokeMember` calls `setAccessible(true)` and caches the result per class. Without this the whole client
  bridge silently reported "no ship" and every display stayed at its structure coordinates.
- `ClientShip.getRenderTransform()` (not the plain transform) is used on purpose: VS interpolates it between the
  previous and current physics tick, so a display does not judder against the hull it is bolted to.
- `Minecraft.getInstance()` must be stubbed as a **singleton** in any test harness. A stub that returns a fresh
  instance per call leaves `level` permanently `null` and makes every transform identity, which reads exactly
  like a bridge bug.

Rendering has to compose `world = T * R * S * local`, i.e. `DisplayGeometry.applyShipPlacement` emits
`translate(transformedAnchor - camera)` → `rotate/mulPose(quaternion)` → `scale`. `mulPoseMatrix` is avoided on
purpose: the repo already compiles `translate`/`mulPose`(`rotate` at 26.3)/`scale` on all eleven versions, and
`AffineDecomposition` decomposes `AffineTransform` into exactly that triple (Shepperd quaternion extraction, so a
180° rotation does not divide by a near-zero trace). A matrix that cannot be decomposed returns `false` and the
renderer falls back to the plain block translation — a physics blow-up must never blank the world.

Three non-render consumers also read the anchor, and all three must map the observer through the **inverse**
transform before comparing coordinates, or a ship that has sailed away looks "very far" while the player stands
right in front of the picture:

- `DisplayScreen.getDistanceToScreen` → `api` `DisplayGeometry.distanceToOnShip` (`AffineTransform.inversePoint`,
  a 3x3 cofactor inverse so a non-uniformly scaled structure still maps back).
- `DisplayLifecycleManager.handleInfoPacket` / `distanceToData` / `distanceToScreen` — the render-distance census
  that decides whether a display is created, or restored by `restoreVisibleUnloadedScreens`. Both pass `shipId`.
- `DisplayScreen.toSourcePlane` — the acoustic plane. Its **centre** goes through `transformPoint` and its three
  basis axes through `transformDirection` (no translation, renormalised), otherwise the sound stays at the
  shipyard while the hull sails off.

`AffineDecomposition` (in `:api`) turned out to be a genuine JVM-testable seam even though the renderer is not:
`rungeom.sh` compiles the real client `DisplayGeometry` against a recording `PoseStack` stub and asserts the
operation order, the camera subtraction in world space and the `false` fallback. The renderer's GL output cannot
be unit-tested, but the geometry that feeds it can, and that is where the P4 logic lives.

### The anchor is stamped once — the client must discover it afterwards

`stampShipAnchor` runs **only** from `/display create`. A display built on loose blocks and assembled into a
ship afterwards therefore keeps `shipId = 0` for the rest of the save, `ShipAnchorPolicy.transformFor`
short-circuits on a world anchor, and the picture stays at the build site while the hull sails off — including
with no video playing, because the quad is placed from the stored coordinates alone. This was the 1.10.8.2
"the screen did not come with the ship" report, and it had nothing to do with the reflection bug above: the
`Valkyrien Skies client bridge resolved` line was absent from the log precisely because the renderer never
asked the mod anything.

Fix: `ShipAnchorDetectPolicy` (`:api`, pure numbers, unit-tested by `ShipAnchorDetectPolicyTest`) schedules a
probe, and `DisplayScreen.anchoredShipId` runs it and folds the result in. Rules that must not be broken:

- **A server anchor is never second-guessed.** `shouldProbe` returns `false` the moment `shipId > 0`, so a
  client guess can never overwrite the authoritative value. For the same reason `toFullDisplayData()` uploads
  the raw `shipId`, not `anchoredShipId` — two players near one display would otherwise race their own
  guesses into the database.
- **Discovery happens on the render path, throttled.** `anchoredShipId` is a getter that every reader
  (renderer, distance census, acoustics) calls; probing in `tick()` instead would leave the first frame
  wrong. `MISS_RETRY_TICKS = 10` / `HIT_RECHECK_TICKS = 100` keep the reflection off the per-frame budget and
  are exactly two numbers a test pins.
- **`updateData` clears the discovery state.** A moved or re-stamped display would otherwise keep drawing
  against whatever hull used to be at its old coordinates.
- **`DisplayLifecycleManager.distanceToScreen` deliberately does *not* probe.** The two censuses that call it
  decide whether a screen is created at all, so they must stay a pure comparison; the screen probes itself
  once it exists. Do not "fix" that asymmetry by calling the bridge there.

`ValkyrienClientCompat.discoverAt` runs three lookups, and **the coordinate space they report decides whether a
correction is returned**:

1. `getLoadedShipManagingPos(level, x, y, z)` — a **world**-position lookup; the bytecode does `>>4` and
   resolves the shipyard's chunk claim. A hit proves the stored numbers are world space, so it returns a
   correction.
2. A scan over `getAllShips()`, testing `getWorldAABB().containsPoint(x + 0.5, y + 0.5, z + 0.5)`. The world
   bound still contains an un-stamped row's coordinates, because assembling a ship does not move the blocks
   that were already placed. Also returns a correction.
3. A scan testing `getShipAABB().containsPoint(x, y, z)`. `getShipAABB()` is the **structure-space** voxel
   bound, the space a *stamped* row lives in. Returns no correction — such a row must not be re-projected.

The first two run before the third, deliberately: discovery only runs while `shipId == 0`, and that state has
one dominant cause — the server sampled the block before a structure existed, i.e. the build-then-assemble
case, whose coordinates are world space.

### The correction matrix, and why the order cannot be swapped

A build-then-assemble row stores a **world** position, but the renderer multiplies stored coordinates by the
live ship-to-world matrix. Applying that unchanged transforms the structure twice and flings the picture into
the void. The fix is `worldToShip` **captured at the moment of discovery**:

```
world_now = shipToWorld_now * worldToShip_then * storedPos
```

Two properties this buys, both pinned by `ShipAnchorCorrectionTest`:

- At discovery `shipToWorld_now == shipToWorld_then`, so the product is the identity and **the picture does
  not jump** when it is first pinned to the hull. It only starts following once the hull moves.
- The composition order is `live.times(correction)`, i.e. correction first. Swapping it rotates the display
  around the camera instead of around the hull origin.

Rules that must not be broken:

- **A miss never clears a previous hit.** After the first successful probe the blocks are inside the shipyard,
  so both world-space paths are *expected* to miss on every later recheck. Overwriting `detectedShipId` on a
  miss would un-pin the display one recheck later — the original bug, reintroduced.
- **`detectedCorrection` is kept once set.** A later structure-space hit reports `null`; adopting it would drop
  the conversion and re-apply the structure.
- **All readers go through `DisplayScreen.anchoredTransform`, not `ValkyrienClientCompat.transformFor`.**
  Renderer, distance census and acoustics must agree about where the picture is; the correction living in one
  path only is exactly how this class of bug keeps coming back.
- **Probing is silent by default, and that is a bug magnet.** The 1.10.8.2 first attempt shipped a silent probe
  and the resulting log could not distinguish "never found a hull" from "found it, placed it wrongly". The
  current probe logs the first outcome — *including a miss* — and every change of `shipId`, then stays quiet.

Verified contract of the stored coordinates (this matters if the detection is ever rewritten): VS's
`RaycastUtilsKt.clipIncludeShips` defaults `returnMissed = false` (`iconst_0` in the bytecode), so the
`BlockHitResult.location` it returns keeps **structure-space** values. That is what DDX stores, and it is why a
pure world-position probe would silently miss after a hull has moved.

## Experimental Remote Control

The Paper remote-control stick is experimental and uses a `PersistentDataContainer` entry named `remote_display_id`. A normal stick links to the nearest display under the player's look ray on right-click; the server adds a custom name and enchantment-glint override. Shift+right-click on a linked stick sends the experimental V3 `RemoteControlOpen` packet, and the client opens the linked `DisplayMenu` when that display is loaded. Keep the packet id append-only and validate the linked display server-side.

## Optional Flashback integration

Flashback support is experimental and reflection-only. Flashback is a Fabric client mod; NeoForge compatibility is provided by Sinytra Connector, so Dream DisplaysX must not add a hard NeoForge Flashback dependency. Do not assume Flashback's internal classes are stable APIs; all probes must fail closed when Flashback is absent or changes. Replay marker, audio, and export behavior may be incomplete. Flashback's visual timeline can be consumed for synchronization, but its internal keyframe/editor classes are not stable third-party APIs; render switches are persisted in Dream DisplaysX config with system-property overrides. Flashback captures Minecraft OpenAL loopback audio only; Dream DisplaysX's independent Java Sound/OpenSL ES output is not captured without a future SoundEngine PCM bridge.

## Known Issues
1. Danmaku track spacing doesn't scale with font size (hardcoded trackCount=8, 26px) — legacy note from upstream; the current danmaku implementation uses dynamic lane layout.
2. SettingsSection scissor clipping works but controls are placed every frame

## Hotkeys in `Options -> Controls`

`DisplayHotkeys` is the catalog of bindings a player may rebind; a loader must turn each entry into a real
`KeyMapping` or it never appears in vanilla's Controls screen. Three version facts are easy to get wrong and
all three were verified against the actual jars, not from memory:

- **`KeyMapping`'s category argument changed in 1.21.11.** Below it: `(String, int, String)` /
  `(String, Type, int, String)`, category is a translation-key string. From 1.21.11: the fourth parameter is
  `KeyMapping.Category`, a record around an `Identifier`. The category *translation key* changes with it —
  legacy is `key.categories.<ns>`, the record form derives `key.category.<ns>.<path>`. Lang files carry
  **both** spellings (`key.categories.dreamdisplayx` and `key.category.dreamdisplayx.hotkeys`).
- **`InputConstants.Type.KEYSYM` was renamed to `KEYBOARD` in 26.3**, and `SCANCODE` was removed. 26.1.2 and
  26.2 still expose `KEYSYM`/`SCANCODE`/`MOUSE`; 26.3 has only `KEYBOARD`/`MOUSE`. `HotkeyKeyMappings`
  routes this through one `keyboardType` property gated `//? if >=26.3`.
- **Forge 1.18.2 has no `RegisterKeyMappingsEvent`.** The class first appears in the 1.19.x branch, and
  `net.minecraftforge.client.ClientRegistry` (with `registerKeyBinding(KeyMapping)`) was *removed* from the
  1.19.4/1.20.1 universal jars. So `ForgeHotkeyRegistration` has two branches: `>=1.19` listens on the mod
  bus, `<1.19` calls `ClientRegistry` directly. The mod bus is
  `FMLJavaModLoadingContext.get().modEventBus` — **not** `ModLoadingContext.get().activeContainer`, which
  has no event bus at all (`ModContainer` exposes none) and is a private field.

`Category.register` is `@Deprecated` on NeoForge 1.21.11+ in favour of
`RegisterKeyMappingsEvent.registerCategory`, but the patch only adds the annotation: the body still appends
to `SORT_ORDER` and the mapping still groups correctly. The call carries `@Suppress("DEPRECATION")` in
common code rather than forcing a NeoForge type into a file Fabric and Forge also compile.

**Fabric API renamed its own key-binding module in 26.1.** `fabric-key-binding-api-v1` with
`net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(KeyMapping)` became
`fabric-key-mapping-api-v1` with
`net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(KeyMapping)`. The
`keybinding` package does **not** exist on 26.x — a single shared call compiled on 1.18.2-1.21.11 and failed
only on 26.1.2/26.2/26.3 (`Unresolved reference 'keybinding'`). Hence two files, one gate each:
`FabricHotkeyRegistration` (`//? if <26`) and `FabricHotkeyRegistration26` (`//? if >=26`), both exposing the
same `register()` so the loader entry point stays version-free. `KeyMapping.Category.register` still works
on 26.x — Fabric mixes in only to re-sort `SORT_ORDER`.

The in-menu keys (`Space`, arrows, `M`, `F`, `P`, digits) intentionally stay out of the catalog: they belong
to `DisplayMenu.keyPressed` and are contextual, and making them global bindings would collide with jump,
chat, offhand swap and the hotbar.

Chisel note for these files: a `//?} else` branch must open its commented body with `/*` on the next line.
A `//? if ... {` whose body must be *kept* is emitted verbatim, so wrapping live code in `/* ... */` inside a
true branch comments it out — hence the separate `//? if <1.19 { /* ... */ //?}` blocks instead of an inline
`/*` on the same line as the marker.

`ModKeyMappingsTest` and `DisplayHotkeysTest` live in `platform/client/common/src/test` and cover the id
routing and the catalog invariants. That module is **not** in the `unit-test` CI job (which runs `:api` and
`:core` only — see the Testing section), so those two tests are currently executed locally only. Do not add
`:platform:client:common:test` to the job until it has been run green somewhere, or the first red build
belongs to pre-existing breakage.

Local verification without Loom: `chiselSource` from `gradle/src/main/kotlin/support/chisel/Chisel.kt` is
pure Kotlin, so the generated per-version output can be reproduced by calling it directly with a hand-built
Kotlin compiler classpath, then compiled against small stub classes for `KeyMapping`/`InputConstants`. That
is how the 1.20.1 and 26.3 shapes above were checked while `./gradlew :platform:client:common:help` fails
offline on the missing Loom plugin.

## AI Language Policy
- 所有与本项目相关的 AI 回复和思考必须使用**中文**，以确保更好的理解。
- AI 助手必须以猫娘风格（本喵 / 主人大人）回应，使用中文。
- 代码注释、commit message 等仍然使用英文（遵循项目现有惯例）。
