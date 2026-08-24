# Dream DisplaysX — TODO

## 待修复

### 进行中的 hwaccel 调整（已写部分代码，未验证/未完成）
- Fabric `ClothConfigScreen`：Video decoder 下拉改用 `startStringDropdownMenu`（正确的 Cloth 15.x 重载，之前 `startDropdownMenu` 4 参重载不存在导致显示 raw key 的文本框）——代码已改，待编译验证
- NeoForge `NeoForgeClothConfigScreen`：尚未同步同样的 `startStringDropdownMenu` 修复
- `HwAccelCandidateResolver`：Windows 上即使 Vulkan 渲染后端也**优先厂商解码器**（Intel→qsv / NVIDIA→cuda / AMD→amf，vulkan 仅作尾部 fallback）；Linux 保持 vulkan 优先 vaapi 次之——代码已改，待编译验证
- F3 调试菜单：显示**当前实际使用**的解码器（auto 实际选中的后端名，非配置文件值），不用翻日志——尚未实现
- 补全 `dreamdisplayx.config.decoder.*` 翻译 key（auto/software/各 FFmpeg 后端本地化名），当前缺失会显示 raw key

### 遗留 A/V 同步问题
- Pinned CatchUp（b53a45a8）仍会跳帧 + 闪屏一次；有时闪 2–3 次后音频消失——catch-up 目标是 decode head（prebuffer 最深层帧）而非播放位置，需改为按实际播放位置对齐

## 待办（功能/优化）

> **协议说明**：原版 Dream Displays X 仅支持 V2 协议。V3 协议不再向后兼容（不再支持 V2），只兼容旧版基础功能，但不支持新版 V3 专属新特性。

- rw_timeout 再优化：慢网络下更快切换/降级
- 视频播放前测速自动选择最快 CDN（复杂功能，需讨论方案）
- 分辨率问题：走近看 1080p 和 4K 一样模糊——排查纹理/解码尺寸
- Android 平台支持（重新评估可行性）
- 移除客户端 V1 协议支持
- 支持 WorldGuard 和领地插件（Land Claim Plugin）
- 同内容播放支持（Same content playback，归入 V3 协议）：多个显示器同步播放同一内容，播放状态与进度保持一致
- 播放列表与队列（V3 协议）
- Flashback 回放支持
- Replaymod 支持
- 屏幕投屏（Screen casting，归入 V3 协议）
- 远程控制（Remote control，归入 V3 协议）

## 已完成
- ✅ 字号变化时自动清理 fontCache/metricsCache（防止无限增长）
- ✅ 全局 Bilibili 登录（单服务器统一账号）
- ✅ 跨服凭据同步（MySQL）
- ✅ Bilibili 账户信息显示（头像/昵称/VIP）
- ✅ 暂停修复（canHoldWarm 代替 canPark）
- ✅ 移除 Android 支持
- ✅ 移除 Screenshare
- ✅ VIP API 字段修复（`vipType` → `type`，`vipStatus` → `status`）
- ✅ 收到 PlatformCredentials 时刷新 BilibiliAccountLabel
- ✅ FFmpeg CLI 子进程全部替换为 JavaCPP 进程内解码（视频/音频/缩略图）
- ✅ 硬件解码（FFmpeg hwaccel）：按平台+GPU 厂商自动选择 QSV/NVDEC/AMF/D3D11VA/VA-API/VideoToolbox，候选探测+逐级回退软件解码，`hwaccelDecoder` 配置（auto/software/指定后端），Cloth Config 下拉菜单（Fabric+NeoForge）
- ✅ 移除 Configured 支持，配置菜单只用 Cloth Config
- ✅ 配置菜单本地化（`dreamdisplayx.config.*` key，en_us + zh_cn）
- ✅ 音频解码器与视频通道并行打开（起播更快）