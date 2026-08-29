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

