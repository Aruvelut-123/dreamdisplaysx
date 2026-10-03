---
name: Bug 报告
description: 提交 Bug 报告。
title: "[Bug]: "
labels: ["type: bug"]
assignees:
  - Aruvelut-123
type: bug

body:
- type: markdown
  attributes:
    value: |
      感谢你花时间填写这份 Bug 报告！
      提交前请确认你使用的是**最新版本**（见 [CHANGELOG.md](../../CHANGELOG.md)），并且**搜索过现有 issues**（含已关闭的），确认没有重复报告。
- type: input
  id: mod-version
  attributes:
    label: Mod 版本
    description: 你正在运行的 Dream DisplaysX 版本（例如 `1.10.0.4` —— 见 CHANGELOG.md 或 mods 文件夹）。
    placeholder: 1.10.0.4
  validations:
    required: true
- type: dropdown
  id: mc-version
  attributes:
    label: Minecraft 版本
    description: 你运行的 Minecraft 版本是什么？
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
    label: 操作系统
    description: 你在什么操作系统上运行？
    options:
      - Windows
      - Linux（发行版详情请在"发生了什么？"中补充）
      - MacOS
      - Android（启动器和安卓版本详情请在"发生了什么？"中补充）
    default: 0
  validations:
    required: true
- type: dropdown
  id: problem-side
  attributes:
    label: 客户端还是服务器
    description: 问题出现在哪一端？
    options:
      - 客户端（Fabric / NeoForge）
      - 服务器（Paper / Folia）
      - 两端都有
    default: 0
  validations:
    required: true
- type: dropdown
  id: loader
  attributes:
    label: Mod 加载器 / 服务器软件
    description: 你使用的是哪个 Mod 加载器或服务器软件？
    options:
      - Fabric Loader
      - NeoForge
      - Forge
      - Paper
      - Folia
      - 其他（详情请在"发生了什么？"中补充）
    default: 0
  validations:
    required: true
- type: textarea
  id: what-happened
  attributes:
    label: 发生了什么？
    description: 简单清晰地描述 Bug 的现象。
    placeholder: 告诉我们你看到了什么！
    value: "出现了 Bug！"
  validations:
    required: true
- type: textarea
  id: expected
  attributes:
    label: 期望行为
    description: 你期望发生什么？
    placeholder: 我期望……会发生。
  validations:
    required: true
- type: textarea
  id: steps-to-reproduce
  attributes:
    label: 复现步骤
    description: 触发 Bug 的编号步骤，如果可能请从全新的世界/默认设置开始。
    placeholder: |
      1. 放置一个显示器并打开它的菜单。
      2. ...
      3. ...
    validations:
      required: true
- type: input
  id: gpu-info
  attributes:
    label: 显卡信息
    description: 你使用的是哪款显卡？同时请确保驱动是最新的，以防是驱动问题。（服务器端问题无需填写。）
    placeholder: AMD Radeon RX 6600，驱动 32.0.21045.5002（Adrenalin 26.8.1）
- type: textarea
  id: logs
  attributes:
    label: 相关 Minecraft 日志
    description: 请将日志上传到 pastebin 或 mclo.gs 之类的粘贴站，然后在这里附上链接。如果是与 libvlc 相关的问题，请同时附上 libvlc 日志链接。
- type: upload
  id: screenshots
  attributes:
    label: 上传截图
    description: 如果可以，请附上截图以帮助说明问题。
  validations:
    required: false
- type: checkboxes
  id: confirmations
  attributes:
    label: 确认
    options:
      - label: 我使用的是该 mod 的最新版本。
        required: true
      - label: 我搜索过现有 issues，没有重复。
        required: true