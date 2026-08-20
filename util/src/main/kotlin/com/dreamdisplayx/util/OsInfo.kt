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

    /**
     * True on Android.
     *
     * `os.name` is "Linux" and the JVM is a full OpenJDK (not Dalvik/ART) under Android launchers
     * like PojavLauncher / FCL / Zalith, so no single property is reliable. We combine signals:
     *  1. explicit "android"/"dalvik" markers in runtime property names;
     *  2. Android system environment variables (`ANDROID_ROOT`+`ANDROID_DATA` are always set in
     *     app processes, including a game JVM spawned by such a launcher);
     *  3. the Android `build.prop` file on a real device;
     *  4. path fingerprints of emulated/internal storage (`/data/user/0/`, `/storage/emulated/`,
     *     `/Android/data/`) in `user.dir` / `java.home` / `java.io.tmpdir`, which launcher game
     *     directories (`/storage/emulated/0/Android/data/<pkg>/files/...`) always carry.
     */
    val isAndroid: Boolean = run {
        val vmName = System.getProperty("java.vm.name", "").lowercase(Locale.ENGLISH)
        val runtimeName = System.getProperty("java.runtime.name", "").lowercase(Locale.ENGLISH)
        if ("android" in os || "dalvik" in vmName || "android" in runtimeName) return@run true
        val androot = System.getenv("ANDROID_ROOT")
        val anddata = System.getenv("ANDROID_DATA")
        if (androot != null && anddata != null) return@run true
        if (java.io.File("/system/build.prop").exists()) return@run true
        listOf("user.dir", "java.home", "java.io.tmpdir")
            .map { System.getProperty(it, "") }
            .any { it.contains("/Android/data/") || it.contains("/data/user/0/") || it.contains("/storage/emulated/") }
    }

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
