[![Latest release](https://img.shields.io/github/release/Aruvelut-123/dreamdisplaysx.svg)](https://github.com/Aruvelut-123/dreamdisplaysx/releases/latest)
[![License](https://img.shields.io/github/license/Aruvelut-123/dreamdisplaysx)](https://github.com/Aruvelut-123/dreamdisplaysx/blob/main/LICENSE)

<div align="center">
  <img src="https://raw.githubusercontent.com/Aruvelut-123/dreamdisplaysx/main/platform/resources/src/main/resources/assets/dreamdisplayx/icon.png" alt="Dream DisplaysX" width="180">
</div>

# Bring real video playback to Minecraft

Watch videos, livestreams, and more directly on in-game displays — together with your friends.

Create a display, paste a link and that's it!

[中文 README](README.md) · [Repository](https://github.com/Aruvelut-123/dreamdisplaysx)

![Player watching on displays](https://i.imgur.com/JoARVeu.png)

Dream DisplaysX is a fork of [Dream Displays](https://github.com/arnodoelinger/dreamdisplays). Report problems with
this fork [here](https://github.com/Aruvelut-123/dreamdisplaysx/issues), not upstream.

# Watch anything

| Source | What works |
|---|---|
| **Twitch** | Live channels, VODs, and clips |
| **Kick** | Live channels and VODs |
| **Vimeo** | Public videos and live events |
| **Bilibili** | Videos, live channels, and bangumi episodes/seasons/movies |
| **Any video link** | Direct video files and live streams (`.m3u8` / `.mpd`) |
| **Share links** | Google Drive, Dropbox, and Imgur links are rewritten to the file they point at |
| **Pretty much anywhere else** | Not on the list? Try it anyway |

# Get started

Build a display out of black concrete, select it with a diamond axe and type `/display create`. Then look at it and
run `/display video <link> [language]`. Shift-right-click opens the display menu.

| Command | Side | What it does |
|---|---|---|
| `/display create` / `delete` | Server | Create / remove a display |
| `/display video <link> [language]` | Server | Play a video, stream, or ingest URL |
| `/display list` / `info` | Server | List / inspect displays |
| `/display on` / `off` | Server | Enable / disable every display |
| `/display login bilibili <sessdata>` | Server | Store the global Bilibili credential (OP only) |
| `/display logout bilibili` | Server | Delete the global Bilibili credential (OP only) |
| `/dlogin` / `/dlogoff` | Client | Open the Bilibili QR login screen / log out (OP only) |

`/dlogin` opens a QR code to scan with the Bilibili mobile app. The resulting `SESSDATA` is sent to the server,
stored AES-256-GCM encrypted, then synced across the server network and to online clients.

# Supported versions

Built for the following Minecraft versions (see the release badge above for the current version):

| Minecraft | Fabric | Forge | NeoForge | Paper |
|---|---|---|---|---|
| 1.18.2 | ✅ | ✅ | — | — |
| 1.19.4 | ✅ | ✅ | — | — |
| 1.20.1 | ✅ | ✅ | — | — |
| 1.21.1 | ✅ | — | ✅ | ✅ |
| 1.21.11 | ✅ | — | ✅ | ✅ |
| 26.1.2 | ✅ | — | ✅ | ✅ |
| 26.2 | ✅ | — | ✅ | ✅ |
| 26.3 | ✅ | — | ✅ | ✅ |

The Paper plugin is cross-version: one jar covers 1.21.1 – 26.3. Velocity and BungeeCord proxies are
version-independent as well.

# Download

Grab the `.jar` for your loader and Minecraft version from the
[latest release](https://github.com/Aruvelut-123/dreamdisplaysx/releases/latest):

- `dreamdisplayx-fabric-<mc>-<version>.jar` — Fabric / Quilt client or server mod
- `dreamdisplayx-forge-<mc>-<version>.jar` — Forge client or server mod (1.18.2 – 1.20.1)
- `dreamdisplayx-neoforge-<mc>-<version>.jar` — NeoForge client or server mod (1.21.1+)
- `dreamdisplayx-paper-<version>.jar` — Paper plugin
- `dreamdisplayx-velocity-<version>.jar` / `dreamdisplayx-bungeecord-<version>.jar` — proxy plugins

On the **client**, drop the jar into your mods folder. On the **server**, install the matching mod or plugin.

# Features

- **Seamless multiplayer playback** — local, synced, and broadcast modes; V3 batches packets and falls back to V2
- **Adjustable resolutions** up to 4K, with per-viewer audio and subtitle renditions (bilingual subtitles, HLS WebVTT)
- **Conforming displays** — stairs, slabs and mixed shapes share one fitted mesh for video, subtitles and danmaku
- **Per-display playlists** — database-backed queue (SQLite/MySQL) with approval, skip and end-of-queue policies
- **Permissions and claim protection** — LuckPerms, WorldGuard, and optional claim plugins
- **Video-derived dynamic lighting** (LambDynamicLights) and a Complementary shader patcher
- **Moving structures** — displays ride along with a Valkyrien Skies ship or a Sable sub-level
- **Experimental ReplayMod / Flashback compatibility**

# What's new in this fork

- **Bilibili account login** — QR login (`/dlogin`), AES-256-GCM `SESSDATA` storage, global cross-server sync,
  VIP badge, search, Simplified Chinese, and danmaku
- **RTMP / RTMPS / SRT ingest** — feed an OBS-style stream into a display
- **libvlc playback engine** — JNA binding with decoupled video and audio clocks
- **Every platform** — Fabric, Forge, NeoForge, Paper, Velocity and BungeeCord builds

# Moving structures (Valkyrien Skies / Sable)

Displays can be anchored to a structure that moves and ride along with it, translation and rotation
included — no need to recreate them.

| Physics mod | Minecraft versions | Status |
|---|---|---|
| [Valkyrien Skies](https://modrinth.com/mod/valkyrien-skies) | 1.18.2 – 1.20.1 | Supported |
| [Sable](https://modrinth.com/mod/sable) | 1.21.1 | **Experimental** |

Things worth knowing:

- Install the same physics mod on **both the client and the server**. With only one side installed a display
  simply behaves as an ordinary fixed display.
- Both mods are reached by reflection, so there is no hard dependency and a missing mod is skipped silently.
- Create the display while it is still a normal block build with `/display create`, then assemble the structure.
- Sable describes itself as an "extremely intrusive mod" and has no official Valkyrien Skies compatibility;
  running both at once is not supported.
- If a display does not follow its structure, search `latest.log` for `Moving-structure support` or
  `Structure anchor discovery`.

# Android (PojavLauncher / FCL / Zalith)

- Runs on **ARM64** and **x86_64** launchers; audio uses libvlc's OpenSL ES output
- Decode defaults to software `avcodec`; opt into a launcher MediaCodec bridge with `-Ddreamdisplayx.hwDecode=<module>`
- MobileGlues bounds the decoder thread pool and bypasses the PBO upload path that can produce black frames
- Subtitle and danmaku textures load an Android system CJK font when Cacio/AWT is available and degrade safely otherwise

# Building from source

```bash
git clone https://github.com/Aruvelut-123/dreamdisplaysx.git
cd dreamdisplaysx
./gradlew :platform:client:fabric:build :platform:client:neoforge:build
```

Multi-version builds use [Stonecutter](https://github.com/kikugie/stonecutter); version properties live in
`versions.json` and the active one in `versions/active.txt`. libvlc and SQLite natives are produced by CI and
downloaded on first boot into `./dreamdisplayx/natives/<os>/<arch>/`, never bundled into the jar.

# Disclaimer

Dream DisplaysX is not affiliated with the original Dream Displays project or Mojang Studios.

# Credits

- **[Dream Displays](https://github.com/arnodoelinger/dreamdisplays)** — the upstream project this fork is based on
- **[VideoPlayer-Library](https://github.com/squi2rel/VideoPlayer-Library)** — reference for the libvlc native build and packaging