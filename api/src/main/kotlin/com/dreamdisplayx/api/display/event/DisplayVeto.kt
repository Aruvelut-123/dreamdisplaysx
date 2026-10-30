package com.dreamdisplayx.api.display.event

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import java.util.UUID
import java.util.function.Consumer

/**
 * A change somebody asked for, on a display, that a listener may refuse before it happens.
 *
 * Nothing has changed yet when it is delivered; call [deny] to stop it.
 *
 * Example:
 * ```
 * ServerVetoes.registry.on<DisplayVeto.SetUrl> { veto ->
 *     if (!veto.url.startsWith("https://")) veto.deny("Only https links are allowed here.")
 * }
 * ```
 *
 * Listeners run on the server thread before the change, so keep them quick. A listener that throws
 * is logged and does not deny. The requester sees the reason of the first denial.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
sealed class DisplayVeto {
    /** The display the request targets. */
    abstract val target: Target

    /** The player who asked, or `null` when the request came from an addon or the API. */
    abstract val actor: UUID?

    /** The reason of the first denial (may be empty), or `null` while nobody denied. */
    var denial: String? = null
        private set

    /** `true` once a listener denied the request. */
    val isDenied: Boolean get() = denial != null

    /** Refuses the request. [reason] is shown to the requester; the first denial wins. */
    fun deny(reason: String = "") {
        if (denial == null) denial = reason
    }

    /**
     * The display a veto is about, flattened so veto listeners stay platform independent.
     *
     * The world position and facing are deliberately absent: a veto decision is about identity,
     * ownership and content, and the position types differ per loader.
     *
     * @since 1.10.x
     */
    @DreamDisplaysXUnstableApi
    data class Target(
        /** The display identifier. */
        val id: UUID,
        /** The owner's identifier. */
        val ownerId: UUID,
        /** The display's width in blocks. */
        val width: Int,
        /** The display's height in blocks. */
        val height: Int,
        /** The display's URL, as it is right now (before the pending change). */
        val url: String,
        /** The optional, space-free alias, or `null` when unnamed. */
        val name: String?,
        /** `true` for the synthetic displays backing a URL-only fullscreen broadcast. */
        val virtual: Boolean,
    )

    /**
     * A player or an addon wants to change [target]'s video link to [url].
     *
     * [target] still holds the previous URL.
     */
    class SetUrl(
        override val target: Target,
        val url: String,
        val lang: String,
        override val actor: UUID?,
    ) : DisplayVeto()

    /**
     * A display is about to be created. [target] is the candidate: it already carries its owner,
     * size and URL, but it is not registered yet.
     */
    class Create(
        override val target: Target,
        override val actor: UUID?,
    ) : DisplayVeto()

    /** A player or an addon wants to remove [target]. */
    class Delete(
        override val target: Target,
        override val actor: UUID?,
    ) : DisplayVeto()
}

/**
 * Listen to requests that can still be refused.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
interface DisplayVetoes {
    /**
     * Calls [listener] before every request of [type] (or a subtype).
     *
     * Close the returned handle to stop.
     */
    fun <T : DisplayVeto> on(type: Class<T>, listener: Consumer<T>): AutoCloseable
}

/**
 * Listens to requests of type [T] and lets you refuse them.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
inline fun <reified T : DisplayVeto> DisplayVetoes.on(crossinline action: (T) -> Unit): AutoCloseable =
    on(T::class.java, Consumer { action(it) })

/**
 * Collects the registered [DisplayVeto] listeners and asks them about pending changes.
 *
 * The server call sites invoke [ask] right before they mutate anything; a `null` result means the
 * change may proceed, otherwise the returned string is the denial reason (possibly empty).
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
class DisplayVetoRegistry : DisplayVetoes {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Consumer<DisplayVeto>>()

    /** `true` when nobody listens, so call sites can skip building the event entirely. */
    val isEmpty: Boolean get() = listeners.isEmpty()

    override fun <T : DisplayVeto> on(type: Class<T>, listener: Consumer<T>): AutoCloseable {
        val typed = Consumer<DisplayVeto> { veto -> if (type.isInstance(veto)) listener.accept(type.cast(veto)) }
        listeners += typed
        return AutoCloseable { listeners -= typed }
    }

    /**
     * Asks every listener about [veto] and reports the first denial.
     *
     * @return `null` when allowed, otherwise the denial reason (possibly empty).
     */
    fun ask(veto: DisplayVeto, onListenerFailure: (Throwable) -> Unit = {}): String? {
        listeners.forEach { listener ->
            runCatching { listener.accept(veto) }.onFailure(onListenerFailure)
        }
        return veto.denial
    }

    /** Removes every listener; used when a server unloads. */
    fun clear() {
        listeners.clear()
    }
}