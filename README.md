[![最新版本](https://img.shields.io/github/release/Aruvelut-123/dreamdisplaysx.svg)](https://github.com/Aruvelut-123/dreamdisplaysx/releases/latest)
[![许可证](https://img.shields.io/github/license/Aruvelut-123/dreamdisplaysx)](https://github.com/Aruvelut-123/dreamdisplaysx/blob/main/LICENSE)

<div align="center">
  <img src="https://raw.githubusercontent.com/Aruvelut-123/dreamdisplaysx/main/platform/resources/src/main/resources/assets/dreamdisplayx/icon.png" alt="Dream DisplaysX" width="180">
</div>

# 在 Minecraft 中播放视频

Dream DisplaysX 可以把视频、直播和更多媒体直接放进游戏里的显示器，与朋友一起观看。

创建显示器、粘贴链接，就可以开始播放喵~

[English README](README.en.md) · [项目主页](https://github.com/Aruvelut-123/dreamdisplaysx)

![显示器播放视频](https://i.imgur.com/JoARVeu.png)

本项目是 [Dream Displays](https://github.com/arnodoelinger/dreamdisplays) 的分支；本分支的问题请在[本仓库](https://github.com/Aruvelut-123/dreamdisplaysx/issues)提交，不要提交到上游。

# 支持的媒体来源

| 来源 | 支持内容 |
|---|---|
| **Twitch** | 直播频道、VOD 和 clips |
| **Kick** | 直播频道和 VOD |
| **Vimeo** | 公开视频和直播活动 |
| **Bilibili** | 视频、直播、番剧、剧集和电影 |
| **任意视频链接** | 直接视频文件和 `.m3u8` / `.mpd` 流 |
| **分享链接** | Google Drive、Dropbox、Imgur 链接会改写为实际文件链接 |
| **其他网站** | 不在列表中的链接也可以直接尝试播放 |

# 开始使用

用黑色混凝土搭建显示器，用钻石斧选中区域后输入 `/display create`，再注视显示器输入 `/display video <link> [language]`。按住 Shift 右键打开显示器设置菜单。

| 命令 | 位置 | 作用 |
|---|---|---|
| `/display create` / `delete` | 服务器 | 创建 / 删除显示器 |
| `/display video <link> [language]` | 服务器 | 播放视频、直播或推流地址 |
| `/display list` / `info` | 服务器 | 列出 / 查看显示器 |
| `/display on` / `off` | 服务器 | 开启 / 关闭所有显示器 |
| `/display login bilibili <sessdata>` | 服务器 | 全局保存 Bilibili 凭据（仅 OP） |
| `/display logout bilibili` | 服务器 | 删除全局 Bilibili 凭据（仅 OP） |
| `/dlogin` / `/dlogoff` | 客户端 | 打开 Bilibili 扫码登录 / 注销（仅 OP） |

执行 `/dlogin` 后用 Bilibili 手机客户端扫码；`SESSDATA` 会发送到服务器，以 AES-256-GCM 加密保存，并同步到服务器网络和在线客户端。

# 支持的版本

当前支持以下 Minecraft 版本（最新版本号见页面顶部的 release 徽章）：

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

Paper 是跨版本单插件，一个 jar 覆盖 1.21.1 – 26.3；Velocity 与 BungeeCord 代理插件同样与版本无关。

# 下载

从[最新版本](https://github.com/Aruvelut-123/dreamdisplaysx/releases/latest)下载与加载器和 Minecraft 版本对应的 jar：

- `dreamdisplayx-fabric-<mc>-<version>.jar` — Fabric / Quilt 客户端或服务器
- `dreamdisplayx-forge-<mc>-<version>.jar` — Forge 客户端或服务器（1.18.2 – 1.20.1）
- `dreamdisplayx-neoforge-<mc>-<version>.jar` — NeoForge 客户端或服务器（1.21.1+）
- `dreamdisplayx-paper-<version>.jar` — Paper 插件
- `dreamdisplayx-velocity-<version>.jar` / `dreamdisplayx-bungeecord-<version>.jar` — 代理插件

客户端把 jar 放进 `mods` 文件夹，服务器安装匹配的 mod 或插件即可。

# 功能一览

- 多人同步播放：本地、同步和广播三种模式；V3 协议支持批量数据，不可用时自动回退 V2
- 可调分辨率最高 4K，多音轨与多字幕（双语字幕、HLS WebVTT 分段）
- 曲面显示器：楼梯、台阶和混合形状表面，视频、字幕与弹幕投影到同一套贴合网格
- 数据库播放列表：SQLite / MySQL 持久化队列，支持批准、跳过和队列结束策略
- 权限与领地保护：LuckPerms、WorldGuard 以及可选领地插件
- 视频动态光照（需 LambDynamicLights）、Complementary 着色器修补
- 移动结构支持：显示器可以固定在 Valkyrien Skies 船舶或 Sable 子维度上随结构一起移动
- 实验性 ReplayMod / Flashback 兼容

# 本分支新增

- Bilibili 扫码登录、全局凭据同步与广播，附 VIP 标识、搜索和弹幕
- RTMP / RTMPS / SRT 推流输入
- libvlc 播放引擎（JNA 调用原生库，视频与音频独立时钟）
- Fabric / Forge / NeoForge / Paper / Velocity / BungeeCord 全平台构建
- 快捷键接入 Minecraft 原生「选项 → 控制」，玩家可自行改键（默认未绑定）

# 移动结构（Valkyrien Skies / Sable）

显示器可以固定在会移动的结构上，随船舶或子维度一起平移、旋转，不需要重新创建。

| 物理模组 | 支持的 Minecraft 版本 | 说明 |
|---|---|---|
| [Valkyrien Skies](https://modrinth.com/mod/valkyrien-skies) | 1.18.2 – 1.20.1 | 成熟支持 |
| [Sable](https://modrinth.com/mod/sable) | 1.21.1 | **实验性** |

使用要点：

- **客户端和服务端都要安装**同一个物理模组；只装一边时显示器会退化成普通的固定显示器
- 模组通过反射接入，不产生硬依赖，未安装时自动跳过
- 先在方块状态下用 `/display create` 创建显示器，再组装成结构
- Sable 自述为「extremely intrusive mod」，与 Valkyrien Skies 没有官方兼容支持，不建议同时安装
- 显示器已组装进结构之后如果不能跟随移动，请在 `latest.log` 中搜索 `Moving-structure support` 或 `Structure anchor discovery` 两行日志

# Android（PojavLauncher / FCL / Zalith）

- 支持 ARM64 和 x86_64 启动器，音频走 OpenSL ES
- 默认 libvlc 软件 `avcodec` 解码；可用 `-Ddreamdisplayx.hwDecode=<module>` 启用启动器的 MediaCodec 桥接
- MobileGlues 会自动限制解码线程数并绕开可能黑帧的直接 PBO 上传
- 字幕与弹幕在提供 Cacio/AWT 的启动器上加载系统 CJK 字体，缺少 AWT 时安全降级

# 从源码构建

```bash
git clone https://github.com/Aruvelut-123/dreamdisplaysx.git
cd dreamdisplaysx
./gradlew :platform:client:fabric:build :platform:client:neoforge:build
```

项目使用 [Stonecutter](https://github.com/kikugie/stonecutter) 构建多版本，版本属性在 `versions.json`，当前版本在 `versions/active.txt`。libvlc 与 SQLite 原生文件由 CI 构建，首次启动时下载到 `./dreamdisplayx/natives/<os>/<arch>/`，不打包进 jar。

# 免责声明

Dream DisplaysX 与原 Dream Displays 项目及 Mojang Studios 没有隶属关系。

# 致谢

- **[Dream Displays](https://github.com/arnodoelinger/dreamdisplays)** — 本分支基于的上游项目
- **[VideoPlayer-Library](https://github.com/squi2rel/VideoPlayer-Library)** — libvlc 原生构建和打包参考
