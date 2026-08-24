@file:Suppress("Since15")

package com.dreamdisplayx.media.player.process

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import org.slf4j.LoggerFactory

/**
 * Lists the FFmpeg hardware-accelerated decoder backends available at runtime.
 * The enumeration is cached after the first successful call so it is safe to
 * query from the config screen (which may open before the first media player
 * session).
 */
object HwAccelEnumerator {

    private val logger = LoggerFactory.getLogger("DreamDisplaysX/HwAccelEnumerator")

    /**
     * FFmpeg hwaccel backend names (lowercase) that the running FFmpeg build
     * was compiled with and has a device type registered for.  Empty when the
     * native library could not be loaded or the API is unavailable.
     */
    @Volatile
    private var cached: List<String>? = null

    /**
     * Returns the list of available FFmpeg hwaccel backend names (e.g. "cuda",
     * "qsv", "vaapi", "videotoolbox", "vulkan", "d3d11va", "amf", …).
     * Names are lowercased, deduplicated, and sorted alphabetically.
     */
    fun availableBackends(): List<String> {
        cached?.let { return it }

        val names = try {
            enumerate()
        } catch (e: Throwable) {
            logger.warn("Failed to enumerate FFmpeg hwaccel backends (native lib not loaded yet?): {}", e.message)
            emptyList()
        }
        cached = names
        return names
    }

    /**
     * True when the FFmpeg build supports the given [hwaccel] name (e.g. "cuda").
     */
    fun isSupported(hwaccel: String): Boolean {
        val lower = hwaccel.lowercase()
        return availableBackends().any { it == lower }
    }

    // ── Private helpers ────────────────────────────────────────────────

    private fun enumerate(): List<String> {
        val names = mutableSetOf<String>()
        var type = avutil.AV_HWDEVICE_TYPE_NONE
        while (true) {
            type = avutil.av_hwdevice_iterate_types(type)
            if (type == avutil.AV_HWDEVICE_TYPE_NONE) break
            val ptr = avutil.av_hwdevice_get_type_name(type) ?: continue
            val name = try {
                ptr.getString()?.lowercase()
            } catch (_: Exception) { null }
            if (name != null && name.isNotEmpty()) {
                names += name
            }
        }
        return names.toList().sorted()
    }
}