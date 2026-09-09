# Dream DisplaysX Project Roadmap

- Playlists & Queue (V3 protocol)
  - [ ] Add focused pure-JVM coverage for playlist end-of-stream policy decisions where the client policy is extracted from Minecraft-dependent code.
  - [ ] Manually smoke-test local and synced playlist transitions plus seeking on Fabric 26.2.
- Player Robustness
  - [ ] Investigate remaining native decoder stalls and CDN-specific scrub-preview timeouts.
  - [x] Far-seek audio rewind (A/V snap vs. flushing video clock) fixed with a settle window + seek-landing verification.
  - [x] Early stream death with unresolved duration no longer fires the ended-pause / playlist advance (the 0.5 s auto-pause).
