# Dream DisplaysX — TODO

## ✅ 已完成（feat/libvlc，活跃会话）

> **Z-fighting 真修复（lift 放对位置）+ A/V 缓冲调优 + 长视频预览动态采样，已提交推送、CI 通过。**

### 最近两轮已提交的修复
1. **z-fighting 真修复（`5a8377b6` 后新提交）**：
   - `DisplayGeometry.kt`：surfaceOffset 0.02→0.05（光影 0.08）
   - `ScreenRenderer.kt`：改为 `renderVideo` + 每个 quad 独立 `drawLayer`——**lift 在 `applyScreenTransform` 之前**。首版把 lift 放在 transform 内侧（被 `scale(w,h,0)` 的 z×0 吃掉=失效），backdrop 与 video 仍共面。现在用与 loading placeholder 相同的逐层分离，两 LETTERBOX quad 真正分深度
2. **A/V 缓冲**：`LINE_BUFFER_BYTES` 0.5s→**0.3s**——video-ahead=line 缓冲量（恒定），0.5s 让嘴型落后半秒，0.3s 平衡防卡+嘴型
3. **ScrubPreview 动态采样**：固定 20 帧→按时长自适应（≤45 帧，约 8s 间隔）——长电影 hover 不再卡在一帧/首帧
4. **A/V 诊断**：libvlc 3.0.21 的 audio-callback `pts` 是单调时钟非媒体时间→改报 `video=Xms audioBuffered=Yms`（Y 小稳定=健康）

### 用户已测 / 待验证的反馈（活跃问题）
- [ ] **z-fighting**：首版 lift 顺序错没修好，已重写 renderVideo（等待用户复测核实是否消失）
- [ ] **hover 预览部分仍显示首帧**：已把采样改动态（长视频更密），待用户复测；仍可能有个别 ts 提取失败→frameAt 回退
- [ ] **预览不按 LETTERBOX 显示（变形填满）**：extractor 的 `scale` 已正确带黑边，但用户看到拉伸——待查 blit/box 比例 mismatch，或 hover 显示的是 live 纹理而非 ScrubPreview
- [ ] **帧向左偏移 ~1px**（用户标红框）：待查 fitRect/appendQuad 或 texture UV 边界
- [ ] **A/V: video 领先 audio≈缓冲量，不会自动归零**：本质是 line 缓冲固有延迟；已把缓冲降到 0.3s。真正 0 偏移需 libvlc `clock_cb`（3.0 无公开 API）——暂以减小缓冲为解，向用户说明

### 历史遗留需求（未处理）
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