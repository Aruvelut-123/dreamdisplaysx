# Dream DisplaysX roadmap

- [ ] Smoke-test the opt-in animated video-loading placeholder on Fabric/NeoForge and Android: default-off UI, first-frame handoff, errors/retry, rotated flat/curved displays, resource reload, and shader replay.
- [ ] On 1.20.1 Fabric, verify a newly created display's first frames render without `Not filled all elements of the vertex` (fixed in `VertexCompat` by emitting vertices in `POSITION_TEX_COLOR` element order).
- [ ] On 1.20.1 Fabric, confirm the display menu draws real sprites instead of magenta checkerboards, the loading spinner sits centred, and video frames appear without `libvlc first frame timeout`.
- [ ] Android 1.20.1: find out why libvlc never invokes the vmem `setup`/`lock` callbacks even though `libvlc_video_set_callbacks` forces `vout=vmem`. The next timeout now dumps `LibVlc.recentLogLines(12)`, so read that first instead of guessing.
- [ ] Repeat natural-EOF playback on ARM64 FCL/Pojav with the new JNI load-order mitigation and confirm no `libvlc.so+0xef7418` crash.
- [ ] Smoke-test the cyan selection outline on Fabric and NeoForge with `display.particles = true` and the disabled setting.
- [ ] If EOF crashes persist, add temporary native instrumentation for JNA/VLC pthread-key creation and destructor order.
