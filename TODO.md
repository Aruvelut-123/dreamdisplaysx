# Dream DisplaysX — TODO

## ✅ 已完成（feat/libvlc，活跃会话）

> **z-fighting 分层 + A/V 诊断修正已实现、编译通过，待提交推送+构建验证。**

### 本次已完成的修复（下一提交内容）
1. **z-fighting（两个根因都修了）**：
   - `DisplayGeometry.kt`：surfaceOffset 0.02→**0.05**（光影 0.08）——显示 quad 彻底离开方块面，AMD 上 polygon offset 失效也不闪
   - `ScreenRenderer.kt`：`renderGpuTexture` 改为接收 stack+facing，**LETTERBOX backdrop 与 video 分不同深度层**（video 额外 lift OVERLAY_LIFT）——即 loading screen 逐层分离的深度方法；backdrop 画黑条、video 单独抬一层，两共面 quad 不再 z-fight
2. **A/V 诊断爆炸（audioLine=399923909ms≈uptime）**：libvlc 3.0.21 的 audio-callback `pts` 是单调时钟非媒体时间 → `playedPositionNanos(pts*1000)` 爆掉。**已改**：`LibVlcAudioOutput` 新增 `bufferedNanos()`（写帧−播放帧 = 缓冲延迟），`playedPositionNanos` 改为接受可信 reference；诊断日志改为 `video=Xms audioBuffered=Yms`（Y 小且稳定=健康，增长=漂移）。`lastWrittenMediaNanos` 字段已删
3. **音画卡住**：`LINE_BUFFER_BYTES` 0.2s→**0.5s**（`*5/10`）防 line.write 阻塞拖垮 libvlc 音频线程→视频卡住

### 待提交推送（改动文件）
- `DisplayGeometry.kt`、`ScreenRenderer.kt`、`LibVlcAudioOutput.kt`、`LibVlcSessionManager.kt`、`CHANGELOG.md`
- 下一步：git add/commit/push → gh CI 验证 → 要用户测试，看日志 `A/V sync: video=… audioBuffered=…ms` 是否小且稳定

### 历史遗留需求（未处理）
- hover 预览首帧（extraction 已确证正确，display 层未解决）
- fps 诊断（publishFrame 每 N 帧日志，验证 GPU scaling 恢复 60fps）

---

## 待办（功能/优化）

> **协议说明**：原版 Dream Displays X 仅支持 V2 协议。V3 协议不再向后兼容（不再支持 V2），只兼容旧版基础功能，但不支持新版 V3 专属新特性。

- 显示块动态灯光（LambDynamicLights 方案，Fabric+NeoForge+全版本）：LambDynamicLights 安装启用→逐块彩色光（采样视频对应像素颜色）；仅 Iris 光影→普通光；都没装→照旧
- Android 平台支持（重新评估可行性）
- 移除客户端 V1 协议支持
- 支持 WorldGuard 和领地插件（Land Claim Plugin）
- 同内容播放支持（Same content playback，归入 V3 协议）：多个显示器同步播放同一内容，播放状态与进度保持一致
- 播放列表与队列（V3 协议）
- Flashback 回放支持
- Replaymod 支持
- 屏幕投屏（Screen casting，归入 V3 协议）
- 远程控制（Remote control，归入 V3 协议）