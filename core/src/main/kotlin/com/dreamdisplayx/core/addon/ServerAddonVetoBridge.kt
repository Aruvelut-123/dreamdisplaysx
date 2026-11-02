package com.dreamdisplayx.core.addon

import com.dreamdisplays.api.server.DisplayVeto
import com.dreamdisplayx.api.display.event.DisplayVeto as ForkVeto
import com.dreamdisplayx.api.display.event.DisplayVetoes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * Bridges an addon's `DreamDisplaysServer.vetoes` listener into this fork's veto registry.
 *
 * The two veto types are unrelated classes: upstream's carries a `ServerDisplay`, the fork's carries a flattened
 * `DisplayVeto.Target` (because a veto decision has to work on both loaders, whose position types never unify). They
 * also mean different things at the call site: upstream's veto **is** the question, while the fork's registry is the
 * one place every gate already consults.
 *
 * So the bridge registers itself into the fork registry rather than beside it:
 *
 * ```
 * gate → forkVeto → [this] → DisplayVeto (upstream) → addon listeners → denied? → forkVeto.deny(...)
 * ```
 *
 * That is what makes an addon refusal behave exactly like a plugin refusal: the gate returns the same denial, so the
 * player sees the same message and an addon request answers `REFUSED`. Installing this beside the registry instead
 * would let an addon veto a change that then happened anyway.
 *
 * The bridge subscribes lazily — the fork registry listener exists only while at least one addon listens, so a server
 * with no addon pays nothing per gate.
 *
 * @param host resolves live rows for the `ServerDisplay` handed to a listener
 * @param vetoes the registry the gates consult, or `null` on a side without one
 *
 * @since 1.10.x
 */
class ServerAddonVetoBridge(
    private val host: ServerAddonHost,
    private val vetoes: () -> DisplayVetoes?,
) {
    /** Listeners to hand over once somebody subscribes; `null` while the bridge is idle. */
    private val listeners = CopyOnWriteArrayList<Consumer<DisplayVeto>>()

    private var registration: AutoCloseable? = null

    /**
     * Subscribes [listener] to every refusable request.
     *
     * Closing the last handle detaches the registry listener again, so the gates go back to skipping the conversion.
     */
    @Synchronized
    fun on(listener: Consumer<DisplayVeto>): AutoCloseable {
        listeners += listener
        if (registration == null) registration = vetoes()?.on(ForkVeto::class.java) { forkVeto -> dispatch(forkVeto) }
        return AutoCloseable {
            remove(listener)
        }
    }

    /** Drops every listener and the registry subscription; used when the server unloads. */
    @Synchronized
    fun clear() {
        listeners.clear()
        registration?.let { runCatching { it.close() } }
        registration = null
    }

    @Synchronized
    private fun remove(listener: Consumer<DisplayVeto>) {
        listeners -= listener
        if (listeners.isEmpty()) {
            registration?.let { runCatching { it.close() } }
            registration = null
        }
    }

    /**
     * Converts [forkVeto] into upstream's shape, asks every addon and mirrors a denial back.
     *
     * A listener that throws is skipped here as well; the registry already logs the failure of the wrapper it calls.
     */
    private fun dispatch(forkVeto: ForkVeto) {
        if (listeners.isEmpty()) return
        val display = ServerAddonEvents.view(host, snapshotOf(forkVeto.target))
        val upstream = when (forkVeto) {
            is ForkVeto.SetUrl -> DisplayVeto.SetUrl(display, forkVeto.url, forkVeto.lang, forkVeto.actor)
            is ForkVeto.Create -> DisplayVeto.Create(display, forkVeto.actor)
            is ForkVeto.Delete -> DisplayVeto.Delete(display, forkVeto.actor)
        }
        listeners.toList().forEach { runCatching { it.accept(upstream) } }
        if (upstream.isDenied) forkVeto.deny(upstream.denial ?: "")
    }

    /**
     * Rebuilds the snapshot a `Target` was flattened from.
     *
     * Fields a target never carried (lang, mode, access) are left at their neutral values: an addon that needs them
     * re-reads the display by id, which the view supports while the display still exists.
     */
    private fun snapshotOf(target: ForkVeto.Target): ServerAddonDisplay {
        val place = target.position
        return ServerAddonDisplay(
            id = target.id,
            ownerId = target.ownerId,
            world = place?.world ?: "",
            x1 = place?.x1 ?: 0,
            y1 = place?.y1 ?: 0,
            z1 = place?.z1 ?: 0,
            x2 = place?.x2 ?: 0,
            y2 = place?.y2 ?: 0,
            z2 = place?.z2 ?: 0,
            facing = place?.facing?.let { com.dreamdisplays.api.display.model.property.DisplayFacing.fromByte(it.byte) }
                ?: com.dreamdisplays.api.display.model.property.DisplayFacing.NORTH,
            width = target.width,
            height = target.height,
            name = target.name,
            url = target.url,
            lang = "",
            mode = com.dreamdisplays.api.playback.model.PlaybackMode.LOCAL,
            access = com.dreamdisplays.api.playback.model.DisplayAccess.DEFAULT,
        )
    }
}