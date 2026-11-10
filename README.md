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
- 实验性 ReplayMod / Flashback 兼容

# 本分支新增

- Bilibili 扫码登录、全局凭据同步与广播，附 VIP 标识、搜索和弹幕
- RTMP / RTMPS / SRT 推流输入
- libvlc 播放引擎（JNA 调用原生库，视频与音频独立时钟）
- Fabric / Forge / NeoForge / Paper / Velocity / BungeeCord 全平台构建
- 快捷键接入 Minecraft 原生「选项 → 控制」，玩家可自行改键（默认未绑定）

# Android（PojavLauncher / FCL / Zalith）

- 支持 ARM64 和 x86_64 启动器，音频走 OpenSL ES
- 默认 libvlc 软件 `avcodec` 解码；可用 `-Ddreamdisplayx.hwDecode=<module>` 启用启动器的 MediaCodec 桥接
- MobileGlues 会自动限制解码线程数并绕开可能黑帧的直接 PBO 上传
- 字幕与弹幕在提供 Cacio/AWT 的启动器上加载系统 CJK 字体，缺少 AWT 时安全降级

# 播放引擎：从 libvlc 迁回 FFmpeg

本分支的长期目标是**把播放引擎整体从 libvlc 迁回 FFmpeg**，而不是给 libvlc 打补丁、也不是只加一个
Android 渲染后端。libvlc 是一个「解封装 + 解码 + 渲染 + 音频」的整包方案，迁回 FFmpeg 意味着把这四层
拆开、逐层换成 FFmpeg 的原生 API 自己驱动，最终不再依赖 libvlc 的进程内实例。

## 为什么迁移

- **可控性**：libvlc 把解封装、解码、渲染、音频时钟全部封装在自己的 `libvlc_media_player` 里，
  我们能调的只有少量选项。出现「音画不同步」「音量漂移」「DASH 分片被当成流结束」这类问题时，
  只能靠日志反推它的内部状态。
- **体积与依赖**：libvlc 需要为每个平台准备一整套原生运行时（含插件目录），
  Android 上还要额外处理 `libc++` SONAME 冲突、JNI `JNI_OnLoad` 崩溃、`AudioTrack` 数量上限等
  平台特有陷阱。FFmpeg 的 JavaCPP 绑定是单一 artifact，按平台自动解压。
- **可测试性**：解封装与解码是纯数据流，可以脱离播放器单独验证；libvlc 的行为只能整机实测。

## 当前进度

迁移分阶段推进，**尚未完成**，libvlc 仍是默认且唯一在用的引擎：

| 组件 | 状态 | 说明 |
|---|---|---|
| 会话契约 `PlaybackSession` | ✅ 已完成 | 把原先硬编码在 `MediaPlayer` 里的 37 个调用点抽成接口，`LibVlcSessionManager` 是第一个实现（`backendName = "libvlc"`） |
| 解封装 `FfmpegDemuxer` | ✅ 已完成 | `avformat_open_input` + `av_read_frame`，自动丢弃音视频以外的包 |
| 解码 `AndroidMediaCodecHwSolver` | ✅ 已完成 | FFmpeg `h264_mediacodec` / `hevc_mediacodec`，输出打包 BGRA；可按 `hardware` 分派像素格式并降级到软解 |
| 硬解后端选择 `FfmpegHwBackendPolicy` | ✅ 已完成 | 候选链 + 逐级降级 + `-Ddreamdisplayx.hwDecode` 覆盖；纯 JVM，16 个单测覆盖。Android 链 `<codec>_mediacodec → 软解`，桌面链按 `cuvid/qsv/vaapi/videotoolbox → 软解` 排列 |
| `FfmpegSession` 实现 | ✅ 已完成 | 把解封装、解码、PCM 音频输出串成「解封装 → 解码 → 发布帧」的会话，桌面与 Android 共用 |
| 桌面端 FFmpeg 解码 | ✅ 已完成 | 桌面走独立硬解解码器（NVDEC / QSV / VA-API / VideoToolbox），再降级到软件解码 |
| 音频迁出 libvlc | 🟡 桌面已完成 | 桌面用 Java Sound `SourceDataLine` 输出 PCM；Android 的 `AudioTrack` 通路尚未接线，仍由 libvlc 承担 |
| 移除 libvlc 依赖 | 🚧 未开始 | 迁移完成的标志 |

**`enable-ffmpeg-pipeline` 开关（默认 `false`）打开后，FFmpeg 接管视频解码**；桌面端还会接管音频
与播放时钟（`isSplit = false`，单个 `FfmpegSession` 全包），Android 端音频暂时仍归 libvlc。

## 已落地的部分

### 会话契约

`PlaybackSession` 定义了播放器需要的全部生命周期：启动、跳转、画质切换、挂起/恢复、帧发布、
音频控制、销毁。`MediaPlayer` 持有的是这个接口而不是具体类，因此新增后端不需要改动调用点，
调用点也不必靠 `is LibVlcSessionManager` 判断后端类型——用 `backendName` 即可。

### 解封装

`FfmpegDemuxer` 用 `avformat_open_input` → `avformat_find_stream_info` → `av_read_frame` 读取压缩包，
自动跳过并释放音频/字幕包。`Referer` / `User-Agent` 通过 `av_dict_set` 传入，
Bilibili 等 CDN 的防盗链因此可用；码流的 `extradata`（H.264 的 SPS/PPS、H.265 的 VPS/SPS/PPS）
会暴露出来，因为 MediaCodec 需要在配置阶段就拿到参数集。

### 解码

`AndroidMediaCodecHwSolver` 以 `ndk_codec=1` 走 FFmpeg 的 mediacodec **NDK** 后端，
**不引用任何 `android.*` 类**，因此在 Pojav / Zalith 这类「桌面 OpenJDK、没有 android.jar」的
隔离 JVM 上也能加载。

两个必须注意的实现细节：

- **硬解帧不是像素**。mediacodec 解码器输出 `AV_PIX_FMT_MEDIACODEC`，其 `data(0)` 是一个不透明的
  GPU surface 句柄；直接按平面读取会崩溃或花屏。必须先经 `av_hwframe_transfer_data` 拷回系统内存。
- **输出打包 BGRA**。取回 YUV 帧后用 `sws_scale` 一次性完成色彩空间转换与平面打包，
  接入既有的 `FramePixelFormat.BGRA32` 上传路径。
  不走 GPU-YUV 三平面路径，因为该路径（`DisplayYuvRenderTypes.active`）在 Sodium 环境下会黑屏，
  已被永久关闭。

### 配置开关

`enable-ffmpeg-pipeline`（**默认 `false`**）控制是否允许使用这条管线：

```toml
enable-ffmpeg-pipeline = true
```

它通过 `PlaybackConfig.ffmpegPipelineEnabled` 暴露给媒体层，默认值保证未实现该属性的宿主
行为不变。开关是**全平台**的：Android 上走 MediaCodec NDK 硬解，桌面端走独立硬解解码器
（`h264_cuvid` / `h264_qsv` / `h264_vaapi` / `h264_videotoolbox`）并逐级降级到软解。

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
