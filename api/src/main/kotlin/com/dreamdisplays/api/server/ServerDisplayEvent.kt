package com.dreamdisplays.api.server

import com.dreamdisplays.api.Unstable

/**
 * Things that happen to displays on the server.
 *
 * @since 1.10.x
 */
@Unstable
sealed interface ServerDisplayEvent {
    /** The display the event is about. */
    val display: ServerDisplay

    /** A display was created. */
    data class Created(override val display: ServerDisplay) : ServerDisplayEvent

    /** A display was removed. */
    data class Removed(override val display: ServerDisplay) : ServerDisplayEvent

    /** A display's video link changed to [url]. */
    data class UrlChanged(override val display: ServerDisplay, val url: String) : ServerDisplayEvent
}
