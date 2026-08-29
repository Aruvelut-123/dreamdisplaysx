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
- [x] **Scrub 向后 seek 修复**（defc60da）：恢复 timeProvider 门控 + settle 后再 clear 一次，只接受前进过 target 后的新帧——解决"往回看时前半显示后半第一帧定死" → **用户确认长视频 scrub 现在正常**
- [x] **Scrub 短视频超时修复**（84753725）：Bilibili 解析 codecs 字段 + scrub 优先选 H.264 ≤360p 流（避开软件解码慢的 AV1/HEVC）——**待用户复测**
- [x] **Scrub 8x 倍率 seek**（07ebc011，已回退 2f586ef6）：加速播放会让 vout 跳帧渲染，phase-2 latch 抓不到/错帧，**长视频向后 scrub 回归** → 回退恢复 1x
- [x] **Scrub network-caching=300ms**（51c60261）：libvlc 默认 1500ms，seek 后要缓冲满才渲染；短视频 CDN 慢 → 2s latch 超时 → 疯狂 frame timeout。加 `:network-caching=300` 让 seek 后快速渲染——**待用户复测**
- [x] **F3 显示 Video FPS 行**（2f586ef6）：Frames 下面加"Video FPS: n"（第一个播放中的屏，无需 JVM 参数）——**待用户看**
- [x] **音频提前结束诊断**（84753725）：onDrain + END_REACHED 对比音频/视频时长 → **用户确认 2:13 视频音频 129s（视频短 ~4s）** = DASH 音频流本身比视频短，**媒体源特性，非代码 bug**
- [x] **F3 显示 Stream 信息行**（da301b78）：Video FPS 下加"Stream: codec 高度p 源fps"——用于判断 1080P vs 4K 帧率差异是否是编码/软解问题——**待用户看**

- [x] **F3 显示 Audio feed rate**（97a821e5）：Video FPS/Stream 下加"Audio: n ms/s"（每秒音频喂入率，~1000 健康）——用于判断音频时钟是否欠载拖慢视频——**待用户看**

## 待验证 / 活跃问题

- [ ] **帧率反常识（1080P 10-17fps vs 4K 25-38fps）**：F3 显示两视频都是 H.264（avc1.640033/640034）→ **不是编码/解码问题**。4K 前 3 秒和 1080P 一样慢、之后变快 → 指向**音频时钟/缓冲**。**用 F3 的 Audio ms/s 行看 1080P 是否 <1000（音频欠载拖慢 libvlc 主时钟→vout）**——待用户看
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
