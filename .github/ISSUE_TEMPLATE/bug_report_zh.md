---
name: Bug 报告
about: 报告一个 Bug，帮助我们改进
title: ''
labels: "type: bug"
assignees: Aruvelut-123

---
body:
  - type: markdown
    attributes:
      value: |
        **提交前请确认** —— 感谢你花时间上报问题！请确保：
        1. 你使用的是**最新版本**（见 [CHANGELOG.md](../../CHANGELOG.md)）；很多 Bug 已经修复。
        2. 你已经**搜索过现有 issues**（含已关闭的），确认没有重复报告。
        3. 你准备好附上**日志**——对排查播放器问题来说，日志是最有用的一样东西。

        **日志在哪里？**
        - 客户端（Fabric / NeoForge）：`.minecraft/logs/latest.log`（若崩溃还有 `crash-reports/`）
        - 服务器（Paper）：`logs/latest.log`（崩溃文件在 `crash-reports/`）
  - type: input
    id: mod-version
    attributes:
      label: Mod 版本
      description: Dream DisplaysX 的版本号，例如 `1.10.0.4`（见 CHANGELOG.md 或 mods 文件夹）。
    validations:
      required: true
  - type: input
    id: mc-version
    attributes:
      label: Minecraft 版本
      description: 例如 `1.21.1`、`1.21.11`、`26.2`、`26.3`。
    validations:
      required: true
  - type: dropdown
    id: loader
    attributes:
      label: 加载器
      options:
        - Fabric
        - NeoForge
        - 仅 Paper 服务器
        - 其他 / 不确定
    validations:
      required: true
  - type: dropdown
    id: area
    attributes:
      label: 遇到的问题涉及哪些功能？
      multiple: true
      options:
        - 播放（视频/音频、拖动、快进预览）
        - 搜索 / 粘贴链接
        - 显示器菜单（设置、滑块、快捷键）
        - 播放列表 / 队列
        - 缩略图 / 首帧预览
        - 弹幕叠加
        - 曲面显示器（台阶 / 楼梯）
        - 网络 / 多显示器同步
        - 其他
    validations:
      required: true
  - type: textarea
    id: description
    attributes:
      label: Bug 描述
      description: 简单清晰地描述问题现象。
      placeholder: 当我……时，出现……而不是……
    validations:
      required: true
  - type: textarea
    id: expected
    attributes:
      label: 期望行为
      description: 你希望发生什么？
  - type: textarea
    id: repro
    attributes:
      label: 复现步骤
      description: 用编号列出步骤，以便从干净状态复现该问题。
      placeholder: |
        1. 放置显示器并打开菜单。
        2. ...
        3. ...
      render: markdown
  - type: textarea
    id: logs
    attributes:
      label: 日志
      description: |
        粘贴 `latest.log` 中与 bug 相关的段落（警告/错误），或直接上传日志文件。
        播放器相关的 bug 特别依赖日志 —— 原生 libvlc 的错误都会出现在这里。
      render: shell
  - type: textarea
    id: media
    attributes:
      label: 截图 / 视频
      description: 如果方便，附上截图或短录屏（例如显示器出错的画面）。
  - type: input
    id: env
    attributes:
      label: 运行环境
      description: 操作系统及版本、GPU 型号、JDK 版本（涉及硬件解码/性能问题时较为重要）。
      placeholder: Windows 11、NVIDIA RTX 3060、Temurin JDK 21
  - type: checkboxes
    id: confirmations
    attributes:
      label: 确认
      options:
        - label: 我使用的是该 mod 的最新版本。
          required: true
        - label: 我搜索过现有 issues，没有重复。
          required: true
        - label: 我能在默认设置下复现（若改动过设置我会在描述中注明）。