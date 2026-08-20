[![Latest release](https://img.shields.io/github/release/Aruvelut-123/dreamdisplaysx.svg)](https://github.com/Aruvelut-123/dreamdisplaysx/releases/latest)
[![License](https://img.shields.io/github/license/Aruvelut-123/dreamdisplaysx)](https://github.com/Aruvelut-123/dreamdisplaysx/blob/main/LICENSE)

<div align="center">
  <img src="https://i.imgur.com/HM4JUdj.png" alt="Dream DisplaysX"> 
  <div>
    <a href="https://github.com/Aruvelut-123/dreamdisplaysx/releases">Download from GitHub</a>
  </div>
</div>

## What is Dream DisplaysX?

**Dream DisplaysX** is a fork of [Dream Displays](https://github.com/arnodoelinger/dreamdisplays) that puts video
displays inside Minecraft — watch YouTube, Twitch, Bilibili, or any video, right on a wall in your world, together with
friends or solo.

Put a display on the wall and watch YouTube, Twitch, Bilibili, or pretty much any video, right inside Minecraft.
It works great solo, and just as well with friends: watch together in sync, or let everyone control their own screen
independently. Players just install the client-side mod, and they're ready to go.

## What's new in this fork?

Compared to the original Dream Displays, this fork adds:

- **Bilibili account login** — a client-side login screen (`/dlogin`) with **QR-code scanning**. Your `SESSDATA` is stored **encrypted** on the server (AES-256-GCM)
  and is only pushed back to *your* client, unlocking higher quality / VIP Bilibili streams.
- **Bilibili live danmaku** — live-room bullet comments (弹幕) scroll across the display while a `live.bilibili.com/<roomId>` video plays.
- **Bilibili bangumi support** — `/bangumi/play/ep<id>` and `/ss<id>` (episodes / seasons / movies) URLs work, and **Bilibili movie / bangumi search results** are merged into the suggestion grid.
- **RTMP / RTMPS / SRT ingest** — feed an OBS-style live stream into a display with `/display video rtmp://...`.
- **Android support** — Android detection is now robust (multisignal: env vars, `/system/build.prop`, path fingerprints). The FFmpeg download caches to app-internal storage. On Android 10+ where arbitrary binaries cannot be `exec`'d, the mod falls back to system `ffmpeg` (PATH / Termux) with a `-Ddreamdisplayx.ffmpeg.path` override. The CI jar bundles native libav (`dreamdisplayx_lav.so` + FFmpeg shared libs via dlopen — no exec needed).
- **Bilibili danmaku improvements** — HTML entities (`&lt;` &gt;`) are decoded in live and timed comments; danmaku unsubscribes when the video fails to start.
- **Maximum quality capped at 1080p** — the 60fps toggle and the 2160p/1440p quality tiers have been removed; the quality ladder stops at 1080p.
- **Built-in Simplified Chinese** (`zh_cn`) language file.
- **`gh-proxy.com` FFmpeg mirror** — the bundled FFmpeg downloads via a China-friendly mirror with fallback.
- Updated for **Minecraft 1.21.1, 1.21.11, 26.1.2, and 26.2**.

> If you encounter any error on this version, **do not** submit issues to the original repository — open an issue
> [here](https://github.com/Aruvelut-123/dreamdisplaysx/issues) instead. Thanks!

## Works with all popular server software

Setting it up on the server takes seconds:

- Running a plugin-based server (Paper, Spigot, Velocity, BungeeCord)? Drop the plugin `.jar` into your `/plugins` folder
- Running a mod-based server (Fabric, NeoForge)? Drop the mod `.jar` into your `/mods` folder

![Display menu](https://wsrv.nl/?url=https%3A%2F%2Fprivate-user-images.githubusercontent.com%2F74359983%2F633438991-f1ada886-0cd5-447a-8da7-99491d77c0ae.png%3Fjwt%3DeyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJnaXRodWIuY29tIiwiYXVkIjoicmF3LmdpdGh1YnVzZXJjb250ZW50LmNvbSIsImtleSI6ImtleTUiLCJleHAiOjE3ODYzNzc1MjEsIm5iZiI6MTc4NjM3NzIyMSwicGF0aCI6Ii83NDM1OTk4My82MzM0Mzg5OTEtZjFhZGE4ODYtMGNkNS00NDdhLThkYTctOTk0OTFkNzdjMGFlLnBuZz9YLUFtei1BbGdvcml0aG09QVdTNC1ITUFDLVNIQTI1NiZYLUFtei1DcmVkZW50aWFsPUFLSUFWQ09EWUxTQTUzUFFLNFpBJTJGMjAyNjA4MTAlMkZ1cy1lYXN0LTElMkZzMyUyRmF3czRfcmVxdWVzdCZYLUFtei1EYXRlPTIwMjYwODEwVDE1NTM0MVomWC1BbXotRXhwaXJlcz0zMDAmWC1BbXotU2lnbmF0dXJlPWM1ZmY1YTM3MDFlNjE0ODdmMWNjZjAzZDllN2M1ZDQzZjc0OGMwMzM0MDYyYzY3MDVkMTU4YjE3NmQ0ZjYyZTcmWC1BbXotU2lnbmVkSGVhZGVycz1ob3N0JnJlc3BvbnNlLWNvbnRlbnQtdHlwZT1pbWFnZSUyRnBuZyJ9.SgSQJl554bceqqJwZb4mtglbQ-grB4huwFMRXXypnLQ&n=-1)

## Supported versions

| Minecraft | Fabric | NeoForge | Paper | Notes |
|-----------|--------|----------|-------|-------|
| 1.21.1    | ✅      | ✅        | ✅     | Legacy LTS line |
| 1.21.11   | ✅      | ✅        | ✅     | Current default |
| 26.1.2    | ✅      | ✅        | ✅     | |
| 26.2      | ✅      | ✅        | ✅     | |

Velocity and BungeeCord proxies are also supported.

## Download

Grab the `.jar` for your loader and Minecraft version from the
[latest release](https://github.com/Aruvelut-123/dreamdisplaysx/releases/latest):

- `dreamdisplayx-fabric-<mc>-<version>.jar` — Fabric / Quilt client or server mod
- `dreamdisplayx-neoforge-<mc>-<version>.jar` — NeoForge client or server mod
- `dreamdisplayx-paper-<version>.jar` — Paper plugin (cross-version, 1.21.1 – 26.2)
- `dreamdisplayx-velocity-<version>.jar` / `dreamdisplayx-bungeecord-<version>.jar` — proxy plugins

On the **client**, install the mod into your mods folder. On the **server**, install the matching jar (plugin or mod).
That's it — no extra dependencies required.

## Features

### What you can watch

Create a display, paste a link with `/display video <link>` — Dream DisplaysX figures out the rest.

|                                                                                                                                                                                                                                                                           | Source                        | What works                                                                     |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------|--------------------------------------------------------------------------------|
| <img src="https://cdn.simpleicons.org/youtube" width="32" height="32" alt="YouTube">                                                                                                                                                                                      | **YouTube**                   | Videos and livestreams, ad-free, up to 4K, with multiple audio languages       |
| <img src="https://cdn.simpleicons.org/twitch" width="32" height="32" alt="Twitch">                                                                                                                                                                                        | **Twitch**                    | Live channels, VODs, and clips                                                 |
| <img src="https://cdn.simpleicons.org/kick" width="32" height="32" alt="Kick">                                                                                                                                                                                            | **Kick**                      | Live channels and VODs                                                         |
| <img src="https://cdn.simpleicons.org/vimeo" width="32" height="32" alt="Vimeo">                                                                                                                                                                                          | **Vimeo**                     | Public videos and live events                                                  |
| <img src="https://cdn.simpleicons.org/bilibili" width="32" height="32" alt="Bilibili">                                                                                                                                                                                    | **Bilibili**                  | Videos, live channels, and bangumi episodes/seasons/movies                     |
| <img src="https://cdn.simpleicons.org/ffmpeg" width="32" height="32" alt="Video file">                                                                                                                                                                                    | **Any video link**            | Direct video files and live streams (`.m3u8` / `.mpd`) just work               |
| <img src="https://cdn.simpleicons.org/googledrive" width="32" height="32" alt="Google Drive"><br><img src="https://cdn.simpleicons.org/dropbox" width="32" height="32" alt="Dropbox"><br><img src="https://cdn.simpleicons.org/imgur" width="32" height="32" alt="Imgur"> | **Share links**               | Google Drive, Dropbox, and Imgur links are rewritten to the file they point at |
| <img src="https://cdn.simpleicons.org/googlechrome" width="32" height="32" alt="Web">                                                                                                                                                                                     | **Pretty much anywhere else** | Not on the list? Try it anyway — chances are it'll play                        |

### Playback

- **Seamless multiplayer synchronization: local, synced, and broadcast**
- **Ad-free YouTube video playback, so you can enjoy uninterrupted viewing**
- Direct search and suggestions
- Picture-in-Picture mode
- Adjustable resolutions from 144p up to 4K
- Volume control from 0% to 200%
- Brightness control from 0% to 100%
- Multiple video languages support
- Integrated controls for play, pause, and seek
- Hardware accelerations
- And much more!

### Displays

- **Vertical display orientation support**
- Customizable display sizes in blocks
- Screens and settings remain after the server restarts or when unloaded

### Server

- **Simple and precise server-side configuration**
- **Ultra-low network impact and zero lags**
- **Fabric server support (1:1 as Paper)**
- Display commands — manage your in-game screens: create, delete, etc.
- Fine-grained permissions for admin-only control
- Full [LuckPerms](https://luckperms.net/) support

## How to use this mod?

Set up a display using black concrete, select it with a diamond axe, and type `/display create`. After the display is
created, type `/display video <link> [language]`

Done! To customize the display, look at it and press `Shift + RMB`

### Command reference

| Command                                          | Where      | What it does                                              |
|--------------------------------------------------|------------|-----------------------------------------------------------|
| `/display create` / `/display delete`            | Server     | Create / delete a display                                 |
| `/display video <link> [language]`               | Server     | Play a video, live stream, or ingest URL                  |
| `/display list` / `/display info`                | Server     | List / inspect displays                                   |
| `/display on` / `/display off`                   | Server     | Toggle all displays                                       |
| `/display login bilibili <sessdata>`             | Server     | Store your Bilibili credential (encrypted on disk)        |
| `/display logout bilibili`                       | Server     | Remove your stored Bilibili credential                    |
| `/dlogin`                                        | Client     | Open the Bilibili login screen (QR code or phone + password) |
| `/share start` / `/share stop`                   | Client     | Start / stop streaming your screen to the server relay    |

> **Bilibili login tip:** run `/dlogin` in-game and scan the QR code with the Bilibili mobile app.
> On success the mod sends your `SESSDATA` to the server, which stores it encrypted and uses it to
> unlock higher-quality streams for you. Already have a cookie? Use `/display login bilibili <sessdata>`.

[Read more in our wiki](https://github.com/Aruvelut-123/dreamdisplaysx/wiki).

![Display](https://i.imgur.com/yyIKdp8.png)

## Building from source

```bash
git clone https://github.com/Aruvelut-123/dreamdisplaysx.git
cd dreamdisplaysx
./gradlew :platform:client:fabric:build :platform:client:neoforge:build
```

The project uses [Stonecutter](https://github.com/kikugie/stonecutter) for multi-version builds; the active version is
selected in `versions/active.txt`. Native libraries (FFmpeg) are downloaded at runtime or bundled by the CI workflow.

## Support this project

If you enjoy Dream DisplaysX, consider [buying me a coffee](https://ko-fi.com/arnodoelinger). Every contribution helps
keep the project alive!

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/arnodoelinger)

## Disclaimer

Dream DisplaysX is not affiliated with original Dream Display nor Mojang Studio.
