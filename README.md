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

## Protocol compatibility

Clients negotiate the batch-capable **V3** envelope (`dreamdisplayx:v3`) when available and fall back to the compatible **V2** envelope (`dreamdisplayx:v2`) otherwise. V3 same-content snapshots bind multiple displays to one URL and playback timeline. Legacy **V1** traffic is detected and the affected player is notified in chat, but V1 packets are not processed.

V3, `/display group`, the Paper remote-control stick, and the Flashback / ReplayMod bridges are experimental and may change.

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
- **Video-derived dynamic lighting** — display frames light the world when LambDynamicLights is installed
- **Complementary shader patcher** — patches Complementary r5.8.1 packs into disposable copies, leaving other packs untouched
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
- **Experimental ReplayMod & Flashback compatibility** — replay rendering can freeze display media and follow the replay timeline, with pause / seek / video-change / GUI actions using replay markers. Flashback support is optional and reflection-based (works through Sinytra Connector on NeoForge). A global display-audio multiplier lives in Minecraft's Sound Options; display audio is not yet captured into replay exports.
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
- **Bilibili danmaku** — viewer-local scrolling comments over displays, with per-display toggle and global speed/density/filter settings.
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

- Runs on **ARM64** and **x86_64** launchers (PojavLauncher / FCL / Zalith).
- Audio uses libvlc's OpenSL ES output; desktop 3D positional audio (`javax.sound`) is unavailable. DASH streams (e.g. Bilibili) play through a separate audio-only libvlc player kept in sync by A/V auto-resync.
- Video decode uses MediaCodec; override with `-Ddreamdisplayx.hwDecode=<module>` or disable with an empty value, same as desktop.
- Android libvlc players are paused (never stopped or released) on teardown to avoid native `SIGSEGV` crashes; stale native files are cleaned at startup.
- Bundles Android SQLite and a uniquely-named `libc++`; all `.so` files load from executable app-internal storage (emulated storage is `noexec`).
- Ships a safe libvlc JNI bridge and an `android.os.Environment` stub; `libvlcjni.so` and Android-incompatible libvlc options are never used.

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

![Display](https://i.imgur.com/yyIKdp8.png)

## Building from source

```bash
git clone https://github.com/Aruvelut-123/dreamdisplaysx.git
cd dreamdisplaysx
./gradlew :platform:client:fabric:build :platform:client:neoforge:build
```

The project uses [Stonecutter](https://github.com/kikugie/stonecutter) for multi-version builds (version properties in
`versions.json`, active version in `versions/active.txt`). The libvlc + SQLite natives are built by the CI
"Build Natives" workflow from official VideoLAN distributions and **downloaded at runtime** on first boot into
`./dreamdisplayx/natives/<os>/<arch>/` (never bundled, keeping the jar small).

## Disclaimer

Dream DisplaysX is not affiliated with original Dream Display nor Mojang Studio.

## Credits

- **[Dream Displays](https://github.com/arnodoelinger/dreamdisplays)** — the original upstream project that this fork is based on.
- **[VideoPlayer-Library](https://github.com/squi2rel/VideoPlayer-Library)** — reference for libvlc native build and packaging (used during CI workflow development).
