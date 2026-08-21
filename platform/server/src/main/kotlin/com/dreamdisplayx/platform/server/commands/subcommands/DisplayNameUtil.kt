package com.dreamdisplayx.platform.server.commands.subcommands

import java.util.UUID

/**
 * Canonical, storage-safe form of a display name, or null when it can't be used as a display name.
 *
 * A display name is a human-readable label (spaces and non-Latin script allowed), distinct from the
 * display's unique [UUID] id which is what commands use to address a display. Rejected shapes: blank /
 * control-character names, names that look like a bare UUID, and the reserved keyword `this`.
 */
private val DISPLAY_NAME_MAX_LENGTH = 64
private val DISPLAY_NAME_CONTROL_CHAR = Regex("[\\p{Cc}\\p{Cf}]")

internal fun normalizeDisplayName(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    if (trimmed.length > DISPLAY_NAME_MAX_LENGTH) return null
    if (DISPLAY_NAME_CONTROL_CHAR.containsMatchIn(trimmed)) return null
    if (trimmed.equals("this", ignoreCase = true)) return null
    if (runCatching { UUID.fromString(trimmed) }.isSuccess) return null
    return trimmed
}
