package com.dreamdisplayx.api.media.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * How a video frame is scaled to fit the display's render resolution — the same choices
 * `ffmpeg -vf scale` offers (`force_original_aspect_ratio=decrease` + `pad` for letterbox,
 * `=increase` + `crop` for center-crop).
 *
 * @since 1.10.0
 */
@DreamDisplaysXUnstableApi
enum class StretchMode {
    /** Scale the source to exactly the display's resolution, ignoring aspect ratio (may distort). */
    STRETCH,

    /** Scale to fit inside the display, keeping the source aspect ratio, and pad the remaining area with black bars. */
    LETTERBOX,

    /** Scale to cover the whole display, keeping the source aspect ratio, and crop the overflowing edges. */
    CROP;

    companion object {
        /** Parses a wire / config value (`letterbox`, `LETTERBOX`, `crop`, ...), falling back to [LETTERBOX]. */
        fun parse(value: String?): StretchMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LETTERBOX

        /** Lower-case wire value used in config files and the protocol. */
        val StretchMode.wire: String get() = name.lowercase()
    }
}