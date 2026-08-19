package com.dreamdisplayx.api.display.service

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.display.event.DisplayEvent
import com.dreamdisplayx.api.display.model.Display
import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.playback.service.PlaybackPort
import com.dreamdisplayx.api.watchparty.service.WatchPartyPort

/**
 * Display system.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
interface DisplaySystem :
    DisplayLookup,
    DisplayMutationPort,
    PlaybackPort,
    WatchPartyPort {
    /** Records a new display in the "system". */
    fun recordDisplay(display: Display)

    /** Removes a display from the "system". */
    fun removeDisplay(id: DisplayId)

    /** Clear displays from the system. */
    fun clearDisplays()

    /** Publishes a display event to all listeners. */
    fun publish(event: DisplayEvent)
}
