# Dream Displays X — TODO

## ✅ 已完成（feat/libvlc 会话）

- [x] **A/V 崩溃根因（真修）**：`ensureDropBuffer()` 4 字节→整帧+padding——pause/seek 后 libvlc 整帧写入 4 字节 buffer 的堆溢出（0xC0000374，随机线程崩溃）→ **用户确认不再崩**
- [x] **视频 resume 卡帧**：`clear()` 不再销毁 buffers（只重置状态）——多次 seek→pause→resume 后视频不再卡在旧帧、音频照播 → **用户确认修好**
- [x] **A/V 缓冲/同步加固**：`LINE_BUFFER_BYTES`=100ms、line 访问移到 libvlc 音频线程、`onPlay` count 上限、`AUTO_RESYNC_THRESHOLD`=300ms、pause/resume 不 stop/start line、seek→pause→resume 时钟一致
- [x] **libvlc 3.0.21 → 3.0.22**：build.sh/collect.sh/natives.yml 版本升级 + Build Natives 重建（natives-de826bd-1787899222）
- [x] **JVM 诊断开关**（`LibVlcDiagnostics`）：silentAudio / noAudioCallback / noVideoCallback / noFrameSink / noVideoPublish / noAutoResync / noHardwareAccel——留作以后排查
- [x] **PreviewSection 视频预览偏位/空隙（部分）**：16:9 视频正常；**电影（宽幅/带黑边）上下都有空隙——"居中但没缩放"**，仍在排查中
- [x] **z-fighting**：lift 放对位置 + renderVideo 逐层分离——用户确认已修复
- [x] **帧向左偏移 ~1px**——用户确认已修复
- [x] **F3 解码器显示 dxva2**（`psz_description` + configuredHwBackend 回退）——用户确认已显示 dxva2
- [x] **循环重播卡最后一帧**：`beginSeek(0)` ENDED 分支 stop()+play() 后补 `eosFired.set(false)`（eac3fd40）——**待用户复测**
- [x] **ScrubSession 常驻 player**：每视频一个 video-only libvlc player，懒创建、复用、视频切换销毁（ee3999de）
- [x] **Scrub seek 修复**：seek 前先恢复播放 + 末尾 clamp + 1.5s 快速失败（7bdd1fdb）——**待用户复测**
- [x] **Scrub 用最低清晰度（≤360p）流提取**（bbfe541a）：从 availableVideo 挑最低清晰度流，seek 加载解码大幅加速——**待用户复测**
- [x] **onDrain 音频诊断**（随 7bdd1fdb 提交）：记录 fed frames≈音频总时长，用于判断音频是否比视频短——**待用户复测**
- [x] **长视频 crash（0xC0000005）**（eaadc9f4）：i420ToBufferedImage capacity 保护 → **用户确认长视频不再崩**
- [x] **循环重播**（eac3fd40）：eosFired 重置 → **用户确认循环重播没问题**
- [x] **Scrub 向后 seek 修复**（defc60da）：恢复 timeProvider 门控 + settle 后再 clear 一次，只接受前进过 target 后的新帧——解决"往回看时前半显示后半第一帧定死"——**待用户复测**

## 待验证 / 活跃问题

- [ ] **Scrub 短视频仍有问题**（用户提到但未详述）：等主人说明具体现象（提取慢？首帧？错帧？）
- [ ] **Scrub 长视频向后 seek 复测**：defc60da 修复后，确认从后往前 scrub 前半不再显示后半首帧
- [ ] **音频提前停止**（未定位）：音频和视频同步但音频先停。**onDrain 诊断日志**已就位，待用户复测：看音频总时长 vs 视频总时长，判断是否 DASH 音频流比视频短
- [ ] **搜索返回 0**（非本喵改动）：最近提交只碰播放/scrub 相关，**没碰搜索代码**。主人 20 秒内连搜 4 次全 0 = **Bilibili 搜索风控**。待主人冷却后复测
- [ ] **Scrub 整体复测**：awaitPast + ScrubSession 复用 + 360P 流 + 向后 seek 四合一，确认 hover 快速且所有位置正确
- [ ] **Scrub 诊断日志清理**：SCRUB-DEBUG / SCRUB-CRC / onDrain 日志在确认修好后移除
- [ ] **A/V 双向 auto-sync 最终确认**：诊断能报带符号领先量（audio ahead / video ahead），用户复测确认后关闭诊断

## 历史遗留需求（未处理）

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
