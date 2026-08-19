package com.dreamdisplayx.platform.client.core

import com.dreamdisplayx.api.runtime.module.DreamDisplaysXRuntime

/**
 * Represents the main application for the client.
 */
interface ClientApplication : DreamDisplaysXRuntime {
    /** Context for the application, providing access to state, services, and platform APIs. */
    val context: ClientContext

    /** Emits a lifecycle event to all registered modules. */
    fun emit(event: ClientLifecycleEvent)

    /** Subscribes a listener to lifecycle events. */
    fun onEvent(listener: (ClientLifecycleEvent) -> Unit): AutoCloseable
}
