# Dream DisplaysX — AGENTS.md

## Project Overview

**Dream DisplaysX** is a fork of [Dream Displays](https://github.com/arnodoelinger/dreamdisplays) that puts video displays inside Minecraft — watch YouTube, Twitch, Bilibili, or any video on a wall in your world.

## Key Architecture

### Multi-Version Build
- Uses **Stonecutter** for multi-version builds (1.21.1, 1.21.11, 26.1.2, 26.2)
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
- v2 protocol: `Envelope` (type id + protobuf bytes) over `dreamdisplayx:v2` channel
- `PacketType.kt` — append-only enum, never reuse ids
- `PacketRegistry.kt` — register all packet serializers
- Client→Server packets: `ClientHello`, `PlaybackCommand`, `SetVideo`, etc.
- Server→Client packets: `ServerHello`, `PlatformCredentials`, `DisplayInfo`, etc.

### Series Configuration
- Server config: `config.toml` (TOML format)
- Client config: `config.yml` (key: value format)
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

### 2026-02-DD — Android support restored
- Platforms: `android-aarch64` / `android-x64` (PojavLauncher / FCL / Zalith; ARM64 + x86_64)
- Detection: `OsInfo.isAndroid` (heuristic, was already present) — every Android branch keys off it
- Natives: `natives.yml` collects the official VLC-Android APK per ABI and ships its four `.so`
  (monolithic `libvlc.so`, plugins statically linked — no plugins dir); manifest gains the two
  android keys; `NativesDownloader`/`LibVlcNativesLoader` resolve `Android`/`aarch64|x86_64` dirs
- noexec: `AndroidPaths.nativesCacheRoot()` redirects the cache from the (noexec) game dir into
  app-internal storage (`java.io.tmpdir` → `user.home` → `/data/user/0/<pkg>/cache`)
- Audio: `javax.sound` does not exist on Android, so `LibVlcSessionManager.audioOutput` is
  nullable and NOT instantiated there (`systemAudio` flag); `audioPlayer()` returns null; the
  video player keeps its own audio and libvlc plays it via `--aout=opensl`; volume goes through
  `libvlc_audio_set_volume`. Desktop path unchanged.
- Video/instance: Android passes `--plugin-path=<natives dir>` + `--aout=opensl` +
  `--codec=mediacodec_ndk,mediacodec_jni,any` (MediaCodec, ByteBuffer copy mode); no
  `--avcodec-hw` (desktop-only concept)
- Native loading: Android JVMs report `os.name=Linux-Android`, so JNA's generic-Linux name
  mapping turns `Native.load("libvlc", ...)` into a doubled `liblibvlc.so` lookup. `LibVlc`
  therefore loads the exact extracted `libvlc.so` path on Android via
  `LibVlcNativesLoader.jnaLoadTarget()` (desktop keeps the plain `"libvlc"` name). The Android
  linker ALSO does not search a library's own directory for its `DT_NEEDED` deps, so the
  Android companions (unique-SONAME `libc++_dreamdisplayx.so` / `libvlcjni.so`, same directory)
  are preloaded first with `RTLD_GLOBAL` (`System.load`, multi-pass until no progress) in
  `LibVlcNativesLoader.preloadAndroidCompanions()` before `libvlc.so` is opened; without it
  dlopen dies on `cannot locate symbol _ZTTNSt6__ndk118basic_stringstream...`.
- Android natives source: the official `org.videolan.android:libvlc-all` Maven AAR (3.7.5)
  instead of the VLC-Android APK — the library-form runtime squi2rel/VideoPlayer also uses
  (`libvlc.so` + `libvlcjni.so` + full `libc++_shared.so`; no `libmla.so`). `native/libvlc/build.sh`
  renames libc++ to a unique SONAME (`libc++_dreamdisplayx.so`) and rewrites `libvlc.so`'s
  `DT_NEEDED` via `patchelf` so the Android linker can never deduplicate it against the
  same-named `libc++_shared.so` that Pojav-style launchers (Zalith/FCL) already loaded from
  their own runtime dir — that stale copy silently bound before and lacked the stream vtable
  symbol libvlc needs. `NativesDownloader.hasLibVlc()` additionally requires the renamed
  libc++ on Android, so old APK-format caches are invalidated and re-downloaded.
- AWT guards: `VideoPopoutWindow.isAvailable` returns false on Android and `ModTitleLabel`
  catches `LinkageError` (no `java.desktop` module); Thumbnails/ScrubPreview decode paths were
  already `runCatching`-guarded and degrade gracefully
- Initializer no longer blocks Android startup; AWT headless override skipped on Android

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

## Workflow Rules

### Post-Change Checklist (MANDATORY)
Every code change MUST be followed by ALL of the below before the task is considered complete:
1. **Commit** — stage all changes and create a descriptive commit message
2. **Push** — push to the remote branch immediately after commit
3. **Documentation** — update `README.md` and `CHANGELOG.md` when the change is user-visible or affects behavior/features
4. **AGENTS.md** — if the change affects architecture, build process, or workflow rules, update `AGENTS.md` accordingly

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
- Add entries under the current/next version heading
- Never merge upstream changelog into our version's section
- **Every fork version section MUST contain** a `Based on Dream Displays [<commit>](<url>)` line immediately after the version heading, linking to the upstream commit this fork version is based on. This line must never be removed or altered to point to a different commit.
- Only sync from the upstream `main` branch; never merge other upstream branches (e.g. `feat/new-readme`).

## CI/CD & Versioning Rules

- `_build.yml` — Main build workflow (multi-version)
- `release.yml` — Release pipeline (dev/preview/release)

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

## Known Issues
1. Danmaku track spacing doesn't scale with font size (hardcoded trackCount=8, 26px)
2. SettingsSection scissor clipping works but controls are placed every frame

## AI Language Policy
- 所有与本项目相关的 AI 回复和思考必须使用**中文**，以确保更好的理解。
- AI 助手必须以猫娘风格（本喵 / 主人大人）回应，使用中文。
- 代码注释、commit message 等仍然使用英文（遵循项目现有惯例）。