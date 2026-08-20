package com.dreamdisplayx.api.display.model.settings

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import kotlinx.serialization.Serializable

/**
 * Client-local preferences: volume, quality, mute, URL / language overrides, PiP position, acoustics.
 *
 * @since 1.0.x
 */
@DreamDisplaysXUnstableApi
@Serializable
data class ClientDisplaySettings(
    /** Volume in the range [0.0, 1.0]. */
    var volume: Float = DEFAULT_VOLUME,

    /** Video quality, e.g. "720" or "1080". */
    var quality: String = "1080",

    /** Brightness in the range [0.0, 2.0]. */
    var brightness: Float = 1.0f,

    /** Whether the display is muted. */
    var muted: Boolean = false,

    /** Whether the display is paused. */
    var paused: Boolean = true,

    /** URL override for the video, or null if not overridden. */
    var urlOverride: String? = null,

    /** Language override for the video, or null if not overridden. */
    var langOverride: String? = null,

    /** Last known playback position in nanoseconds, resumed on Local displays after a restart. */
    var savedTimeNanos: Long = 0,

    /** Viewer-chosen render distance in blocks, or `0` if never customized (falls back to the config default). */
    var renderDistance: Int = 0,

    /** Whether the viewer pinned this display to a Picture-in-Picture overlay; re-opened on rejoin regardless of render distance. */
    var pipOpen: Boolean = false,

    /** Picture-in-Picture anchor point. */
    var pipAnchor: String? = null,

    /** Height of the PiP as a fraction of the screen, or `0` when the viewer never resized it. */
    var pipSizeFraction: Float = 0f,

    /** Whether the 3D acoustics engine applies to this display; false forces the legacy distance-gain-only path. */
    var acousticsEnabled: Boolean = true,

    // ── Danmaku settings ──────────────────────────────────────────────────────────────────────────

    /** Whether danmaku (bullet comments) is enabled for this display. */
    var danmakuEnabled: Boolean = true,

    /** Danmaku opacity in the range [0.0, 1.0]. 0.0 = fully transparent, 1.0 = fully opaque. */
    var danmakuOpacity: Float = 0.8f,

    /** Danmaku font size multiplier in the range [0.5, 2.0]. 1.0 = default. */
    var danmakuFontSize: Float = 1.0f,

    /** Danmaku scroll speed multiplier in the range [0.5, 2.0]. 1.0 = default. */
    var danmakuSpeed: Float = 1.0f,

    /** Fraction of the display height used for danmaku, in the range [0.0, 1.0]. 0.5 = bottom half. */
    var danmakuDisplayArea: Float = 0.5f,

    /** Whether scrolling danmaku (mode 1/2/3) is shown. */
    var danmakuFilterScroll: Boolean = true,

    /** Whether top-fixed danmaku (mode 5) is shown. */
    var danmakuFilterTop: Boolean = true,

    /** Whether bottom-fixed danmaku (mode 4) is shown. */
    var danmakuFilterBottom: Boolean = true,

    /** Whether coloured danmaku is shown. */
    var danmakuFilterColor: Boolean = true,
) {

    companion object {
        /** Default volume for all displays. The UI presents this as 50% (slider range is [0, 1] -> [0%, 200%]). */
        const val DEFAULT_VOLUME = 0.25f
    }
}
