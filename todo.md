# Dream DisplaysX roadmap

- [ ] Smoke-test the opt-in animated video-loading placeholder on Fabric/NeoForge and Android: default-off UI, first-frame handoff, errors/retry, rotated flat/curved displays, resource reload, and shader replay.
- [ ] Repeat natural-EOF playback on ARM64 FCL/Pojav with the new JNI load-order mitigation and confirm no `libvlc.so+0xef7418` crash.
- [ ] Smoke-test the cyan selection outline on Fabric and NeoForge with `display.particles = true` and the disabled setting.
- [ ] If EOF crashes persist, add temporary native instrumentation for JNA/VLC pthread-key creation and destructor order.
- [ ] Find why keyword search returns nothing on Forge: `SuggestionsController.runSearch` calls `BilibiliApi` directly, so the cause is not the Forge networking layer.
- [ ] Smoke-test the display hotkeys in `Options -> Controls` with runClient on Fabric/NeoForge/Forge: the four catalog bindings should appear under their own categories, be rebindable, and stay unbound by default. Compile-only verification so far (no JVM-only seam).
- [ ] Extend the CI `unit-test` job to `:platform:client:common:test` once `./gradlew :platform:client:common:test` has been seen green somewhere — `ModKeyMappingsTest`/`DisplayHotkeysTest` currently never run in CI.
