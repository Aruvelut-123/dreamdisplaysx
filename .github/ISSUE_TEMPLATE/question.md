---
name: Question
about: Ask a question about using or developing the mod
title: ''
labels: "type: question"
assignees: Aruvelut-123

---
body:
  - type: markdown
    attributes:
      value: |
        **Before asking** — thank you for reaching out! Please:
        1. **Search existing issues** (open and closed) first — your question may already be answered.
        2. Check [README.md](../../README.md) and the in-game display menu; many things are discoverable there.
        3. Include your **environment** below. Without it, a question like "playback is slow" cannot be answered usefully.

        **Environment basics** (also shown in the menu's "About" screen when available):
        - Mod version, Minecraft version, loader (Fabric / NeoForge / Paper).
        - OS, GPU, JDK — relevant for playback/performance questions.
  - type: dropdown
    id: category
    attributes:
      label: What is your question about?
      options:
        - Usage (how do I ...?)
        - Configuration (settings, keybinds, display setup)
        - Compatibility (loader versions, other mods, plugins)
        - Playback / media (codecs, formats, URLs, hardware decoding)
        - Development (building, contributing, the codebase)
        - Other
    validations:
      required: true
  - type: textarea
    id: question
    attributes:
      label: Your question
      description: Be specific. Include what you are trying to achieve, what you tried, and what happened.
      placeholder: I'm trying to ... I tried ... but ...
    validations:
      required: true
  - type: input
    id: environment
    attributes:
      label: Environment
      description: Mod version, Minecraft version, loader, OS — as much as is relevant.
      placeholder: 1.10.0.4, Minecraft 26.2, Fabric, Windows 11
  - type: textarea
    id: tried
    attributes:
      label: What have you tried?
      description: Anything you already attempted or looked up — it saves everyone time.
