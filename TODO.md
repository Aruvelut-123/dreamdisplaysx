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
- [x] **循环重播卡最后一帧**：`beginSeek(0)` ENDED 分支 stop()+play() 后补 `eosFired.set(false)`——第二次 END_REACHED 不再被吞（已提交 eac3fd40，**未验证**）
- [x] **ScrubSession 常驻 player**：每视频一个 video-only libvlc player，懒创建、复用、视频切换销毁（ee3999de + 7bdd1fdb，**待用户复测**）

## ⏸️ 暂停中（用户睡觉，未推送/未验证）

- [ ] **Scrub 快速提取（真 bug 修复已提交，待用户复测）**：`7bdd1fdb` 修复"暂停状态 seek 后 get_time 不前进 → 每次 hover 卡 8 秒 + seek did not reach target"。修复=seek 前先 `set_pause(0)` 恢复播放 + 末尾 clamp settle + 1.5s 快速失败。**待用户测：hover 是否快速且所有位置正确**
- [ ] **Scrub 用 360P 加速（用户建议，未实现）**：scrub 现在用当前清晰度流 URL（`capturedStreamRawUrl()`）→ 长视频 4K seek 慢。方案：从 `availableVideo` 里挑最低清晰度流（≤360p）给 scrub 用，或重新解析 360P。**进行到一半，未完成**
- [ ] **音频提前停止（未定位）**：音频和视频同步但音频先停。已加 `onDrain` 诊断日志（记录 fed frames≈音频总时长），**未推送/未提交**。待用户复测：看音频总时长 vs 视频总时长，判断是否 DASH 音频流比视频短
- [ ] **Scrub 半屏问题（历史，应已被 7bdd1fdb 修复）**：左半显示右半首帧——根因是 60ms 短暂 play 对网络 seek 不可靠 + 暂停状态 seek。已改为 playing seek + awaitPast
- [ ] **搜索返回 0（非本喵改动）**：最近提交只碰 ScrubPreview/DisplayScreen/DisplayMediaController/LibVlcFrameExtractor/CHANGELOG，**没碰搜索代码**。主人 20 秒内连搜 4 次全 0 = **Bilibili 搜索风控**。待主人冷却后复测

## 待验证 / 活跃问题

- [ ] **Scrub 诊断日志清理**：SCRUB-DEBUG / SCRUB-CRC 日志在 scrub 确认修好后移除（LibVlcFrameExtractor 里还有）
- [ ] **onDrain 诊断日志**：音频提前停止问题确认后移除
- [ ] **A/V 双向 auto-sync 最终确认**：诊断能报带符号领先量（audio ahead / video ahead），用户复测确认后关闭诊断
- [ ] **长视频 crash（0xC0000005）**：`i420ToBufferedImage` capacity 保护已提交（eaadc9f4），**未验证**
- [ ] **待办提交链**：7bdd1fdb（scrub 修复）+ ba629e1f（changelog）+ onDrain 诊断（未提交）——onDrain 诊断改动还**在本地未提交**，下次开工先提交推送

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
