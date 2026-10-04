# Dream DisplaysX Project Roadmap

- Playlists & Queue (V3 protocol)
  - [ ] Add focused pure-JVM coverage for playlist end-of-stream policy decisions where the client policy is extracted from Minecraft-dependent code.
  - [ ] Manually smoke-test local and synced playlist transitions plus seeking on Fabric 26.2.
- Player Robustness
  - [ ] Investigate remaining native decoder stalls and CDN-specific scrub-preview timeouts.
- Curved displays (upstream 42af16c3)
  - [ ] Danmaku overlay is not drawn on conforming (stairs/slabs) displays; decide whether to project it onto the fitted mesh.
  - [ ] Smoke-test curved displays in game on 26.3 (Fabric + NeoForge) with slabs, stairs and mixed air/shaped selections.
  - [ ] Consider filtering `DisplayInfo` recipients by client conforming support (old clients ignore fields 24/25 and draw flat).
- [ ] Manually smoke-test Linux 1.21.11 Fabric with repeated short-video loops and rapid URL switches.
