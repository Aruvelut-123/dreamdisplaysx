# Dream DisplaysX Project Roadmap

- Playlists & Queue (V3 protocol)
  - [ ] Add focused pure-JVM coverage for playlist end-of-stream policy decisions where the client policy is extracted from Minecraft-dependent code.
  - [ ] Manually smoke-test local and synced playlist transitions plus seeking on Fabric 26.2.
- Player Robustness
  - [ ] Investigate remaining native decoder stalls and CDN-specific scrub-preview timeouts.
  - [ ] CROP stretch mode reportedly freezes the picture until another mode is selected. `fitRect`/`appendQuad`'s UV mapping, the CPU `fitFrame` crop path and the YUV plane path were audited and are correct, so it needs a reproduction that states whether the picture freezes or playback stalls, plus the display block size and video resolution involved.
- Curved displays (upstream 42af16c3)
  - [ ] Danmaku overlay is not drawn on conforming (stairs/slabs) displays; decide whether to project it onto the fitted mesh.
  - [ ] Smoke-test curved displays in game on 26.3 (Fabric + NeoForge) with slabs, stairs and mixed air/shaped selections.
  - [ ] Consider filtering `DisplayInfo` recipients by client conforming support (old clients ignore fields 24/25 and draw flat).
