package com.dreamdisplayx.platform.server.utils

import com.dreamdisplayx.util.Version

/**
 * Parses client-supplied mod / plugin version strings into a [Version], bounded against hostile
 * input. The version comes straight off the network with no length cap from the decoder, so this
 * enforces one itself before any regex work runs.
 */
object VersionUtil {
    /** Comfortably above any real version string; caps the work a single malformed packet can trigger. */
    private const val MAX_VERSION_LENGTH = 64

    /** Regex for disallowed characters in version strings. */
    private val DISALLOWED_CHARS = "[^0-9A-Za-z+.-]".toRegex()

    /** Returns the parsed [Version], or null if [raw] is empty, oversized, or not parseable. */
    fun parseOrNull(raw: String): Version? {
        if (raw.isEmpty() || raw.length > MAX_VERSION_LENGTH) return null
        val sanitized = raw.trim().replace(DISALLOWED_CHARS, "").takeIf { it.isNotEmpty() } ?: return null
        return Version.parse(sanitized)
    }
}
