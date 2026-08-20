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

## Danmaku System

### Architecture
- `DanmakuManager` subscribes to live (room_id) and VOD (cid) danmaku
- `DanmakuOverlay` renders via AWT `BufferedImage` → `NativeImage`/`DynamicTexture` → textured quad
- Settings read live from `ClientDisplaySettings` via `settings()` lambda

### Key Files
| File | Purpose |
|------|---------|
| `danmaku/DanmakuOverlay.kt` | Rendering, track management, font caching |
| `danmaku/DanmakuManager.kt` | Subscription, message queue |
| `danmaku/BilibiliDanmakuClient.kt` | WebSocket live client |
| `ui/DisplayMenu.kt` | Danmaku settings UI rows |
| `ui/widgets/DanmakuAreaSlider.kt` | Display area slider with visual indicator |
| `ui/widgets/DanmakuFilterBar.kt` | Scroll/Top/Bottom/Color toggle buttons |

### Danmaku Settings (3-Layer Model)
1. **Global toggle** — `config.yml` `danmaku-enabled` (client-side)
2. **Per-display overrides** — `ClientDisplaySettings` danmaku fields (JSON, per-display)
3. **Server defaults** — (future) server config for opacity/fontsize/speed/area/filter defaults

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

## CI/CD & Versioning Rules

- `_build.yml` — Main build workflow (multi-version)
- `release.yml` — Release pipeline (dev/preview/release)

### Version Format
- **Version in `gradle.properties`** always has `-dev` suffix (e.g. `1.9.2.1-dev`), matching upstream convention
- CI workflow auto-detects version type from the version string:
  - `1.9.2.1-dev` → **Developer** (pre-release)
  - `1.9.2.1-preview.1` → **Preview 1** (pre-release)
  - `1.9.2.1` → **Release** (full release)
- `pretty_version` output: `1.9.2.1 Developer` / `1.9.2.1 Preview 1` / `1.9.2.1 Release`
- GitHub Release tag: `v1.9.2.1` (no `-dev` suffix in tag)

### CHANGELOG Rules
- **Title** must include "Release" suffix: `# 1.9.2.1 Release` (even for dev/preview entries)
- Never split a version into multiple sections — all entries for one version go under one `#`
- When based on an upstream version, upstream's original changelog goes in a **separate section below**:
  ```
  # 1.9.2.1 Release
  ...our fork's changes...

  # 1.9.2 Release
  ...upstream's original changelog...
  ```
- Never merge upstream changelog into our version's section
- The CI release pipeline extracts the matching section from CHANGELOG.md by the `pretty_version` title

## Known Issues
1. Danmaku track spacing doesn't scale with font size (hardcoded trackCount=8, 26px)
2. SettingsSection scissor clipping works but controls are placed every frame