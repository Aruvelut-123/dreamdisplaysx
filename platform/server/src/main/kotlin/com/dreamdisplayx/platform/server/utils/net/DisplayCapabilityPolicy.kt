package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.core.protocol.common.packets.ClientHello

/**
 * Applies viewer capability negotiation to display geometry without hiding the display from legacy clients.
 * A peer that has not sent a hello, or sent an older hello without field 29, receives the flat fallback.
 */
internal object DisplayCapabilityPolicy {
    /** Returns whether [displayConforming] may be sent to a viewer with [hello]. */
    fun conformingFor(displayConforming: Boolean, hello: ClientHello?): Boolean =
        conformingFor(displayConforming, hello?.supportsConforming == true)

    /** Applies an already-normalized viewer capability flag. */
    fun conformingFor(displayConforming: Boolean, supportsConforming: Boolean): Boolean =
        displayConforming && supportsConforming
}
