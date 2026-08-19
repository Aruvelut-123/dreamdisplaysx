# Dream DisplaysX — Session Task State (2026-08-10)

Resume file for the ongoing session. Working dir: `D:\IdeaProjects\dreamdisplaysx`
Remote: `origin https://github.com/Aruvelut-123/dreamdisplaysx.git` (branch `main`)

---

## DONE (working tree, NOT committed yet)

### 1. Full rename `dreamdisplays` -> `dreamdisplayx`
- Renamed 23 directories (`com/dreamdisplays` -> `com/dreamdisplayx`, `assets/dreamdisplays` -> `assets/dreamdisplayx`).
- Renamed 18 files (`DreamDisplays*.kt` -> `DreamDisplaysX*.kt`, `dreamdisplays.mixins.json` -> `dreamdisplayx.mixins.json`,
  `dreamdisplays.server.mixins.json`, `dreamdisplays.classtweaker`, `dreamdisplays.proto`,
  gradle convention files `dreamdisplayx.*.gradle.kts`, `dreamdisplayx.stonecutter-versions.settings.gradle.kts`).
- Content replacement across 647 text files, ordered to protect existing `dreamdisplaysx` / `Dream DisplaysX`:
  1. modrinth URLs -> github.com/Aruvelut-123/dreamdisplaysx
  2. `arnodoelinger/dreamdisplays` -> `Aruvelut-123/dreamdisplaysx`
  3. `com.dreamdisplays` -> `com.dreamdisplayx`
  4. `DREAMDISPLAYS` -> `DREAMDISPLAYX` (env vars)
  5. `dreamdisplays(?!x)` -> `dreamdisplayx`
  6. `DreamDisplays(?!X)` -> `DreamDisplaysX`
  7. `Dream Displays(?!X)` -> `Dream DisplaysX`
- Verified: 0 remaining `dreamdisplays` (not followed by x), 0 `DreamDisplays` (not followed by X), 0 double-replace (`dreamdisplayxx` / `DreamDisplaysXX`).
- Key files verified: settings.gradle.kts rootProject.name = "dreamdisplayx", gradle.properties group=com.dreamdisplayx
  (version still `1.9.2-dev` — needs bump), fabric.mod.json id/name renamed, native Cargo.toml/Cargo.lock renamed
  (dreamdisplayx-native / dreamdisplayx-lav / dreamdisplayx-logging / dreamdisplayx_native / dreamdisplayx_lav),
  UpdateCheck.kt points at Aruvelut-123/dreamdisplaysx, workflows jar globs `dreamdisplayx-*.jar`,
  native matrix lib names `libdreamdisplayx_native.so` etc.
- NOTE: `git status` shows ~721 changes (D old paths + new files). Pre-existing uncommitted changes
  (before this session) were: `.github/workflows/_build.yml`, `.github/workflows/release.yml`, `README.md`
  (they added Android matrix + trimmed Modrinth publishing + README fork text). They are mixed into the same tree.

### 2. Token usage log
- Created `D:\dreamdisplayx-token-usage.txt` (append per step; steps 0-1 logged).

### 3. (2026-08-19 continuation session — DONE)
- Version bumped to `1.9.1.1`; CHANGELOG.md got a `# 1.9.1.1 Release` header + `Based on Dream Displays 1.9.1.` + fork notes.
- Workflows fixed: `required_native_plats` -> `required_native_platforms` typo; Android FFmpeg now
  compiled from source (8.1.2, same series as desktop BtbN 8.1.x) per ABI with NDK r26d, incl. license text;
  Android matrix `ffmpeg_arch` corrected (aarch64/arm/x86/x86_64). GitHub-only publishing confirmed
  (only GITHUB_TOKEN, no MODRINTH_TOKEN / PAT). crowdin.yml + `.github/workflows/crowdin.yml` deleted;
  `zh_cn.json` (97 keys) added; server zh.json NOT added (optional, client is priority).
- #188: bilibili bangumi/movie URLs — `MediaSource.Bilibili` gained `epId`/`seasonId`;
  `BilibiliUrls` parses `/bangumi/play/ep<id>` and `/ss<id>`; `BilibiliApi.resolveBangumi`
  uses `pgc/view/web/season` + `pgc/player/web/playurl`; cache keys `ep:`/`ss:`. Tests pass.
