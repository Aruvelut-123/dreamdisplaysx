# Dream DisplaysX — TODO

## 待修复

### 1. Danmaku 其他问题
- 暂无

## 已完成
- ✅ Danmaku 间距随字号动态变化（trackHeight + 6px padding，scrollTrackCount 动态计算）
- ✅ TOP/BOTTOM 弹幕间距随字号变化（1.5× line spacing）
- ✅ 字号变化时自动清理 fontCache/metricsCache（防止无限增长）
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