# Dream DisplaysX Project Roadmap

- Playlists & Queue (V3 protocol)
- Add version-specific Vanilla item-component adapters for the experimental remote-control stick.
- Danmaku (弹幕) overlay — render Bilibili comments scrolling over displays (Bilibili only, no YouTube). Reference implementation: [squi2rel/VideoPlayer](https://github.com/squi2rel/VideoPlayer), package `com.github.squi2rel.vp.danmaku`.
  - Model & timeline: `DanmakuEntry` (mode 1–6 = scroll / fixed-top / fixed-bottom, `progressMs`, color, font size) + `ClientDanmakuController` (6-minute VOD segment loading, active/lane management, speed & density presets, dedup by key).
  - Rendering: `ClientDanmakuRenderer` draws onto the display surface with depth layering; `DanmakuTextLayoutCache` caches text metrics.
  - Sources: `BiliVodDanmakuFetcher` + `BiliDmSegParser` (protobuf `/x/v2/dm/list/seg.so`) for VOD and `BiliLiveDanmakuClient` (live WebSocket) for live rooms; reuse the existing Bilibili `SESSDATA` / WBI auth for fetching.
  - UI: per-display toggle + settings (display area %, speed, density, opacity, font size, type filters) in the display menu, mirroring `VideoManagementScreen`'s danmaku overlay.
