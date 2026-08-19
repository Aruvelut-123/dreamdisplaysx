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
- Task 7 (login, committed `7431b09` 2026-08-10): Bilibili-only platform login.
  Server: `credentials/CredentialStore.kt` (AES-256-GCM, key file `credentials.key` + encrypted
  `credentials.json`), `CredentialActions.kt` (login/logout/snapshotFor), `/display login bilibili
  <sessdata>` / `/display logout bilibili` (Paper + Vanilla registrars), `PlatformCredentials` v2
  packet (id 27) pushed on hello + login/logout from V2Paper/V2Fabric/V2NeoForge.
  Client: `login/BilibiliLoginManager.kt` + `login/PlatformLoginScreen.kt` (`/dlogin` command,
  Fabric + NeoForge) — QR-code login (scan with mobile app, poll every 2s) and phone+password
  (RSA-encrypted via passport web key; CAPTCHA failures fall back to QR), ZXing QR rendering;
  `BilibiliApi.sessdata` cookie used in playback requests. Compile-verified on 1.21.11
  (core/media:source/client common/fabric/neoforge/server all green).

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

### 7. Login method + encrypted credential storage — DONE (committed 7431b09; Bilibili only)
- "add the login method that allowed to login into corresponding platforms to get vip content or higher quality
  and store credentials to server encrypted only".
- Implemented (Bilibili SESSDATA only, per user "only implment bilibili login for now"):
  server-side encrypted credential store (AES-256-GCM, generated key file), `/display login bilibili
  <sessdata>` / `/display logout bilibili` (Paper + Vanilla), `PlatformCredentials` v2 packet pushed
  to the player's client on hello/login/logout, client `/dlogin` UI with QR + phone/password flows,
  SESSDATA cookie wired into `BilibiliApi` playback requests. Twitch/YouTube left for later.

### 8. Singleplayer support — DONE
- Verified: Fabric `ModInitializer`/NeoForge `ServerStartedEvent` run on integrated servers; client uses
  serverId `"singleplayer"` on both loaders; `VanillaBootstrap` covers dedicated + integrated servers alike.
  No gaps found (see DONE section above).

### 9. Build verification — DONE
- Local JVM compile verified on 1.21.11 (api/core/util/media*/platform server/client fabric/neoforge all green).
- 26.x (26.1.2/26.2) compat verified (`b6612d6`); 1.21.1 local build blocked by page-file JVM crash in
  neoform mergeMappings (environment, not code) — CI `build-jars-1.21.1` artifact is the source for the
  user's NeoForge test jar. Workflow YAML validated.

### 10. Commit + push — DONE
- All commits pushed to `origin/main` (no GPG signing, message-file style): `1707b31` rename,
  `6f248ef` bilibili bangumi + screen share + ingest, `0a5b1d1` `b1b4bdf` `6ecc87d` `8c1d674` CI fixes,
  `4b716df` mod-protocol screen sharing, `b6612d6` 26.x compat, `69e4324` platform-jars artifact,
  `cabcead` neoforge bus fix, `de438fd` gh-proxy FFmpeg + authors, `7431b09` bilibili login,
  `c672728` docs task-7 done. Only remaining: user downloads `build-jars-1.21.1` artifact from CI
  and tests `dreamdisplayx-neoforge-1.21.1-1.9.1.1.jar` locally.

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
