package com.dreamdisplays.platform.client.core

/**
 * Represents a lifecycle event in the client's lifecycle. These events are emitted by the client and can be
 * listened to by services and modules to perform actions at specific points in the client's lifecycle.
 */
sealed interface ClientLifecycleEvent {
    /** Emitted when the client application is initializing. */
    data object Initializing : ClientLifecycleEvent

    /** Emitted when the client application has finished initializing and is ready to run. */
    data object Ready : ClientLifecycleEvent

    /** Signifies that the client has joined a server. */
    data class ServerJoined(val serverId: String) : ClientLifecycleEvent

    /** Signifies that the client has left a server. */
    data class ServerLeft(val serverId: String) : ClientLifecycleEvent

    /** Called when the client is shutting down. */
    data object ShuttingDown : ClientLifecycleEvent

    /** Emitted on every tick of the client's main loop, with the current tick count. */
    data class Tick(val tickCount: Long) : ClientLifecycleEvent
}
