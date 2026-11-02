package com.dreamdisplayx.platform.client.input

import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge between the mod's own [KeyBinding] ids and the vanilla `KeyMapping`s each loader registers.
 *
 * Minecraft's `Options -> Controls` screen only edits `KeyMapping` instances it knows about, and it
 * is the only thing that persists a rebound key. A binding that lives in [KeyBindingRegistry] alone
 * is therefore invisible there and can never be changed by a player — the exact bug this object and
 * [DisplayHotkeys] together fix.
 *
 * Each loader creates one `KeyMapping` per [DisplayHotkeys] entry and hands its press poll here under
 * the same id. Consumers poll by id at tick time instead of caching a key code, because:
 *
 * - `KeyMapping` exposes no public getter for the *currently* bound key (only `saveString()`), so a
 *   raw key code cannot be read back after a rebind; `consumeClick()` is the supported read path.
 * - Polling by closure means a mid-session rebind is picked up on the very next tick.
 *
 * Keeping this object free of Minecraft types is what makes the wiring unit-testable: the platform
 * layer is the only place that can prove `KeyMapping`s exist, but the id routing is pure JVM.
 */
object ModKeyMappings {
    /** Registered press polls by [KeyBinding] id. */
    private val polls = ConcurrentHashMap<String, () -> Boolean>()

    /** Number of attached mappings; `0` means no loader has registered its `KeyMapping`s yet. */
    val size: Int get() = polls.size

    /**
     * Attaches [poll] under [id], replacing any previous one.
     *
     * Replacement rather than accumulation is deliberate: a dev `runClient` relaunch in the same JVM,
     * or a duplicate loader hook, must not stack two pollers onto one binding — otherwise a single
     * press would be reported twice and a play/pause toggle would cancel itself out.
     */
    fun attach(id: String, poll: () -> Boolean) {
        polls[id] = poll
    }

    /** Removes the poll registered under [id], if any. */
    fun detach(id: String) {
        polls.remove(id)
    }

    /**
     * Consumes one press of [id].
     *
     * @return `true` when the binding was pressed since the previous poll; `false` when it was not
     * pressed or when no loader registered it (headless tests, or a platform without Controls
     * integration). Callers must treat `false` as "nothing happened", never as "the key is unbound".
     */
    fun consume(id: String): Boolean = polls[id]?.invoke() ?: false

    /** Whether a loader registered [id]; lets callers skip work for a platform that has no Controls. */
    fun isAttached(id: String): Boolean = polls.containsKey(id)

    /** Ids that currently have a loader-registered mapping. Snapshot, safe to iterate. */
    fun attachedIds(): Set<String> = polls.keys.toSet()

    /** Drops every mapping. Used by tests and on client shutdown. */
    fun reset() {
        polls.clear()
    }
}