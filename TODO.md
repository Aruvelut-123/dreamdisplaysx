# Dream Displays X — TODO

## 待验证 / 活跃问题

- [ ] **音频提前 ~4s**：已确认是媒体源特性（Bilibili 音频流短），无法播放不存在的数据；用户接受则关闭诊断
- [ ] **搜索返回 0**（非本喵改动）：最近提交只碰播放/scrub 相关，**没碰搜索代码**。主人 20 秒内连搜 4 次全 0 = **Bilibili 搜索风控**。待主人冷却后复测
- [ ] **Scrub 诊断日志清理**：SCRUB-DEBUG / SCRUB-CRC / onDrain 日志在确认修好后移除
- [ ] **A/V 双向 auto-sync 最终确认**：诊断能报带符号领先量（audio ahead / video ahead），用户复测确认后关闭诊断

## 历史遗留需求（未处理）

- **Scrub 短视频 frame timeout（暂缓——主人指示难修复先放着）**：H.264 优先（84753725）+ network-caching=300（51c60261）已提交，但**短视频仍可能超时**；8x 倍率已回退（2f586ef6）。难在 Bilibili 短视频 CDN（edge.mountaintoys.cn）seek 分片慢/不稳定 + libvlc vout 渲染时序。**后续再看**
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