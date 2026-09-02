[![Latest release](https://img.shields.io/github/release/Aruvelut-123/dreamdisplaysx.svg)](https://github.com/Aruvelut-123/dreamdisplaysx/releases/latest)
[![License](https://img.shields.io/github/license/Aruvelut-123/dreamdisplaysx)](https://github.com/Aruvelut-123/dreamdisplaysx/blob/main/LICENSE)

<div align="center">
  <img src="https://i.imgur.com/HM4JUdj.png" alt="Dream DisplaysX">
</div>

# Bring real video playback to Minecraft

Watch videos, livestreams, and more directly on in-game displays — together with your friends.

Create a display, paste a link and that's it!

![Player watching on displays](https://i.imgur.com/JoARVeu.png)

Dream DisplaysX is a fork of [Dream Displays](https://github.com/arnodoelinger/dreamdisplays). If you encounter any
error on this version, **do not** submit issues to the original repository — open an issue
[here](https://github.com/Aruvelut-123/dreamdisplaysx/issues) instead. Thanks!

# Watch anything

![Display menu](https://i.imgur.com/wGnDzrT.png)

|                                                                                                                                                                                                                                                                           | Source                        | What works                                                                     |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------|--------------------------------------------------------------------------------|
| <img src="https://cdn.simpleicons.org/twitch" width="32" height="32" alt="Twitch">                                                                                                                                                                                        | **Twitch**                    | Live channels, VODs, and clips                                                 |
| <img src="https://cdn.simpleicons.org/kick" width="32" height="32" alt="Kick">                                                                                                                                                                                            | **Kick**                      | Live channels and VODs                                                         |
| <img src="https://cdn.simpleicons.org/vimeo" width="32" height="32" alt="Vimeo">                                                                                                                                                                                          | **Vimeo**                     | Public videos and live events                                                  |
| <img src="https://cdn.simpleicons.org/bilibili" width="32" height="32" alt="Bilibili">                                                                                                                                                                                    | **Bilibili**                  | Videos, live channels, and bangumi episodes/seasons/movies                     |
| <img src="https://cdn.simpleicons.org/ffmpeg" width="32" height="32" alt="Video file">                                                                                                                                                                                    | **Any video link**            | Direct video files and live streams (`.m3u8` / `.mpd`) just work               |
| <img src="https://cdn.simpleicons.org/googledrive" width="32" height="32" alt="Google Drive"><br><img src="https://cdn.simpleicons.org/dropbox" width="32" height="32" alt="Dropbox"><br><img src="https://cdn.simpleicons.org/imgur" width="32" height="32" alt="Imgur"> | **Share links**               | Google Drive, Dropbox, and Imgur links are rewritten to the file they point at |
| <img src="https://cdn.simpleicons.org/googlechrome" width="32" height="32" alt="Web">                                                                                                                                                                                     | **Pretty much anywhere else** | Not on the list? Try it anyway — chances are it'll play                        |

# Built for multiplayer

Create a display, paste a link with `/display video <link>` — Dream DisplaysX figures out the rest.

Watch together with your friends with seamless multiplayer synchronization. Choose between local, synchronized, and
broadcast playback depending on how you want your displays to behave.

Dream DisplaysX keeps playback synchronized across the server while keeping network usage extremely low.

![Cinema](https://i.imgur.com/PKxe0oG.png)

# Made for you

Dream DisplaysX is built to make watching videos in Minecraft feel as natural as possible.

<table>
<tr>
<td valign="top" width="50%" align="center">

## Your experience

<div align="left">

- **Seamless multiplayer playback** — local, synced, and broadcast modes
- **Powerful media player** — search, Picture-in-Picture & more
- **Immersive audio** — 3D sound, volume up to 200% & more
- **Adjustable resolutions** — from 144p up to 4K
- **Hardware-accelerated playback** — libvlc hardware decode (d3d11va / vaapi / videotoolbox)
- **Customizable displays** — size, brightness, stretch mode & orientation

</div>

</td>
<td valign="top" width="50%" align="center">

## Your server

<div align="left">

- **Broad server support** — Paper, Fabric, NeoForge, Velocity, BungeeCord
- **Fullscreen mode** — great for events and presentations
- **Simple config** — precise control over displays and playback
- **Permissions** — fine-grained control with LuckPerms support
- **Claim protection** — display creation respects WorldGuard and optional claim plugins (GriefPrevention, Residence, Lands, Towny)
- **ReplayMod compatibility** — during replay rendering, local display media is frozen and its visible frame is driven by the ReplayMod millisecond playhead, so slow export FPS cannot speed the video up; replay pause holds the same frame, while pause/resume, seek, video-change, and display-GUI actions are recorded as persistent `.mcpr` markers
- **Ultra-low network impact** — minimal impact for your traffic
- **Persistent displays** — settings survive server restarts and unloading

</div>

</td>
</tr>
</table>

# What's new in this fork?

Compared to the original Dream Displays, this fork adds:

- **Bilibili account login** — QR-code login screen (`/dlogin`); `SESSDATA` is stored **encrypted** on the server
  (AES-256-GCM) and pushed back to unlock higher-quality / VIP Bilibili streams.
- **Global Bilibili login** — single account per server/network, broadcast to all players,
  OP-only with LuckPerms support and cross-server credential sync (SQLite/MySQL).
- **Bilibili VIP badge** — official VIP badge image, differentiating normal and annual VIP.
- **Bilibili built-in** — Simplified Chinese translation, Bilibili search in suggestions, unreleased-content filter.
- **RTMP / RTMPS / SRT ingest** — feed an OBS-style live stream into a display.
- **Built-in Simplified Chinese** (`zh_cn`) language file.
- **libvlc playback engine** — low-level JNA libvlc binding (no vlcj, no FFmpeg binary, no Rust, no Python).
  Video and audio run on decoupled clocks for full-framerate playback; native runtimes are downloaded automatically
  on first boot.
- Updated for **Minecraft 1.21.1, 1.21.11, 26.1.2, and 26.2**.

# Get started

Build your first display, invite your friends, and make displays a part of your world.

Set up a display using black concrete, select it with a diamond axe, and type `/display create`. After the display is
created, type `/display video <link> [language]`. Done! To customize the display, look at it and press `Shift + RMB`.

## Command reference

| Command                                          | Where      | What it does                                              |
|--------------------------------------------------|------------|-----------------------------------------------------------|
| `/display create` / `/display delete`            | Server     | Create / delete a display                                 |
| `/display video <link> [language]`               | Server     | Play a video, live stream, or ingest URL                  |
| `/display list` / `/display info`                | Server     | List / inspect displays                                   |
| `/display on` / `/display off`                   | Server     | Toggle all displays                                       |
| `/display login bilibili <sessdata>`             | Server     | Store Bilibili credential globally (OP-only)              |
| `/display logout bilibili`                       | Server     | Remove stored Bilibili credential (OP-only)               |
| `/dlogin`                                        | Client     | Open the Bilibili login screen (OP-only)                  |
| `/dlogoff`                                       | Client     | Log out of Bilibili (OP-only)                             |

> **Bilibili login tip:** run `/dlogin` in-game and scan the QR code with the Bilibili mobile app.
> On success the mod sends your `SESSDATA` to the server, which stores it encrypted, syncs it across
> the server network, and broadcasts it to all online players — everyone gets the unlocked streams.

## Download

Grab the `.jar` for your loader and Minecraft version from the
[latest release](https://github.com/Aruvelut-123/dreamdisplaysx/releases/latest):

- `dreamdisplayx-fabric-<mc>-<version>.jar` — Fabric / Quilt client or server mod
- `dreamdisplayx-neoforge-<mc>-<version>.jar` — NeoForge client or server mod
- `dreamdisplayx-paper-<version>.jar` — Paper plugin (cross-version, 1.21.1 – 26.2)
- `dreamdisplayx-velocity-<version>.jar` / `dreamdisplayx-bungeecord-<version>.jar` — proxy plugins

On the **client**, install the mod into your mods folder. On the **server**, install the matching jar (plugin or mod).
That's it — no extra dependencies required. LambDynamicLights is an optional client integration for video-derived dynamic lighting.

## Supported versions

| Minecraft | Fabric | NeoForge | Paper | Notes             |
|-----------|--------|----------|-------|-------------------|
| 1.21.1    | ✅      | ✅        | ✅     | Legacy LTS line   |
| 1.21.11   | ✅      | ✅        | ✅     | Upstream default  |
| 26.1.2    | ✅      | ✅        | ✅     |                   |
| 26.2      | ✅      | ✅        | ✅     |                   |

## Android (PojavLauncher / FCL / Zalith)

The mod runs on Android launchers on **ARM64** and **x86_64** devices, with a few platform
differences:

- **Audio** plays through libvlc's own OpenSL ES output. The desktop 3D positional audio
  (panning / occlusion / reverb) needs `javax.sound`, which Android JVMs don't ship. DASH
  streams (e.g. Bilibili's video-only m4s + separate audio m4s) get their audio from the
  separate audio-only libvlc player — created on Android without the desktop Java Sound
  callbacks, so libvlc's own `--aout=opensles` plays the audio m4s directly; volume is routed
  to both players and the existing A/V auto-resync keeps them in sync. Android libvlc players
  are **never stopped or released** by the mod: this build's `libvlc_media_player_stop` itself
  force-tears input/vout/aout so VLC worker threads detach while winding down, and their TLS
  destructor (`jni_detach_thread`) then dereferences freed state — `SIGSEGV at
  libvlc.so+0xef7418` on video switch / world exit even with players never released. Every
  teardown path instead PAUSES the players (`libvlc_media_player_set_pause`), keeping every
  VLC thread alive for the JVM lifetime; the OS reclaims them on process exit. When media is
  reloaded, the old paused players are retained and fresh players are created instead of calling
  `set_media` on a player that already carried media. Each player also gets its own video callback
  and direct-buffer pool, so delayed frames from an old player cannot use the replacement's buffers.
- **Video-derived dynamic lighting** samples the playing frame's RGB color and, when LambDynamicLights is installed, exposes a light source at the display anchor with brightness derived from that color. The sampled RGB value is retained for color-capable rendering integrations; without LambDynamicLights the normal lighting path is unchanged. Iris shader packs retain their own color-lighting pipeline: Iris does not expose a stable public API for injecting arbitrary third-party RGB light sources, so the mod never writes private shader uniforms.
- **Complementary-only shader patcher** scans every ZIP in `shaderpacks` during client startup and recognizes all Complementary Reimagined/Unbound r5.8.1 archives, creating disposable `DreamDisplaysX-*` copies with versioned manifests. It never edits `options.txt`, never forces a shader selection, and leaves BSL, Bliss, Photon, unknown packs, and original ZIPs untouched. Any failed or unsupported patch falls back to the original pack automatically.
- **Hardware decode** uses MediaCodec ( ByteBuffer copy mode feeding the same frame
  callbacks); override with `-Ddreamdisplayx.hwDecode=<module>` or disable it with an empty
  value, same as desktop.
- **SQLite persistence** is bundled for Android: the stock `sqlite-jdbc` artifact ships no
  Android native, so a Bionic build (16 KB page aligned, from xerial's `-sources` jar) is
  bundled in the mod and loaded via `org.sqlite.lib.path` / `org.sqlite.lib.name` — display
  and credential storage keep working on-device.
- **LibVLC loads by absolute path, with only libc++ preloaded**: the Android JVM reports
  `os.name=Linux-Android`, which makes JNA's generic-Linux name mapping turn `libvlc` into a
  doubled `liblibvlc.so`; the mod dlopens the exact extracted `libvlc.so` path instead. The
  Android runtime depends on `libc++` in the same directory — the Android linker does not
  search it, so the renamed libc++ is loaded first with `RTLD_GLOBAL` (`System.load`) before
  `libvlc.so` is opened. The bundled libc++ carries a **unique SONAME**
  (`libc++_dreamdisplayx.so`, with `libvlc.so`'s `DT_NEEDED` rewritten at packaging time):
  Pojav-style launchers load their own `libc++_shared.so` first, and the Android linker would
  otherwise deduplicate by SONAME and bind `libvlc.so` to that stale copy, which lacks the
  `_ZTTNSt6__ndk118basic_stringstream...` vtable symbol libvlc needs. **`libvlcjni.so` is never
  loaded** — it is the org.videolan Java-layer JNI glue, not a dependency of `libvlc.so`, and
  if it were `System.load`'ed its `JNI_OnLoad` would fail on a game JVM (it needs the Android
  framework class `android.os.Build$VERSION`) and the JVM would unload it under threads it had
  already spawned — a `SIGSEGV in __pthread_start`. Three Android safeguards: the natives
  cache is **wiped before extraction**, any stray `.so` outside the AAR-era whitelist
  (`libvlc.so` / `libc++_dreamdisplayx.so`) is **deleted at every startup** (this also
  removes `libvlcjni.so`), and the whitelist excludes it permanently. And because plain
  `dlopen` never invokes JNI entry points, the loader also calls `libvlc.so`'s exported
  `JNI_OnLoad` with the live JavaVM (obtained via `JNI_GetCreatedJavaVMs` from `libjvm.so`)
  so VLC-Android's AndroidBridge initialises and `libvlc_new` succeeds — mirroring the native
  bridge squi2rel/VideoPlayer ships, done in pure JNA. **Never pass `--plugin-path` on
  Android**: the monolithic libvlc-all AAR builds VLC with loadplugins disabled, so that option
  is compiled out entirely and `libvlc_new` returns null on the unknown option (observed:
  `vlc: unknown option or missing mandatory argument --plugin-path=...` → `libvlc_new returned
  null`). Audio output is forced with `--aout=opensles` (the module's real name — `opensl`
  does not exist), hardware decode with `--codec=mediacodec_ndk,mediacodec_jni,any`, and
  `--http-proxy` is NOT passed (this AAR compiles that option out — "option --http-proxy no
  longer exists" — and VLC-Android's http access module calls `vlc_getProxyUrl()` regardless).
  The JNI system-proxy probe is made safe instead: libvlc.so's `JNI_OnLoad` only completes
  (caching `java/lang/System.getProperty`) when the `android.os.Environment` class it resolves
  first exists, so the mod ships its own `android.os.Environment` stub — the same trick
  squi2rel/VideoPlayer uses — and pre-warms it before injecting `JNI_OnLoad`. Without this the
  cached `getProperty` refs stay NULL and `vlc_getProxyUrl` crashes the game JVM from inside
  (`SIGSEGV in libjvm.so` — observed on Pojav-style JVMs that lack the `android.*` classes).
  Desktop loading by name is unchanged.

The natives are extracted to app-internal storage automatically (the game directory on
emulated storage is mounted noexec, so `.so` files there cannot be loaded).

## JVM arguments (advanced tuning & diagnostics)

Add these to your launcher's JVM arguments (e.g. Prism: `Settings → Java → JVM arguments`). All are
optional — defaults work fine.

| Argument | Default | What it does |
|----------|---------|--------------|
| `-Ddreamdisplayx.hwDecode=<backend>` | `d3d11va` (Win) / `vaapi` (Linux) / `videotoolbox` (Mac) / `mediacodec_ndk,mediacodec_jni,any` (Android) | Hardware decode backend for libvlc. Other values: `dxva2`, `any`, empty string = disable hardware decode. |
| `-Ddreamdisplayx.audioBufferMs=<ms>` | `100` | Java Sound line buffer for audio. Larger is safer (45ms crashed historically); lower tightens lip-sync. |
| `-Ddreamdisplayx.networkCachingMs=<ms>` | `300` | libvlc `--network-caching` / `--file-caching`. Raise if streams stutter on slow networks. |
| `-Ddreamdisplayx.debugFps=true` | off | Draw the live delivered video FPS on the display-menu preview. |
| `-Ddreamdisplayx.verboseLibvlc=true` | off | Enable libvlc debug logging (shows decoder/backend selection, fallback reasons). |
| `-Ddreamdisplayx.noDropLateFrames=true` | off | Add `--no-drop-late-frames --no-skip-frames` (diagnostic; causes old/new-frame flicker). |
| `-Ddreamdisplayx.noAutoResync=true` | off | Disable the A/V drift correction entirely (bisection). |
| `-Ddreamdisplayx.silentAudio=true` | off | Never open the audio line (bisection). |
| `-Ddreamdisplayx.noAudioCallback=true` | off | Don't register libvlc audio callbacks (bisection). |
| `-Ddreamdisplayx.noVideoCallback=true` | off | Don't register libvlc video callbacks (bisection). |
| `-Ddreamdisplayx.noFrameSink=true` | off | Skip preview/popout frame sinks (bisection). |
| `-Ddreamdisplayx.noVideoPublish=true` | off | Skip the GPU surface publish — video frozen, audio only (bisection). |
| `-Ddreamdisplayx.noHardwareAccel=true` | off | Don't pass `--avcodec-hw` to libvlc at all (bisection). |

[Read more in our wiki](https://github.com/Aruvelut-123/dreamdisplaysx/wiki).

![Display](https://i.imgur.com/yyIKdp8.png)

## Building from source

```bash
git clone https://github.com/Aruvelut-123/dreamdisplaysx.git
cd dreamdisplaysx
./gradlew :platform:client:fabric:build :platform:client:neoforge:build
```

The project uses [Stonecutter](https://github.com/kikugie/stonecutter) for multi-version builds; version properties
live in `versions.json` and the active version is selected in `versions/active.txt`. The libvlc + SQLite native
runtimes are collected from official pre-built VideoLAN distributions by the CI "Build Natives" workflow
(`.github/workflows/natives.yml`) — Flathub flatpak for Linux, official VideoLAN dmg/zip for macOS and Windows
x86/x64, the MSYS2 package for Windows aarch64, and the official `libvlc-all` Maven AAR for Android ARM64/x86_64 — and
**downloaded at runtime** on first boot into `./dreamdisplayx/natives/<os>/<arch>/` (never bundled in the jar,
keeping it small).

## Disclaimer

Dream DisplaysX is not affiliated with original Dream Display nor Mojang Studio.

## Credits

- **[Dream Displays](https://github.com/arnodoelinger/dreamdisplays)** — the original upstream project that this fork is based on.
- **[VideoPlayer-Library](https://github.com/squi2rel/VideoPlayer-Library)** — reference for libvlc native build and packaging (used during CI workflow development).
