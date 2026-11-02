package com.dreamdisplayx.core.addon

import com.dreamdisplays.api.server.ServerDisplay
import com.dreamdisplays.api.server.ServerDisplayEvent
import java.util.UUID

/**
 * What a server mutation site calls after it has changed something.
 *
 * `DisplayManager` and the command handlers know nothing about addons; they only know that *a display id* was touched.
 * Passing the id rather than a snapshot is what keeps every call site to one line and one argument: the host resolves
 * the row, so no loader type is ever named outside [ServerAddonHost].
 *
 * Every method returns immediately while nobody listens — one boolean read per mutation for an addon-free server.
 * The event stream speaks upstream's `com.dreamdisplays.*` types, which is what an addon compiled against upstream
 * expects; the host is the only place that translates from this fork's own model.
 *
 * @since 1.10.x
 */
object ServerAddonHooks {
    /** The host installed by the loader, or `null` before it starts. */
    @Volatile
    private var host: ServerAddonHost? = null

    /** Publishes [host]; called once when the server starts, cleared when it stops. */
    fun install(host: ServerAddonHost?) {
        this.host = host
    }

    /** The live host, or `null`. */
    fun host(): ServerAddonHost? = host

    /** Wraps a live [snapshot] as a [ServerDisplay]; `null` when no host is installed. */
    fun view(snapshot: ServerAddonDisplay): ServerDisplay? = host?.let { ServerAddonEvents.view(it, snapshot) }

    /**
     * Publishes a display that was just created.
     *
     * [id] is resolved through the host, which has the row by now.
     */
    fun created(id: UUID) {
        if (!ServerAddonEvents.hasListeners) return
        val snapshot = snapshotOf(id) ?: return
        val display = view(snapshot) ?: return
        ServerAddonEvents.emit(ServerDisplayEvent.Created(display))
    }

    /**
     * Publishes a display that is about to be removed.
     *
     * Call this **before** the removal. Dispatch is synchronous, so the listener runs while the row is still registered
     * and every read answers from it; the snapshot travels along so a listener that keeps the [com.dreamdisplays.api.server.ServerDisplay]
     * after the removal still reads a usable box instead of zeroes.
     */
    fun removed(id: UUID) {
        if (!ServerAddonEvents.hasListeners) return
        val snapshot = snapshotOf(id) ?: return
        val display = view(snapshot) ?: return
        ServerAddonEvents.emit(ServerDisplayEvent.Removed(display))
    }

    /** Publishes a display whose video link changed to [url]. */
    fun urlChanged(id: UUID, url: String) {
        if (!ServerAddonEvents.hasListeners) return
        val snapshot = snapshotOf(id) ?: return
        val display = view(snapshot) ?: return
        ServerAddonEvents.emit(ServerDisplayEvent.UrlChanged(display, url))
    }

    /** Publishes a display that moved to a new box. */
    fun moved(id: UUID) {
        if (!ServerAddonEvents.hasListeners) return
        val snapshot = snapshotOf(id) ?: return
        val display = view(snapshot) ?: return
        ServerAddonEvents.emit(ServerDisplayEvent.Moved(display))
    }

    /** Publishes an addon-data change. Nothing in this fork writes such data, so only a host with a store calls this. */
    fun dataChanged(id: UUID, keys: Set<String>) {
        if (!ServerAddonEvents.hasListeners) return
        val snapshot = snapshotOf(id) ?: return
        val display = view(snapshot) ?: return
        ServerAddonEvents.emit(ServerDisplayEvent.DataChanged(display, keys))
    }

    /** Resolves the live snapshot of [id] through the installed host, or `null`. */
    fun snapshotOf(id: UUID): ServerAddonDisplay? = host?.snapshot()?.firstOrNull { it.id == id }
}