package com.dreamdisplayx.api.display.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.display.model.property.DisplayBounds
import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.display.model.property.DisplayState
import com.dreamdisplayx.api.display.model.settings.DisplaySettings
import com.dreamdisplayx.api.playback.model.PlaybackMode
import com.dreamdisplayx.api.watchparty.model.WatchPartySession

/**
 * Represents a display that can be rendered on the client.
 *
 * @since 1.0.x
 */
@DreamDisplaysXUnstableApi
data class Display(
    /** The unique identifier of the display. */
    val id: DisplayId,

    /** The bounds of the display. */
    val bounds: DisplayBounds,

    /** The settings for the display. */
    val settings: DisplaySettings,

    /** The URL of the video to display. */
    val url: String?,

    /** The current runtime state of the display. */
    val state: DisplayState,

    /** The effective playback mode (`WATCH_PARTY` while a session is live, otherwise the base mode). */
    val mode: PlaybackMode = PlaybackMode.LOCAL,

    /** The live watch-party session over this display, or null when none is running. */
    val watchParty: WatchPartySession? = null,
) {
    /** Returns true if the display is currently playing. */
    val isPlaying: Boolean get() = state is DisplayState.Playing

    /** Returns true if the display is currently paused. */
    val isPaused: Boolean get() = state is DisplayState.Paused

    /** Returns true if the display is currently idle. */
    val isIdle: Boolean get() = state is DisplayState.Idle

    /** Returns true if the display has a URL set. */
    val hasUrl: Boolean get() = !url.isNullOrBlank()
}
