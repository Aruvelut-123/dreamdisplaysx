package com.dreamdisplayx.platform.client.managers

import com.dreamdisplayx.core.protocol.common.packets.PlaylistCommand
import com.dreamdisplayx.core.protocol.common.packets.PlaylistState
import com.dreamdisplayx.platform.client.Initializer

/**
 * Client-side mirror of a display's server playlist plus the command sender. The server is the sole
 * authority: every mutation echoes back as a fresh [PlaylistState] snapshot, which replaces the
 * local mirror wholesale (no client-side reconciliation logic).
 */
object PlaylistStateStore {
    /** Latest snapshot per display UUID. */
    private val states = HashMap<java.util.UUID, PlaylistState>()

    /** The current snapshot for [displayId], or null when the display has none. */
    fun stateOf(displayId: java.util.UUID): PlaylistState? = states[displayId]

    /** Applies a fresh snapshot, replacing any prior state for the display. */
    fun apply(state: PlaylistState) {
        states[state.displayId] = state
    }

    /** Drops the mirror when the display (or the connection) goes away. */
    fun remove(displayId: java.util.UUID) {
        states.remove(displayId)
    }

    /** Sends one queue command for [displayId]; the authoritative echo refreshes the panel. */
    fun send(
        displayId: java.util.UUID,
        action: Int,
        itemId: java.util.UUID? = null,
        position: Int = -1,
        url: String = "",
        lang: String = "",
        title: String = "",
        endBehavior: Int = 0,
        enqueuePolicy: Int = 0,
        enabled: Boolean = true,
    ) {
        Initializer.sendPacket(
            PlaylistCommand(
                displayId = displayId,
                action = action,
                itemId = itemId ?: java.util.UUID(0L, 0L),
                position = position,
                url = url,
                lang = lang,
                title = title,
                endBehavior = endBehavior,
                enqueuePolicy = enqueuePolicy,
                enabled = enabled,
            ),
        )
    }
}
