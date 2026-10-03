name: Bug Report
description: File a bug report.
title: "[Bug]: "
labels: ["type: bug"]
assignees:
  - Aruvelut-123
type: bug

body:
- type: markdown
  attributes:
    value: |
      Thanks for taking the time to fill out this bug report!
- type: textarea
  id: what-happened
  attributes:
    label: What happened?
    description: A clear and concise description of what the bug is.
    placeholder: Tell us what you see!
    value: "A bug happened!"
  validations:
    required: true
- type: dropdown
  id: mc-version
  attributes:
    label: Minecraft Version
    description: What version of Minecraft are you running?
    options:
      - 1.20.1
      - 1.21.1
      - 1.21.11
      - 26.1.2
      - 26.2
      - 26.3
    default: 0
  validations:
    required: true
- type: dropdown
  id: operating-system
  attributes:
    label: Operating System
    description: What operating system are you running?
    options:
      - Windows
      - Linux (fill more detail about your distro in "What happend?" section)
      - MacOS
      - Android (fill more detail about the launcher and your android version in "What happend?" section)
    default: 0
  validations:
    required: true
- type: dropdown
  id: software-type
  attributes:
    label: Client or Server Software Type
    description: What Mod Loader or Server software are you using?
    options:
      - Fabric Loader
      - NeoForge
      - Forge
      - Spigot
      - Paper
      - Folia
      - Other (fill more detail in "What happend?" section)
    default: 0
  validations:
    required: true
- type: dropdown
  id: client-type
  attributes:
    label: Client or Server Mod Loader Type
    description: What Mod Loader or Server software are you using?
    options:
      - Fabric Loader
      - NeoForge
      - Forge
      - Spigot
      - Paper
      - Folia
      - Other (fill more detail in "What happend?" section)
    default: 0
  validations:
    required: true
- type: input
  id: gpu-info
  attributes:
    label: Graphics Card info
    description: What graphics card are you using? Also make sure your driver is latest in case it's driver problem. (won't need to fill for server side problem)
    placeholder: AMD Radeon RX 6600 with 32.0.21045.5002 (Adrenalin 26.8.1) driver
- type: textarea
  id: logs
  attributes:
    label: Relevant Minecraft log output
    description: Please upload your logs to some website like pastebin or mclo.gs is fine, just provide the link. Also if it's libvlc related issue, please attatch a libvlc log link here too.
- type: upload
  id: screenshots
  attributes:
    label: Upload screenshots
    description: If applicable, add screenshots to help explain your problem.
  validations:
    required: false
