package com.dreamdisplayx.util

import java.util.*

/**
 * Single source of truth for OS / architecture detection.
 */
object OsInfo {
    private val os: String = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
    private val arch: String = System.getProperty("os.arch", "").lowercase(Locale.ENGLISH)

    val isWindows: Boolean = "win" in os
    val isMac: Boolean = "mac" in os

    /** True on Linux and Linux-derived kernels (`"nux"`/`"nix"` in `os.name`); false on BSD/Solaris/other Unix. */
    val isLinux: Boolean = "nux" in os || "nix" in os

    /** True on Android (detected via `os.name` or the Dalvik/ART runtime). */
    val isAndroid: Boolean = "android" in os ||
            System.getProperty("java.vm.name", "").lowercase(Locale.ENGLISH).contains("dalvik") ||
            System.getProperty("java.runtime.name", "").lowercase(Locale.ENGLISH).contains("android")

    /** True on any 64-bit or 32-bit ARM architecture (aarch64, arm64, armv7, ...). */
    val isArm: Boolean = "aarch64" in arch || "arm64" in arch || "arm" in arch

    /** True specifically on 64-bit ARM. */
    val isArm64: Boolean = "aarch64" in arch || "arm64" in arch

    /**
     * The Linux desktop session type, or null when not running under a Linux desktop.
     *
     * `WAYLAND_DISPLAY` wins over `DISPLAY` because a Wayland session often still exposes a
     * `DISPLAY` for XWayland compatibility; that X11 socket only mirrors XWayland windows, not the
     * whole Wayland desktop.
     */
    val linuxSessionType: String?
        get() {
            if (!isLinux) return null
            val waylandDisplay = System.getenv("WAYLAND_DISPLAY")
            if (!waylandDisplay.isNullOrBlank()) return "wayland"
            val sessionType = System.getenv("XDG_SESSION_TYPE")?.lowercase(Locale.ENGLISH)
            if (sessionType == "wayland") return "wayland"
            val display = System.getenv("DISPLAY")
            return if (!display.isNullOrBlank()) "x11" else null
        }
}
