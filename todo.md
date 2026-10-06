# Dream DisplaysX roadmap

- [ ] Repeat natural-EOF playback on ARM64 FCL/Pojav with the new JNI load-order mitigation and confirm no `libvlc.so+0xef7418` crash.
- [ ] Smoke-test the cyan selection outline on Fabric and NeoForge with `display.particles = true` and the disabled setting.
- [ ] If EOF crashes persist, add temporary native instrumentation for JNA/VLC pthread-key creation and destructor order.
