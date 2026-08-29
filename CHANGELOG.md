# 1.9.5.1 Release

Based on Dream Displays [8ccaf45](https://github.com/arnodoelinger/dreamdisplays/commit/8ccaf45).

## Highlights

- **Full libvlc rewrite (vlcj removed)** — the media pipeline is ported from JavaCPP/FFmpeg to a low-level JNA libvlc binding: single libvlc instance + player, never rebuilt, video via lock/unlock/display callbacks into a grow-only triple-buffer pool. No more `JNA: callback garbage collected` spam, switch flicker, or stutter.
- **Two-player A/V split** — the video player runs `:no-audio` (system clock → full source FPS); a separate audio-only player (`:no-video`) feeds the 3D DSP + Java Sound. Audio snaps to video every ~10s on drift >300ms. Fixed the audio-clock throttle that kept the vout at ~60% of source FPS.
- **3D DSP audio restored** — libvlc audio callbacks deliver decoded PCM through the per-display `AudioDspStage` (direction, occlusion, reverb) to a Java Sound `SourceDataLine`; volume as PCM gain.
- **Hardware decode: d3d11va default on Windows** — explicit per-OS backend (d3d11va / vaapi / videotoolbox), `-Ddreamdisplayx.hwDecode` override, F3 shows the real decoder.
- **Scrub preview on demand** — per-hover extraction from the lowest-quality (≤360p) H.264 stream via a long-lived software-decoded player; no more pre-generated sample frames.
- **Stretch mode + GPU-scaled rendering** — video is drawn at native aspect with the GPU doing the scaling; STRETCH / LETTERBOX / CROP UV modes; z-fighting flicker fixed.
- **Ported upstream 1.9.5 fixes** — displays no longer turn black behind water / glass (drawn before translucent terrain + shader-pack replay pass), partially-written persistent PBO uploads no longer corrupt textures on Windows, and the NeoForge `sqlite-jdbc` clash is fixed by shipping it as a jar-in-jar.
- **Render-distance menu slider removed** — displays now unload by the client's own chunk render distance (upstream behavior); the distance-based quality scaler is untouched.
- **Android support restored** — runs under PojavLauncher / FCL / Zalith launchers on ARM64 and x86_64 devices: the natives cache moves to exec-friendly app-internal storage, audio plays through libvlc's OpenSL ES output (3D positional audio unavailable there), and hardware decoding uses MediaCodec (ByteBuffer copy mode). **SQLite persistence works too** — the stock `sqlite-jdbc` jar ships no Android native, so a Bionic build (extracted from xerial's own `-sources` artifact) is bundled and loaded via `org.sqlite.lib.path` / `org.sqlite.lib.name`, keeping display + credential storage on-device. Desktop platforms are unaffected.

## Playback

- **Pause/resume crash fixes** — render thread no longer touches the audio line (0xC0000374 heap corruption); drop buffer padded to a full frame (0xC0000374 on seek); audio callback window clamped ~0.25s (0xC0000409); no more `SourceDataLine.stop()/start()` (0xC0000409/0xC0000005).
- **A/V auto-resync (bidirectional)** — flushes and re-anchors when the audio buffer drifts >300ms in either direction, plus a 1.5s initial sync after playback/replay start.
- **Loop/replay fixes** — ENDED path does `stop()`+`play()`, re-arms `eosFired` (no more freeze on second replay), and restarts the audio player so audio replays too.
- **Native runtime bootstrap** — libvlc + SQLite runtimes are collected from official VideoLAN distributions by CI and downloaded at runtime into `./dreamdisplayx/natives/`; libvlc upgraded 3.0.21 → 3.0.22.

## Scrub preview

- Per-hover single extraction (coalesced), prefers H.264 ≤360p, `:network-caching=300`, seek-while-playing gated past the target so backward seeks never cache a stale frame; failed frames keep the nearest valid thumbnail.

## Debug overlay

- F3 shows `Video FPS`, `Stream` (codec/resolution/fps), `Frame int` (min/avg/max ms — vout dropping vs decoder slow), the real decoder name, and commit id. `-Ddreamdisplayx.debugFps=true` draws the live FPS on the menu preview.

## Tunable diagnostics

- JVM flags: `-Ddreamdisplayx.networkCachingMs=<ms>` (default 300), `-Ddreamdisplayx.audioBufferMs=<ms>` (default 100), `-Ddreamdisplayx.hwDecode=<backend>`, `-Ddreamdisplayx.verboseLibvlc=true`, `-Ddreamdisplayx.noDropLateFrames=true`, plus bisection switches `silentAudio` / `noAudioCallback` / `noFrameSink` / `noVideoPublish` / `noAutoResync` / `noHardwareAccel`. See README for details.

