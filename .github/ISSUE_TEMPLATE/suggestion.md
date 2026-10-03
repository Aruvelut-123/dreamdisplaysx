---
name: Suggestion
about: Suggest a feature or an improvement
title: ''
labels: "type: suggestion"
assignees: Aruvelut-123

---
body:
  - type: markdown
    attributes:
      value: |
        **Before suggesting** — thank you for the idea! Please:
        1. **Search existing issues** (open and closed) — someone may have already asked for this.
        2. Keep the suggestion **focused**: one idea per issue is easier to discuss and implement.
        3. Explain the **why** as much as the **what** — the motivation often matters more than the feature itself.
  - type: dropdown
    id: category
    attributes:
      label: What does this suggestion touch?
      options:
        - Playback / media (codecs, formats, hardware decoding, syncing)
        - Search / paste-a-link
        - Display menu (settings, sliders, keyboard shortcuts)
        - Playlist / queue
        - Thumbnails / first-frame preview
        - Danmaku overlay
        - Curved displays (stairs / slabs)
        - Networking / multi-display sync
        - UI / visuals
        - Performance
        - Other
    validations:
      required: true
  - type: textarea
    id: idea
    attributes:
      label: Your idea
      description: Describe the feature or improvement you want. What does it do, and how would a player use it?
      placeholder: I would like the ability to ... so that I can ...
    validations:
      required: true
  - type: textarea
    id: motivation
    attributes:
      label: Motivation
      description: Why do you want this? What problem does it solve or what workflow does it enable?
  - type: textarea
    id: alternatives
    attributes:
      label: Alternatives considered
      description: What workarounds or other mods/features do you currently use instead?
  - type: input
    id: environment
    attributes:
      label: Environment
      description: Mod version, Minecraft version, loader, OS — if relevant to the suggestion.
      placeholder: 1.10.0.4, Minecraft 26.2, Fabric, Windows 11
