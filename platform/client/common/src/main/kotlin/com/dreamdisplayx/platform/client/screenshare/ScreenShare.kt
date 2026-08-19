package com.dreamdisplayx.platform.client.screenshare

import com.dreamdisplayx.util.OsInfo

/**
 * Client-side screen sharing: captures the user's screen and pushes it as an RTMP stream through
 * the bundled FFmpeg binary (the BtbN GPL builds ship `libx264` plus the `x11grab` / `gdigrab` /
 * `avfoundation` capture devices).
 *
 * Supported capture backends:
 * - Windows: `gdigrab` desktop duplication.
 * - macOS: `avfoundation` (first capture device).
 * - Linux X11: `x11grab` against `$DISPLAY`.
 * - Linux Wayland: `x11grab` against the XWayland `$DISPLAY` (Minecraft itself runs on XWayland, so
 *   the game window and other XWayland apps are capturable). A pure-Wayland session with no XWayland
 *   socket is rejected with a clear message rather than silently capturing nothing.
 * - Android: deliberately unsupported — a JVM mod has no OS-level screen-capture path there.
 */
object ScreenShare {
    /** Why screen sharing is unavailable on this platform, or null when it can be started. */
    fun unsupportedReason(): String? = when {
        OsInfo.isAndroid ->
            "Screen sharing is not available on Android."
        OsInfo.isWindows -> null
        OsInfo.isMac -> null
        OsInfo.isLinux -> when (OsInfo.linuxSessionType) {
            "x11" -> null
            "wayland" -> if (x11Display() != null) {
                null
            } else {
                "Wayland screen sharing needs XWayland; no DISPLAY is available."
            }
            else -> "Screen sharing needs an X11 or Wayland Linux session."
        }
        else -> "Screen sharing is not supported on this operating system."
    }

    /** The X11 display to capture, or null when none is available. */
    fun x11Display(): String? = System.getenv("DISPLAY")?.takeIf { it.isNotBlank() }

    /**
     * Builds the FFmpeg argv that captures the screen and pushes it to [rtmpUrl].
     *
     * When [width]/[height] are both positive the capture is cropped to that size; otherwise the
     * whole screen is captured. [fps] is the capture and output frame rate.
     */
    fun buildCommand(
        ffmpeg: String,
        rtmpUrl: String,
        fps: Int = 30,
        width: Int = 0,
        height: Int = 0,
    ): List<String> {
        val cmd = mutableListOf(ffmpeg, "-hide_banner", "-loglevel", "warning", "-nostats")
        val sizeArgs = if (width > 0 && height > 0) listOf("-video_size", "${width}x$height") else emptyList()

        when {
            OsInfo.isWindows -> {
                cmd += listOf("-f", "gdigrab", "-framerate", fps.toString())
                cmd += sizeArgs
                cmd += listOf("-i", "desktop")
            }

            OsInfo.isMac -> {
                cmd += listOf("-f", "avfoundation", "-framerate", fps.toString())
                cmd += sizeArgs
                cmd += listOf("-i", "1:none")
            }

            else -> {
                val display = x11Display() ?: throw IllegalStateException("No X11 DISPLAY available.")
                cmd += listOf("-f", "x11grab", "-framerate", fps.toString())
                cmd += sizeArgs
                cmd += listOf("-i", display)
            }
        }

        cmd += listOf(
            "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
            "-pix_fmt", "yuv420p", "-g", (fps * 2).toString(),
            "-b:v", "2500k", "-maxrate", "2500k", "-bufsize", "5000k",
            "-f", "flv", rtmpUrl,
        )
        return cmd
    }
}