## Code quality

- Routine/diagnostic logs moved from WARN/INFO to DEBUG; dead code (`Processes.kt`) and unused imports removed; per-call Regex/MD5 hoisted to cached fields; audio DSP hot path optimized (LoudnessMeter alpha precomputed, ParametricBinaural `read(0f)` skipped — bit-equivalent); `BilibiliAccountLabel` gets a 30s failure backoff.

## Sources

- Bilibili quality selector shows canonical resolution labels (360P / 480P / 720P / 1080P / 4K).
- Bilibili CDN mirror selection with startup bandwidth ranking (based on [PiliPlus](https://github.com/piliplus/piliplus)) and `bilibili-cdn-mirror` config; session refresh removed (endpoint always errors).
- Removed `yt-dlp` orchestrator and the YouTube resolver chain (`NewPipeExtractor`); search is now `DirectSearchService` (URL paste → info card, `BV`/`av` → Bilibili video).
- Bilibili unreleased content filtered from search; resolution resolved fresh on every play (no stale 30-minute cache); fixed 4K blur by allocating the texture at the source height.
- Login/logout i18n + full Chinese server translation.

## CI / Build

- SQLite native build split into a standalone workflow; CI skips builds on doc/workflow-only changes.
- Commit ID stamped into the build and shown in F3 (`Commit: <hash>`).
- Natives workflow collects the official LibVLC runtime for 9 platforms: 7 desktop plus Android ARM64/x86_64 (extracted from the official VLC-Android APK, monolithic `libvlc.so`).

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

# 1.9.5 Release

## Highlights

- Enhanced README file
- Rendering enhancements and fixes
- `NeoForge` `sqlite-jdbc` fix

## Client

### Improvements

- Enhanced README file ([#186](https://github.com/arnodoelinger/dreamdisplays/pull/186))

### Fixes

- Fixed displays turning into a black rectangle when seen through water or glass
- Fixed persistently mapped PBO frame uploads occasionally corrupting or freezing display textures on Windows ([#199](https://github.com/arnodoelinger/dreamdisplays/issues/199))

## Server

### Fixes

- Fixed the `NeoForge` server failing to start with a module resolution error when another mod also bundles `sqlite-jdbc` ([#201](https://github.com/arnodoelinger/dreamdisplays/issues/201))

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
- Improved `Dream Displays` wiki
- Various other fixes and improvements

## Client

### Features

- `NeoForge` server support (including single-player) ([#95](https://github.com/arnodoelinger/dreamdisplays/issues/95))
- Full Twitch support
- New Borderless and Fullscreen display modes, with a new `/display fullscreen` command for events and presentations
  ([#135](https://github.com/arnodoelinger/dreamdisplays/pull/135))
- Added custom video support and file-host share link support (Google Drive, Dropbox, imgur, etc.) — paste a direct link
  to any video and play it on a display (server must be 1.9.0 or higher)
- Added Twitch, Kick, Vimeo, and Bilibili support ([#129](https://github.com/arnodoelinger/dreamdisplays/pull/129), [#129](https://github.com/arnodoelinger/dreamdisplays/pull/156), [#173](https://github.com/arnodoelinger/dreamdisplays/issues/173))
- Added 3D acoustics for displays: sound is muffled by walls (occlusion), loses highs over distance (air absorption),
  and picks up room / cave reverberation raytraced from nearby blocks and their material; e.g., stone reflects, wool
  absorbs ([#147](https://github.com/arnodoelinger/dreamdisplays/pull/147))
- Added an audio track selector, so you can pick your language right in the display menu
  ([#149](https://github.com/arnodoelinger/dreamdisplays/pull/149))
- Added `/display schedule` command to schedule a video to play at a specific time
- Added `/display name` command ([#151](https://github.com/arnodoelinger/dreamdisplays/pull/151))
- Changed some display commands syntax: now you can choose the display by its ID or by looking at it and typing "this"
- Added support for saving and restoring the last known playback position everywhere, and each display's custom render
  distance across game restarts
- Added distance-based quality: a display now steps its video quality down as you move away from it (one step at 66% of
  its render distance, two steps at 75%)
- Added YouTube chapter markers
- Added a filter button
- Added click sounds when clicking buttons in the display menu
- Gray-out only seekbar and near buttons when the display isn't ready yet, instead of the whole menu
- Added [Crowdin](https://crowdin.com/project/dreamdisplays) integration
  ([#141](https://github.com/arnodoelinger/dreamdisplays/pull/141))
- Brought back `NeoForge` 1.21.11 releases to the [corporate ad dispenser](https://www.curseforge.com/)

### Improvements

- Improved displays performance ([#131](https://github.com/arnodoelinger/dreamdisplays/pull/131))
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
- Enhanced UI components ([#148](https://github.com/arnodoelinger/dreamdisplays/pull/148))
- Now recommendations are endlessly scrolling
- Enhanced ambient grid ([#136](https://github.com/arnodoelinger/dreamdisplays/pull/136))
- All sliders now have snap behavior and fixed subdivisions for better precision
- Improved popout context menu positioning
- Improved scrollbars: now you can drag them
- Retry on "Not all references are available" error instead of fatal erroring
- Removed the greyed-out buttons state while a display isn't ready yet
- Added an author's avatar and a verified badge next to their name
- Enhanced cursor handling for 1.21.11
- Improved `Gradle` build system, so it looks less like a frankenstein
  ([#150](https://github.com/arnodoelinger/dreamdisplays/pull/150))
- Improved platform resources structure
- Codebase improvements: more Kotlin analogues instead of Java imports, optimized imports
- Improved KDoc documentation in the codebase
- Improved wiki

### Fixes

- Fixed video sometimes freezing indefinitely
- Fixed a `Synced` / `Broadcast` display sometimes getting stuck on "Waiting for video..." forever
  ([#138](https://github.com/arnodoelinger/dreamdisplays/issue/138))
- Fixed "Unrecoverable stream failure" error when using Iris shaders
  ([#146](https://github.com/arnodoelinger/dreamdisplays/issue/146))
- Fixed live resume, live quality switches, and stall recovery blocking every other play / pause / seek / etc. action on
  the display for the whole network re-resolve
- Fixed some videos getting a permanently broken stream (403 Forbidden) instead of falling back to a working one
- Fixed retries being silently unlimited when a resolved stream failed to open right away
- Fixed the background quality refresher endlessly restarting a live stream when the closest available rendition didn't
  exactly match the requested quality
- Fixed video getting stuck when seeking right after changing quality
  ([#121](https://github.com/arnodoelinger/dreamdisplays/issues/121))
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
- `NeoForge` server support ([#95](https://github.com/arnodoelinger/dreamdisplays/issues/95))
- New command: `/display fullscreen` for events and presentations
  ([#135](https://github.com/arnodoelinger/dreamdisplays/pull/135))
- Full `LuckPerms` support on `Fabric` / `NeoForge` servers
  ([#128](https://github.com/arnodoelinger/dreamdisplays/pull/128))
- Added a `[custom_media]` config section and a `dreamdisplays.custom` permission to control whether players may play
  their own links (Vimeo, Kick, and direct files), with optional per-host allow / blocklists
- `/display video` now accepts any supported link, not only YouTube URLs
- Added `max_displays_per_player` config limit and wired up the `create_bypass` permission and
  `fullscreen.quality_cap` setting
- Added explosion protection for `Fabric` and `NeoForge` servers

### Improvements

- Reduced per-player memory overhead on long-running servers with many unique joins
- `Dream Displays` security improvements
- Added a `storage.use_ssl` option in `config.toml` to enable TLS on the `MySQL` connection (was hardcoded off)
- Repeat all videos for all platforms on every playback mode
  ([#127](https://github.com/arnodoelinger/dreamdisplays/pull/127))
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
  ([#164](https://github.com/arnodoelinger/dreamdisplays/issue/164))
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

- Fixed explosion display protection mixin crash ([#161](https://github.com/arnodoelinger/dreamdisplays/issue/161))

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

- Added a `[custom_media]` config section and a `dreamdisplays.custom` permission to control whether players may play
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
  ([#138](https://github.com/arnodoelinger/dreamdisplays/issue/153))

# 1.9.0 Preview 3

## Highlights

- 3D audio support
- Language selector right in the display menu
- Now recommendations are endlessly scrolling
- [Crowdin](https://crowdin.com/project/dreamdisplays) platform integration
- Fixed some bugs, including crash on `Fabric` 1.21.11
- New Gradle build system
- Some other minor improvements

## Client

### Features

- Added 3D acoustics for displays: sound is now muffled by walls (occlusion), loses highs over distance (air
  absorption), and picks up room / cave reverberation raytraced from nearby blocks and their material; e.g., stone
  reflects, wool absorbs ([#147](https://github.com/arnodoelinger/dreamdisplays/pull/147))
- Added audio track selector, so you can select your language right in the display menu
  ([#149](https://github.com/arnodoelinger/dreamdisplays/pull/149))
- Added subtitles support ([#151](https://github.com/arnodoelinger/dreamdisplays/pull/151))
- Added click sounds when clicking on buttons in the display menu
- Added [Crowdin](https://crowdin.com/project/dreamdisplays) integration
  ([#141](https://github.com/arnodoelinger/dreamdisplays/pull/141))

### Improvements

- Enhanced UI components ([#148](https://github.com/arnodoelinger/dreamdisplays/pull/148))
- Now recommendations are endlessly scrolling
- Enhanced cursor handling for 1.21.11
- Improved popout context menu positioning
- Improved scrollbars: now you can drag them
- Retry on "Not all references are available" error instead of fatal erroring
- Removed greying out buttons feature when the display is not ready yet
- Added author's avatar by their name
- Added verified badge by author's name
- Improved Gradle build system, so that looks less like a frankenstein
  ([#150](https://github.com/arnodoelinger/dreamdisplays/pull/150))
- Improved platform resources structure
- Use more Kotlin analogues instead of Java imports
- Optimized imports

### Fixes

- Fixed "Unrecoverable stream failure" error when using Iris shaders
  ([#146](https://github.com/arnodoelinger/dreamdisplays/issue/146))
- Fixed a `Synced` / `Broadcast` display sometimes getting stuck on "Waiting for video..." forever
  ([#138](https://github.com/arnodoelinger/dreamdisplays/issue/138))
- Fixed disappearing video preview when pausing and returning to the menu
- Fixed video sometimes freezing indefinitely
- Fixed disappearing suggestions in some cases after a stutter / lag spike, requiring a seek to unstick it
- Fixed retries being silently unlimited when a resolved stream failed to open right away
- Fixed some videos getting a permanently broken stream (403 Forbidden) instead of falling back to a working one
- Loop `Local` displays on instead of freezing

## Server

### Fixes

- Fixed crash on `Fabric` 1.21.11 caused by invalid `BareTokenArgumentType` registration
  ([#137](https://github.com/arnodoelinger/dreamdisplays/issue/137))
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

- `NeoForge` server support (including single-player) ([#95](https://github.com/arnodoelinger/dreamdisplays/issues/95))
- Full Twitch support ([#129](https://github.com/arnodoelinger/dreamdisplays/pull/129))
- New Borderless and Fullscreen display modes ([#135](https://github.com/arnodoelinger/dreamdisplays/pull/135))
- New command: `/display fullscreen` for events and presentations
  ([#135](https://github.com/arnodoelinger/dreamdisplays/pull/135))
- Added support for saving and restoring the last known playback position everywhere
- Added support for saving and restoring each display's custom render distance across game restarts

### Improvements

- Enhanced UI components
- Now all sliders have snap behavior and fixed subdivisions for better precision
- Improved displays performance ([#131](https://github.com/arnodoelinger/dreamdisplays/pull/131))
- Added scrubbing preview on the seek bar (frame preview on hover)
- Reduced native decode-path overhead on every frame, for smoother in-process playback
- Increased stall watchdog threshold from 30 to 45 seconds to avoid false positives on slow networks
- Enhanced ambient grid ([#136](https://github.com/arnodoelinger/dreamdisplays/pull/136))
- Codebase improvements

### Fixes

- Fixed restoring display snapshots from prior sessions
- Fixed the background quality refresher endlessly restarting a live stream when the closest available rendition didn't
  exactly match the requested quality
- Fixed the display menu's video preview being fit to the display's own block shape instead of the video's aspect ratio
- Fixed video getting stuck when seeking right after changing quality
  ([#121](https://github.com/arnodoelinger/dreamdisplays/issues/121))
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

- `NeoForge` server support ([#95](https://github.com/arnodoelinger/dreamdisplays/issues/95))
- Full `LuckPerms` support on `Fabric` / `NeoForge` servers
  ([#128](https://github.com/arnodoelinger/dreamdisplays/pull/128))
- New command: `/display fullscreen` for events and presentations
  ([#135](https://github.com/arnodoelinger/dreamdisplays/pull/135))

### Improvements

- `/display delete` is now available to everyone for their own displays
- Codebase improvements: more modularization and cleanup
  ([#127](https://github.com/arnodoelinger/dreamdisplays/pull/127))
- `Fabric` / `NeoForge` server display deletion now notifies nearby clients itself, matching `Paper`, so a future caller
  can't forget to broadcast
- Added a `storage.use_ssl` option in `config.toml` to enable TLS on the `MySQL` connection (was hardcoded off)
- Reduced per-player memory overhead on long-running servers with many unique joins
- Dream Displays security improvements
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
  ([#121](https://github.com/arnodoelinger/dreamdisplays/issues/121))
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

- Fix critical crash for `Fabric` 1.21.11 & 26.1.2 ([#118](https://github.com/arnodoelinger/dreamdisplays/issues/118))

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
- Improved Dream Displays security

### Fixes

- Fixed incompatibilities with high-quality shaders ([#108](https://github.com/arnodoelinger/dreamdisplays/issues/108))
- Fixed unnecessary sync corrections while media is paused or parked
- Fixed a rare internal service lookup issue that could affect features with multiple service implementations

## Server

### Improvements

- Improved experimental API
- Improved report cooldown handling under repeated report attempts
- Improved media links, network requests, and JSON handling for server-side media features
- Improved saved display storage so display data is safer across restarts and crashes
- The mod update notification is now shown once per server session
- Improved Dream Displays security

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
- Improved Dream Displays security

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
- Improved Dream Displays security

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
- Improved Dream Displays security

### Fixes

- Fixed audio cutting out after about 10 seconds ([#107](https://github.com/arnodoelinger/dreamdisplays/pull/107))
- Fixed repeating video playback in local playback mode

## Server

### Improvements

- Players can no longer spam the report system
- Improved Dream Displays security

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
- Brought back Minecraft 1.21.11 support ([#91](https://github.com/arnodoelinger/dreamdisplays/pull/91))
- Added a native Rust media pipeline with `FFmpeg` and in-process LAV decoding
- Added stable `Vulkan` support for display rendering (`OpenGL` rendering is still supported)
- Replaced the old synchronization mode with new local, synced, and broadcast playback modes
- Added a new packet protocol v2
- Reduced CPU usage by up to 50–70× on tested hardware scenarios (Java 25 required)
- Improved video stream resolving speed by up to 10–12× in supported cases

## Client

### Features

- Added support for Minecraft 26.2
- Brought back Minecraft 1.21.11 support ([#91](https://github.com/arnodoelinger/dreamdisplays/pull/91))
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
- Fixed FFmpeg extraction on Linux ([#93](https://github.com/arnodoelinger/dreamdisplays/issues/93))
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
- Added `dreamdisplays.local`, `dreamdisplays.synced`, `dreamdisplays.broadcast`, `dreamdisplays.lock`,
  `dreamdisplays.delete.others`, and `dreamdisplays.create.bypass` permissions
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

- Fixed `MariaDB` compatibility issue ([#88](https://github.com/arnodoelinger/dreamdisplays/pull/88))
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
  ([#79](https://github.com/arnodoelinger/dreamdisplays/issues/79))

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
  ([#79](https://github.com/arnodoelinger/dreamdisplays/issues/79))
- Fix a strange version number in the menu ([#81](https://github.com/arnodoelinger/dreamdisplays/issues/81))
- Fix version semantic versioning parsing for mod updates
- Fix tiled thumbnail rendering in the menu
- Fix texture race crash in some rare cases
- Fix a locked quality bug ([#80](https://github.com/arnodoelinger/dreamdisplays/issues/80))
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
- Fix language selector ([#73](https://github.com/arnodoelinger/dreamdisplays/issues/73))
- Fix volume reset after leaving active display distance
  ([#76](https://github.com/arnodoelinger/dreamdisplays/issues/76))
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
- Change maximum of render distance to 128 blocks ([#59](https://github.com/arnodoelinger/dreamdisplays/issues/59))
- Change maximum volume to 200% ([#60](https://github.com/arnodoelinger/dreamdisplays/issues/60))
- Support CurseForge releases
- Smoother video playback and some optimizations

## Mod

- We've created [Discord server](https://discord.gg/uwMMZ2KWk6)!
- Smoother video playback and some optimizations
- Video brightness control
- Store paused state of display
- Change maximum of render distance to 128 blocks ([#59](https://github.com/arnodoelinger/dreamdisplays/issues/59))
- Change maximum volume to 200% ([#60](https://github.com/arnodoelinger/dreamdisplays/issues/60))
- Fix playing videos after changing quality
- Support CurseForge releases
- Documentation in codebase of the mod

## Server

- Refactors and small improvements
- Documentation in codebase of the plugin
- Improve update logic and fix ignoring mod versions ([#63](https://github.com/arnodoelinger/dreamdisplays/issues/63))

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
- New mod name Dream Displays
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
