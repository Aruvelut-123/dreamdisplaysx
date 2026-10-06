[![最新版本](https://img.shields.io/github/release/Aruvelut-123/dreamdisplaysx.svg)](https://github.com/Aruvelut-123/dreamdisplaysx/releases/latest)
[![许可证](https://img.shields.io/github/license/Aruvelut-123/dreamdisplaysx)](https://github.com/Aruvelut-123/dreamdisplaysx/blob/main/LICENSE)

<div align="center">
  <img src="https://i.imgur.com/HM4JUdj.png" alt="Dream DisplaysX">
</div>

# 在 Minecraft 中播放视频

Dream DisplaysX 可以把视频、直播和更多媒体直接放进游戏里的显示器，与朋友一起观看。

创建显示器、粘贴链接，就可以开始播放喵~

[English README](README.en.md) · [项目主页](https://github.com/Aruvelut-123/dreamdisplaysx)

![显示器播放视频](https://i.imgur.com/JoARVeu.png)

Dream DisplaysX 是 [Dream Displays](https://github.com/arnodoelinger/dreamdisplays) 的分支。如果发现本分支的问题，请在[本仓库](https://github.com/Aruvelut-123/dreamdisplaysx/issues)提交 issue，不要提交到上游仓库。

# 支持的媒体来源

![显示器菜单](https://i.imgur.com/wGnDzrT.png)

| 来源 | 支持内容 |
|---|---|
| **Twitch** | 直播频道、VOD 和 clips |
| **Kick** | 直播频道和 VOD |
| **Vimeo** | 公开视频和直播活动 |
| **Bilibili** | 视频、直播、番剧、剧集和电影 |
| **任意视频链接** | 直接视频文件和 `.m3u8` / `.mpd` 直播流 |
| **分享链接** | Google Drive、Dropbox、Imgur 分享链接会改写为实际文件链接 |
| **其他网站** | 不在列表中的链接也可以直接尝试播放 |

# 多人同步播放

创建显示器后输入 `/display video <link>`，Dream DisplaysX 会自动解析链接。

显示器支持本地、同步和广播三种播放模式，可以和服务器上的其他玩家一起观看，同时尽量减少网络流量。

## 协议兼容性

客户端优先协商支持批量数据的 **V3** 信封（`dreamdisplayx:v3`），不可用时自动回退到 **V2**（`dreamdisplayx:v2`）。V3 的同内容快照可以让多个显示器共享 URL 和播放时间线。旧版 **V1** 流量会被识别并在聊天中提示，但不会被处理。V2/V3 握手还会声明曲面显示器能力；未声明能力的旧客户端会收到平面兼容表示。

V3、`/display group`、Paper 远程控制棒以及 Flashback / ReplayMod 桥接仍属于实验功能，接口可能变化。

![影院](https://i.imgur.com/PKxe0oG.png)

# 功能特点

- 无缝多人播放：本地、同步和广播模式
- 强大的媒体播放器：搜索、画中画等
- 沉浸式音频：3D 声音，音量最高 200%
- 可调分辨率：按媒体源提供的档位选择，最高支持 4K；未知直链尺寸不会强制放大到 4K
- 多音轨和多字幕：菜单可选择音频 / 字幕轨，支持双语字幕、字幕缓存和 HLS WebVTT 分段；桌面切换音轨不会重启视频画面，Android 会重建原生播放会话，并区分复用与独立音频避免重复播放
- 有界 HLS 主清单解析：未使用的子流保持延迟校验，异常或过大的清单不会触发串行探测风暴
- 硬件加速：libvlc 支持 d3d11va、vaapi、videotoolbox 等后端
- 视频动态光照：安装 LambDynamicLights 后，显示器画面可以照亮周围世界
- Complementary 着色器修补：只为 Complementary r5.8.1 创建临时副本，不修改其他着色器包
- 可自定义显示器：尺寸、亮度、拉伸模式和方向
- 服务器支持：Paper、Fabric、NeoForge、Velocity、BungeeCord
- 权限和领地保护：支持 LuckPerms、WorldGuard 以及可选领地插件
- 实验性 ReplayMod / Flashback 兼容：回放可以暂停、拖动、切换视频并跟随时间线
- 数据库播放列表：SQLite/MySQL 持久化队列，支持添加、批准、移除、跳过和队列结束策略；待批准项目不会被自动播放
- 曲面显示器：支持楼梯、台阶和混合形状表面，视频、字幕与弹幕都会投影到同一套贴合网格；不支持曲面的旧客户端会收到平面兼容表示

# 本分支新增内容

相较于原版 Dream Displays，本分支还提供：

- Bilibili 扫码登录（`/dlogin`），服务器使用 AES-256-GCM 加密保存 `SESSDATA`
- 全局 Bilibili 登录，在服务器网络内同步并广播给在线玩家，支持 LuckPerms
- Bilibili VIP 标识、搜索、简体中文和弹幕
- RTMP / RTMPS / SRT 推流输入
- libvlc 播放引擎：通过 JNA 调用原生 libvlc，视频和音频使用独立时钟
- Minecraft 1.21.1、1.21.11、26.1.2、26.2 和 26.3 支持

# 开始使用

使用黑色混凝土搭建显示器，用钻石斧选中区域并输入 `/display create`。创建完成后注视显示器，输入 `/display video <link> [language]`。按住 Shift 右键可以打开显示器设置菜单。

## 命令参考

| 命令 | 位置 | 作用 |
|---|---|---|
| `/display create` / `/display delete` | 服务器 | 创建 / 删除显示器 |
| `/display video <link> [language]` | 服务器 | 播放视频、直播或推流地址 |
| `/display list` / `/display info` | 服务器 | 列出 / 查看显示器 |
| `/display on` / `/display off` | 服务器 | 开启 / 关闭所有显示器 |
| `/display login bilibili <sessdata>` | 服务器 | 全局保存 Bilibili 凭据（仅 OP） |
| `/display logout bilibili` | 服务器 | 删除全局 Bilibili 凭据（仅 OP） |
| `/dlogin` | 客户端 | 打开 Bilibili 登录界面（仅 OP） |
| `/dlogoff` | 客户端 | 注销 Bilibili（仅 OP） |

> **Bilibili 登录提示：** 在游戏中执行 `/dlogin`，使用 Bilibili 手机客户端扫描二维码。成功后，mod 会把 `SESSDATA` 发送到服务器并加密保存，再同步到服务器网络和在线客户端。

搜索框和 `/display video` 都支持直接粘贴完整的 `http(s)` 视频文件地址；链接中的百分号编码路径会原样保留，适合 WebDAV 等直链文件。解析到多音轨或字幕轨时，可在显示器菜单的音频 / CC 按钮中切换；第二个 CC 按钮会把另一条字幕显示在双语文本的下一行。HLS 主播放列表只在实际选择子流时校验子链接，避免无效或过大的清单拖慢解析；字幕请求会限制大小和超时，直播字幕播放列表暂不下载。

## 下载

从[最新版本](https://github.com/Aruvelut-123/dreamdisplaysx/releases/latest)下载与你的加载器和 Minecraft 版本对应的 jar：

- `dreamdisplayx-fabric-<mc>-<version>.jar`：Fabric / Quilt 客户端或服务器 mod
- `dreamdisplayx-neoforge-<mc>-<version>.jar`：NeoForge 客户端或服务器 mod
- `dreamdisplayx-paper-<version>.jar`：Paper 插件（1.21.1 – 26.3）
- `dreamdisplayx-velocity-<version>.jar` / `dreamdisplayx-bungeecord-<version>.jar`：代理插件

客户端把 mod 放进 mods 文件夹，服务器安装匹配的 mod 或插件即可。LambDynamicLights 是可选的客户端动态光照集成。

## 支持的版本

| Minecraft | Fabric | NeoForge | Paper | 备注 |
|---|---|---|---|---|
| 1.21.1 | ✅ | ✅ | ✅ | 长期支持版本 |
| 1.21.11 | ✅ | ✅ | ✅ | 上游默认版本 |
| 26.1.2 | ✅ | ✅ | ✅ | |
| 26.2 | ✅ | ✅ | ✅ | |
| 26.3 | ✅ | ✅ | ✅ | |

## Android（PojavLauncher / FCL / Zalith）

- 支持 ARM64 和 x86_64 启动器
- 使用 libvlc OpenSL ES 输出音频；复用音频留在视频播放器，独立音频使用单独播放器；桌面 3D 定位音频（`javax.sound`）在 Android 不可用
- 默认使用 libvlc 软件 avcodec 解码，避免 Pojav/FCL 缺少 `android.media.MediaCodecList` 时反复探测失败；可用 `-Ddreamdisplayx.hwDecode=<module>` 显式启用启动器提供的 MediaCodec 桥接
- MobileGlues 会自动限制 avcodec 解码线程数，降低 GL 翻译缓冲与视频缓冲争抢原生内存；播放器切换时等待旧 vout 进入暂停态后再创建新会话
- teardown 时 Android libvlc 播放器只暂停、不 stop 或 release，避免原生 `SIGSEGV`
- vmem 回调使用 32 字节对齐 stride、格式代际和槽位租约，旧播放器与 scrub 会话的回调/缓冲区在进程结束前保持可达
- MobileGlues 绕过可能产生黑帧的直接 PBO/BGRA 上传，改用 Minecraft command encoder 纹理路径
- Android SQLite 和独立命名的 `libc++` 会解压到应用内部可执行目录
- 提供安全的 libvlc JNI 桥接和 `android.os.Environment` stub，不加载 `libvlcjni.so`
- 字幕和弹幕在提供 Cacio/AWT 的启动器上会优先加载 Android 系统 CJK 字体（如 Noto Sans CJK）；不带 AWT 的运行时安全跳过纹理文字而不崩溃

## JVM 参数（高级调试）

以下参数都可选，默认值适用于大多数情况：

| 参数 | 默认值 | 作用 |
|---|---|---|
| `-Ddreamdisplayx.hwDecode=<backend>` | Windows `d3d11va` / Linux `vaapi` / Mac `videotoolbox` / Android `avcodec` | libvlc 解码模块；Android 默认固定软件 `avcodec`，非空值可显式启用启动器的 MediaCodec 桥接，空值仍固定软件解码 |
| `-Ddreamdisplayx.androidAvcodecThreads=<n>` | MobileGlues `2`，其他 Android `4` | 限制 Android 软件解码线程数，范围 `1..8`；用于降低原生内存峰值 |
| `-Ddreamdisplayx.audioBufferMs=<ms>` | `100` | Java Sound 音频缓冲区；增大更稳，减小可降低延迟 |
| `-Ddreamdisplayx.networkCachingMs=<ms>` | `300` | libvlc 网络 / 文件缓存；网络卡顿时可以增大 |
| `-Ddreamdisplayx.debugFps=true` | 关闭 | 在显示器菜单预览中显示实际视频 FPS |
| `-Ddreamdisplayx.verboseLibvlc=true` | 关闭 | 打开 libvlc 调试日志 |
| `-Ddreamdisplayx.noDropLateFrames=true` | 关闭 | 禁止丢弃迟到帧（仅诊断，可能造成画面闪烁） |
| `-Ddreamdisplayx.noAutoResync=true` | 关闭 | 禁用音视频漂移修正 |
| `-Ddreamdisplayx.silentAudio=true` | 关闭 | 不创建音频输出，用于定位问题 |
| `-Ddreamdisplayx.noAudioCallback=true` | 关闭 | 不注册 libvlc 音频回调 |
| `-Ddreamdisplayx.noVideoCallback=true` | 关闭 | 不注册 libvlc 视频回调 |
| `-Ddreamdisplayx.noFrameSink=true` | 关闭 | 跳过预览和弹出窗口的画面接收 |
| `-Ddreamdisplayx.noVideoPublish=true` | 关闭 | 跳过 GPU 画面发布，用于定位问题 |
| `-Ddreamdisplayx.noHardwareAccel=true` | 关闭 | 不向 libvlc 传递 `--avcodec-hw` |

![显示器](https://i.imgur.com/yyIKdp8.png)

## 从源码构建

```bash
git clone https://github.com/Aruvelut-123/dreamdisplaysx.git
cd dreamdisplaysx
./gradlew :platform:client:fabric:build :platform:client:neoforge:build
```

项目使用 [Stonecutter](https://github.com/kikugie/stonecutter) 构建多版本，版本属性在 `versions.json`，当前版本在 `versions/active.txt`。libvlc 和 SQLite 原生文件由 CI 的 Build Natives 工作流构建，首次启动时下载到 `./dreamdisplayx/natives/<os>/<arch>/`，不会打进 jar。

## 免责声明

Dream DisplaysX 与原 Dream Displays 项目及 Mojang Studios 没有隶属关系。

## 致谢

- **[Dream Displays](https://github.com/arnodoelinger/dreamdisplays)**：本分支基于的上游项目
- **[VideoPlayer-Library](https://github.com/squi2rel/VideoPlayer-Library)**：libvlc 原生构建和打包参考
