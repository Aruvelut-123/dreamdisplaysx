package com.dreamdisplays.platform.client.core

/**
 * Mutable state of the client, which can be updated by modules and observed by other modules.
 */
interface ClientMutableState : ClientState {
    override var isOnScreen: Boolean

    override var displaysEnabled: Boolean

    override var isPremium: Boolean

    override var isAdmin: Boolean

    override var isReportingEnabled: Boolean

    override var connectedServerId: String?
}
