package com.dreamdisplayx.core.addon

import com.dreamdisplays.api.server.ServerDisplay
import com.dreamdisplays.api.server.ServerDisplayEvent
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * Fan-out of server display events to addon listeners.
 *
 * The server keeps no event bus of its own: `DisplayManager` mutates and broadcasts packets, nothing more. Rather than
 * grow a second notification subsystem, the mutation sites call [ServerAddonHooks] and the host's `listen` subscribes
 * here too. An event carries a live [ServerDisplay] view, not a stale copy, so a listener re-reading the display sees
 * the current row.
 *
 * Dispatch is synchronous on the caller's thread — the server thread for display mutations, which is where an addon may
 * safely touch its own state. A throwing listener is logged and skipped: one broken addon must not abort the remaining
 * listeners or the mutation that triggered them.
 *
 * @since 1.10.x
 */
object ServerAddonEvents {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(ServerAddonEvents::class.java)

    private val listeners = CopyOnWriteArrayList<Consumer<ServerDisplayEvent>>()

    /** `true` while somebody listens; lets a call site skip building an event nobody will see. */
    val hasListeners: Boolean get() = listeners.isNotEmpty()

    /** Subscribes [listener]; close the handle to stop. */
    fun on(listener: Consumer<ServerDisplayEvent>): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Delivers [event] to every listener, isolating failures. */
    fun emit(event: ServerDisplayEvent) {
        if (listeners.isEmpty()) return
        listeners.forEach { listener ->
            runCatching { listener.accept(event) }
                .onFailure { logger.error("A server addon listener threw while handling {}.", event::class.simpleName, it) }
        }
    }

    /**
     * Wraps [snapshot] as a [ServerDisplay] without a host round-trip.
     *
     * The returned view reads the live row while the display exists and answers from [snapshot] once it is gone.
     * Dispatch is synchronous, so during the `Removed` event the row is still there; an addon that keeps the display
     * and re-reads it after the removal still gets the box it was told about instead of zeroes.
     */
    fun view(host: ServerAddonHost, snapshot: ServerAddonDisplay): ServerDisplay =
        ServerAddonDisplayView(host, snapshot.id, snapshot)

    /** Drops every listener; used when a server unloads so a reload starts clean. */
    fun clear() {
        listeners.clear()
    }
}
