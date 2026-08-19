package com.dreamdisplayx.api.display.event

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.display.model.Display
import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.display.model.property.DisplayState
import com.dreamdisplayx.api.display.model.settings.DisplaySettings
import com.dreamdisplayx.api.media.model.DreamMediaException

/**
 * Represents an event that occurred on a display.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
sealed interface DisplayEvent {
    /** The ID of the display that the event occurred on. */
    val displayId: DisplayId

    /** Created when a display is created. */
    data class Created(override val displayId: DisplayId, val display: Display) : DisplayEvent

    /** Created when a display is removed. */
    data class Removed(override val displayId: DisplayId) : DisplayEvent

    /** Signifies that the display's settings have been changed. */
    data class SettingsChanged(
        override val displayId: DisplayId,
        val previous: DisplaySettings,
        val current: DisplaySettings,
    ) : DisplayEvent

    /** Signifies that the display's state has changed. */
    data class StateChanged(
        override val displayId: DisplayId,
        val previous: DisplayState,
        val current: DisplayState,
    ) : DisplayEvent

    /** Signifies that the display's URL has changed. */
    data class UrlChanged(override val displayId: DisplayId, val url: String?) : DisplayEvent

    /** Signifies that the display's media has encountered an error. */
    data class MediaError(override val displayId: DisplayId, val cause: DreamMediaException) : DisplayEvent
}
