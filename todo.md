# Dream DisplaysX roadmap

- [ ] Smoke-test the opt-in animated video-loading placeholder on Fabric/NeoForge and Android: default-off UI, first-frame handoff, errors/retry, rotated flat/curved displays, resource reload, and shader replay.
- [ ] Repeat natural-EOF playback on ARM64 FCL/Pojav with the new JNI load-order mitigation and confirm no `libvlc.so+0xef7418` crash.
- [ ] Smoke-test the cyan selection outline on Fabric and NeoForge with `display.particles = true` and the disabled setting.
- [ ] If EOF crashes persist, add temporary native instrumentation for JNA/VLC pthread-key creation and destructor order.
- [ ] Reproduce "only one display can be created" on Fabric 1.20.1 and find the actual gate (the per-player cap defaults to 100, so it is not `max_displays_per_player`).
- [ ] Find why keyword search returns nothing on Forge: `SuggestionsController.runSearch` calls `BilibiliApi` directly, so the cause is not the Forge networking layer.
- [ ] Smoke-test the display hotkeys in `Options -> Controls` with runClient on Fabric/NeoForge/Forge: the four catalog bindings should appear under their own categories, be rebindable, and stay unbound by default. Compile-only verification so far (no JVM-only seam).
- [ ] Extend the CI `unit-test` job to `:platform:client:common:test` once `./gradlew :platform:client:common:test` has been seen green somewhere — `ModKeyMappingsTest`/`DisplayHotkeysTest` currently never run in CI.
- [ ] Smoke-test Sable sub-level anchoring on 1.21.1 with both sides installed: create a display on loose blocks, assemble it into a sub-level, and confirm it follows translation and rotation, stays clickable and keeps its sound position; then repeat with Sable only on the client to confirm the display degrades to a fixed one instead of vanishing.
- [ ] Smoke-test the "built then assembled" discovery path on Sable: the server cannot stamp a world-space hit, so the client's own probe has to find the sub-level and apply the correction. Watch for `Structure anchor discovery` in `latest.log` and confirm the picture does not jump on first pin or drift afterwards.
- [ ] Confirm Valkyrien Skies anchoring still works after the `StructureClientCompat` refactor (the renderer, distance census and acoustics now all go through the facade).