- #190 (volume): **investigated, verdict: server path is correct.** `ServerConfigModelTest`
  proves `default_volume=30` -> wire `0.15f`; `RoundTripTest` proves `ServerHello.defaultVolume`
  round-trips; all three v2 platforms send it. The only "gap" is the frozen v1 protocol, which by
  design never sends defaultVolume (old peers don't know the field) — NOT fixable/not a bug in v2.
  → fix skipped per user instruction ("if that issue is invalid... can skip that fix").
- Screen sharing (client): `OsInfo` gained `isAndroid` + `linuxSessionType`; new
  `platform/client/common/.../screenshare/{ScreenShare,ScreenShareManager,ScreenShareCommand}.kt`;
  `/share <rtmp-url>` + `/share stop` client commands on Fabric + NeoForge. Backends: gdigrab
  (Windows), avfoundation (macOS), x11grab (Linux X11 + Wayland-via-XWayland); Android hard-disabled.
- #120 ingest: `MediaSource.Ingest` (`rtmp://`/`rtmps://`/`srt://`); `CustomMediaUrls.isIngest`;
  `MediaUrlPolicy` + `CustomMediaPolicy` accept ingest hosts; `IngestResolver` (live, non-seekable);
  registered in `DefaultMediaResolverProvider`. `/display video rtmp://...` now works.
- Singleplayer: verified — Fabric `ModInitializer`/NeoForge `ServerStartedEvent` run on integrated
  servers, client uses serverId `"singleplayer"` on both loaders. No gaps found.
- Workflow YAML validated (python yaml.safe_load, all three OK).
- Local test jars built (1.21.11): `build/libs/dreamdisplayx-fabric-1.21.11-1.9.1.1.jar` and
  `dreamdisplayx-neoforge-1.21.11-1.9.1.1.jar` (no native libs — cargo absent locally).

---

## PENDING TASKS (in order)

### 3. Version + changelog
- `gradle.properties`: `version=1.9.2-dev` -> `1.9.1.1` (user asked "modify the version number to 1.9.1.1").
- `CHANGELOG.md`: add a line at the very top stating what version of Dream Displays this is based on
  (last upstream entry is `# 1.9.1 Release`; fork is based on Dream Displays 1.9.1). Suggested format:
  `# 1.9.1.1 Release` header + note line `Based on Dream Displays 1.9.1.` then fork notes.
- NOTE release.yml changelog extraction matches `^# {pretty_version}`; pretty_version for 1.9.1.1 = "1.9.1.1 Release".

### 4. Workflow: android libraries + GitHub-only publishing + no PAT
- `_build.yml` currently has (pre-existing, uncommitted) `build-android-natives` job + android matrix.
  **BUG: Android FFmpeg prebuilt URLs are DEAD (404)** — checked via GitHub API:
  - `https://github.com/wang-bin/ffmpeg-android-build/...` -> repo does NOT exist (404).
  - Verified alternatives: `hzw1199/Android-FFmpeg-Prebuilt` (has ffmpeg-8.1.1 dir, but only merged
    `libffmpeg.so` + include/ — NO per-component libav*.so, NO pkgconfig; not usable by ffmpeg-next),
    `StarHosea/ffmpeg-prebuilt-android` (no releases), `arthenica/ffmpeg-kit` (releases have 0 assets),
    `Khang-NT/ffmpeg-binary-android` (2018, FFmpeg 4.x era, too old).
  - **Fix direction:** build FFmpeg from source per ABI in the workflow using the NDK (ffmpeg-android-maker
    recipe) OR keep matrix but generate a pkgconfig/av components layout. lav crate needs
    ffmpeg-next 8.1.0 with features codec/format/software-scaling/software-resampling (libavcodec, libavformat,
    libavutil, libswscale, libswresample). Recommend: add a workflow step that downloads FFmpeg 8.1.x source
    (`https://ffmpeg.org/releases/ffmpeg-8.1.1.tar.xz`), configures per ABI with NDK clang
    (`--target-os=android --arch=... --enable-shared --disable-static --disable-programs --disable-doc
    --enable-small --prefix=$PREFIX`), `make -j install`, then sets PKG_CONFIG_PATH to `$PREFIX/lib/pkgconfig`.
    Cargo-ndk + `--target aarch64-linux-android` etc. Keep android matrix (arm64-v8a / armeabi-v7a / x86 / x86_64).
  - **BUG in `_build.yml` line ~1025:** `REQUIRED_NATIVE_PLATFORMS: ${{ needs.metadata.outputs.required_native_plats }}`
    — typo `required_native_plats` -> `required_native_platforms` (would break "Verify jar native bundle" step).
  - "contains android libraries": jniLibs are extracted to `native/build/ci-bundle/jniLibs` but the jar bundling
    convention only packages desktop `dreamdisplays-natives` (now `dreamdisplayx-natives`). Decide: either also
    bundle android jniLibs into the jar under `dreamdisplayx-natives/android-*`, or leave as separate artifacts
    (they ARE uploaded as jniLibs-* artifacts). Probably leave separate + attach to GitHub release.
  - Publishing: release.yml already GitHub-only in working tree (Modrinth steps removed). Verify no
    `MODRINTH_TOKEN` remains (grep `secrets.` — only GITHUB_TOKEN + CROWDIN_* left; crowdin workflow is being removed).
    No personal PAT used for release upload: uses `secrets.GITHUB_TOKEN` (built-in) — already satisfies the request.
  - Rename leftovers to check in workflows: `dreamdisplays-natives` bundle dir references, `-A "dreamdisplays-ci"` UA string, `DREAMDISPLAYS_REQUIRE_*` env names in `_build.yml` vs gradle convention reading `DREAMDISPLAYS_REQUIRE_NATIVES` etc. (convention renamed to DREAMDISPLAYX_*? verify gradle/src/main/kotlin/conventions/dreamdisplayx.native-resources.gradle.kts).

### 5. Remove crowdin workflow + add zh_cn lang
- Delete `.github/workflows/crowdin.yml` and `crowdin.yml` (user: "remove crowdin workflow and add a built-in simplified chinese language file").
- Add `platform/resources/src/main/resources/assets/dreamdisplayx/lang/client/zh_cn.json` — full translation of en_us.json
  (keys `dreamdisplayx.*`, ~97 keys). Optionally also server `zh.json` (server lang uses two-letter codes) — client file is the priority.
- Check `.github/ISSUE_TEMPLATE`, README for crowdin/Discord badge leftovers (README already removed Crowdin+Discord badges in pre-existing change).

### 6. Features from GitHub issues (all fetched — contents known):
- **#188 (feature):** Bilibili `bangumi/play/...` (ep/ss/movie) URL support.
  Current: `BilibiliUrls.parse` only handles `/video/BV..`, `/video/av..`, `live.bilibili.com`, `b23.tv`.
  `MediaSource.Bilibili` has bvid/avid/part/roomId. Add `epId`/`seasonId` handling + pgc API calls in `BilibiliApi`
  (`https://api.bilibili.com/pgc/view/web/season?ep_id=..` or `?season_id=..`, playurl via pgc endpoint with `ep_id`/`cid`).
  Also "update the built-in FFmpeg download repository to include support for downloading Android FFmpeg binaries" — that's task 4.
- **#120 (feature):** Screen casting ("Screen Sharing like on Platforms like Discord but for Minecraft with a improved encoder").
  Plan: add a `/display cast <url>`-style live-ingest path OR an RTMP/SRT ingest source. Realistic scope:
  support `rtmp://` / `rtmps://` / `srt://` ingest URLs as a `DirectStream`-like source so a player can push their
  screen (OBS-style) into a display. Requires: `MediaHttpUrl`/URL policy currently http(s)-only; `CustomMediaUrls.classify`
  is extension-based. Add a new `MediaSource.Ingest` (or extend DirectStream) + resolver that builds a live non-seekable
  stream; bypass SSRF guard only for explicit rtmp/srt; add command alias `cast` in platform command registrars.
  Keep scope tight and compilable.
- **#190 (fix):** "Server config default volume not applied to new displays" — volume stays 50%.
  Root cause candidate: client `ClientSettingsStore.getSettings(uuid, defaultVolume())` uses `computeIfAbsent` keyed by
  display UUID, so a NEW display gets server default from `ClientPacketManager.serverSnapshot.defaultVolume`, BUT
  `ServerHello.defaultVolume` default is `-1f` and legacy (v1) protocol path never sends it -> client falls back to
  `ClientDisplaySettings.DEFAULT_VOLUME = 0.25f` (50%). Check v1 legacy handshake: does it send defaultVolume?
  `V2Paper/V2Fabric/V2NeoForge` set `defaultVolume = config.settings.defaultVolume` (default 50/200f=0.25). Investigate
  whether `ServerHello` is only sent to v2 peers and whether legacy peers get 0.25 always. Also confirm config.toml
  `display.default_volume` read path (`ServerConfigModel` line ~263) works after restart. Fix = make default volume
  flow reach all peers / apply server default when creating new displays.

### 7. Login method + encrypted credential storage (user's own request, after issues)
- "add the login method that allowed to login into corresponding platforms to get vip content or higher quality
  and store credentials to server encrypted only".
- Plan: server-side credential store (encrypted at rest — AES-GCM with key from config/generated key file),
  commands `/display login <platform> <token>` / `/display logout <platform>` (server), credentials handed to
  authenticated client sessions (or used server-side for resolution). Platforms: YouTube cookies (yt-dlp),
  Bilibili SESSDATA, Twitch OAuth. Resolvers currently run client-side (media/source is client-bundled) —
  decide architecture: simplest = server stores encrypted; client requests token via existing v2 packet channel;
  add `PlatformCredentials` to handshake or a new packet type. Keep compilable; document.

### 8. Singleplayer support (user's latest request: "make the mod works under singleplayer too")
- Client.kt already handles `isLocalServer` / `hasSingleplayerServer()` (serverId "singleplayer") in fabric+neoforge.
- Investigate whether integrated server runs `platform:server` bootstrap (VanillaBootstrap/NeoForgeServerMod covers
  "dedicated + integrated servers alike" per comment). Likely missing: display creation command UX in singleplayer,
  or permission checks, or the client resolving URLs without a network server. Test locally (gradle run) and fix gaps:
  e.g. ensure `PaperServer`/`VanillaServerState` bootstrap runs on integrated server, no reliance on external proxy,
  bstats/update check offline tolerance. Add/verify a `runSingleplayer` dev flow.

### 9. Build verification (user provided proxy: `http://127.0.0.1:7897`, no auth)
- Java 25 present; cargo/rustc NOT installed locally (native builds must be validated by CI or via rustup install).
- Run JVM-side Gradle build with proxy: `$env:HTTPS_PROXY='http://127.0.0.1:7897'; $env:HTTP_PROXY=...`
  then `./gradlew :api:compileKotlin :core:compileKotlin :util:compileKotlin :platform:server:compileKotlin` etc.
  (or a full `:platform:client:fabric:build`). Native resources convention skips natives when
  `DREAMDISPLAYS_REQUIRE_NATIVES` unset and cargo unavailable.
- Validate workflow YAML (e.g., python yaml.safe_load or actionlint if available).

### 10. Commit + push
- Commit each step with conventional commits (e.g.):
  1. `refactor: rename mod/plugin from dreamdisplays to dreamdisplayx` (the rename tree)
  2. `feat: add built-in simplified chinese translations` + `chore: remove crowdin workflow`
  3. `chore: bump version to 1.9.1.1 and note base version in changelog`
  4. `fix(workflow): build android ffmpeg from source, fix native-platform output typo, publish to github only`
  5. `feat(bilibili): support bangumi/play/movie urls (#188)`
  6. `feat: add screen casting via rtmp/srt ingest (#120)`
  7. `fix: apply server default volume to new displays (#190)`
  8. `feat: add platform login with server-side encrypted credentials`
  9. `feat: support singleplayer`
  Then `git push origin main`.
- Note: approval policy is "never" — no sandbox escalations; file policy is danger-full-access (pwsh can write anywhere).

---

## Key file map (post-rename)
- settings.gradle.kts (rootProject.name dreamdisplayx, stonecutter versions 1.21.1/1.21.11/26.1.2/26.2)
- gradle.properties (version=1.9.2-dev -> 1.9.1.1, group=com.dreamdisplayx)
- versions/active.txt = 1.21.11
- Gradle conventions: gradle/src/main/kotlin/conventions/dreamdisplayx.*.gradle.kts
- Client: platform/client/{fabric,neoforge,common}
- Server: platform/server (Paper + vanilla bootstrap for fabric/neoforge)
- Proxy: platform/proxy/{velocity,bungeecord}
- Native: native/ (crates dreamdisplayx-native, dreamdisplayx-lav, dreamdisplayx-logging)
- Lang: platform/resources/src/main/resources/assets/dreamdisplayx/lang/{client,server}
- Workflows: .github/workflows/{ci.yml, release.yml, _build.yml, crowdin.yml(delete)}
- Token log: D:\dreamdisplayx-token-usage.txt
