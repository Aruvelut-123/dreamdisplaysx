# Dream DisplaysX Project Roadmap

- Playlists & Queue (V3 protocol)
  - [ ] Manual runtime smoke test still requires a Fabric 26.3 server/client world; JVM coverage now exercises playlist revision tokens, stale EOS rejection, and direct-play ownership, but the launcher reports no installed Minecraft versions or configured worlds.
- Player Robustness
  - [ ] Native decoder stalls and CDN-specific scrub-preview timeouts still need a real libvlc/CDN run; scrub extraction now uses interruptible bounded waits and retires timed-out CDN sessions, while generation-aware recovery is covered by code review but not reproducible in this JVM-only checkout.
  - [ ] Manual desktop/Android smoke test remains required for per-viewer audio switching (muxed vs separate paths) and bilingual subtitle rendering; native/AWT/GPU paths are unavailable in this environment.
  - [ ] Manual large-HLS smoke test remains required for unavailable/orphaned renditions and quality/audio selection; bounded parsing and referenced-audio filtering have JVM coverage.
- Curved displays (upstream 42af16c3)
  - [ ] Smoke-test curved displays in game on 26.3 (Fabric + NeoForge) with slabs, stairs and mixed air/shaped selections; the Fabric dev run reached asset setup but no Minecraft window appeared, and the launcher has no installed modded instance/world.
- [ ] Manually smoke-test Linux 1.21.11 Fabric with repeated short-video loops, local/synced playlist advances, rapid URL switches and audio continuity; this Windows checkout has no Linux game runtime.
