package com.dreamdisplayx.platform.client

/**
 * Declarative description of one editable configuration field. The config screen (and other tooling)
 * renders a control matching [type] and reads/writes the value through [get]/[apply], so new fields
 * surface in the UI automatically without hand-writing a screen per field.
 *
 * @param key TOML key / config name (e.g. `danmaku-enabled`).
 * @param label User-facing short label for the row.
 * @param comment Human-readable explanation shown under the control (like a Configured tooltip).
 * @param type The control kind to render.
 * @param values For [ConfigEntryType.ENUM], the allowed values in display order; otherwise empty.
 * @param get Reads the current value.
 * @param apply Writes a new value (and persists).
 */
class ConfigEntry<T>(
    val key: String,
    val label: String,
    val comment: String,
    val type: ConfigEntryType,
    val values: List<T> = emptyList(),
    val get: () -> T,
    val apply: (T) -> Unit,
)

/** Kinds of config controls the screen can render. */
enum class ConfigEntryType {
    BOOLEAN,
    INT,
    DOUBLE,
    ENUM,
    STRING,
}
