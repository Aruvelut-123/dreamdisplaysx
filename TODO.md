# Dream DisplaysX — TODO

## 待修复

### 1. Danmaku 间距不随字号变化
- 当字号变大时，track 间距（`trackCount = 8`，`pickVerticalTrack` 硬编码 `26px`）应该调整
- 行间距和轨道高度应基于当前字号动态计算，像 Bilibili 一样只影响新弹幕

### 2. 弹幕溢出滚动区域
- ~~SettingsSection 滚动时，RenderDistance 和 DanmakuFilterBar 的控件会画到外面~~ ✅ 已修复
- 现在 `renderRow` 在不可见时也会 `place()` 控件，用 scissor 裁剪

### 3. Danmaku 其他问题
- 字体大小变化时应该重新计算 `fontCache` 和 `metricsCache`
- 速度变化时只影响新弹幕（已在 `add()` 中实时读取 `s.danmakuSpeed`）

## 已完成
- ✅ 全局 Bilibili 登录（单服务器统一账号）
- ✅ 跨服凭据同步（MySQL）
- ✅ Per-display 弹幕设置 UI
- ✅ Bilibili 账户信息显示（头像/昵称/VIP）
- ✅ 暂停修复（canHoldWarm 代替 canPark）
- ✅ 移除 Android 支持
- ✅ 移除 Screenshare
- ✅ 弹幕设置滚动修复
- ✅ VIP API 字段修复（`vipType` → `type`，`vipStatus` → `status`）
- ✅ 收到 PlatformCredentials 时刷新 BilibiliAccountLabel