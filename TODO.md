# Dream DisplaysX — TODO

## 待修复

### 遗留 A/V 同步问题
- Pinned CatchUp（b53a45a8）仍会跳帧 + 闪屏一次；有时闪 2–3 次后音频消失——catch-up 目标是 decode head（prebuffer 最深层帧）而非播放位置，需改为按实际播放位置对齐

## 待办（功能/优化）

> **协议说明**：原版 Dream Displays X 仅支持 V2 协议。V3 协议不再向后兼容（不再支持 V2），只兼容旧版基础功能，但不支持新版 V3 专属新特性。

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