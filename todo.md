# Dream DisplaysX roadmap

- [ ] Smoke-test the opt-in animated video-loading placeholder on Fabric/NeoForge and Android: default-off UI, first-frame handoff, errors/retry, rotated flat/curved displays, resource reload, and shader replay.
- [ ] Repeat natural-EOF playback on ARM64 FCL/Pojav with the new JNI load-order mitigation and confirm no `libvlc.so+0xef7418` crash.
- [ ] Smoke-test the cyan selection outline on Fabric and NeoForge with `display.particles = true` and the disabled setting.
- [ ] If EOF crashes persist, add temporary native instrumentation for JNA/VLC pthread-key creation and destructor order.
- [ ] Reproduce "only one display can be created" on Fabric 1.20.1 and find the actual gate (the per-player cap defaults to 100, so it is not `max_displays_per_player`).
- [ ] Find why keyword search returns nothing on Forge: `SuggestionsController.runSearch` calls `BilibiliApi` directly, so the cause is not the Forge networking layer.
- [ ] Add a `test` step to CI: `_build.yml` only runs `publishJar`/`shadowJar`, so 30+ unit tests never execute on a push.
