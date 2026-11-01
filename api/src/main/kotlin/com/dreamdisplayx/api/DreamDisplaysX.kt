package com.dreamdisplayx.api

import com.dreamdisplayx.api.addon.ForkAddon
import com.dreamdisplayx.api.addon.ForkAddonContext
import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.display.service.DisplayService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Entry point for addons written **specifically for `Dream DisplaysX`**.
 *
 * It sits beside upstream's `com.dreamdisplays.api.DreamDisplays`, which this fork also publishes verbatim:
 *
 * | addon written against | registers with | gets |
 * |---|---|---|
 * | upstream `Dream Displays` | `DreamDisplays.register(addon)` | `@Addon`, `AddonLifecycle`, `@OnDisplay` / `@OnPlaying` / `@OnFailed` |
 * | this fork | `DreamDisplaysX.register(addon)` | `@ForkAddon`, `ForkAddonLifecycle`, plus every fork-only handler |
 *
 * A fork addon also gets [ForkAddonContext.service], which hands out any service this side registered — that is the
 * part upstream has no equivalent for, and the reason a fork-only addon can reach `DisplayService`, the stream
 * selector, capability negotiation and so on without touching Minecraft classes.
 *
 * Both kinds of addon live in the same manager, so dependencies work across families: an `@ForkAddon` may list an
 * upstream `@Addon`'s id in its `dependsOn` and be enabled after it.
 *
 * ```kotlin
 * @ForkAddon(id = "example-overlay", name = "My Overlay")
 * class MyOverlay : ForkAddonLifecycle {
 *     override fun onEnable(context: ForkAddonContext) {
 *         val displays = context.service(DisplayService::class.java) ?: return
 *         context.own(displays.on { /* … */ })
 *     }
 * }
 *
 * // during your mod's client init:
 * DreamDisplaysX.register(MyOverlay())
 * ```
 *
 * Registration may happen before the client finished booting: the backend is published later and the addon is
 * enabled at that point. It is disabled again on shutdown; registering the same id twice is a programming error and
 * throws.
 *
 * Not to be confused with [DreamDisplaysXUnstableApi], which marks *unstable* members of the fork's own API.
 *
 * @since 1.10.x
 */
object DreamDisplaysX {

    /**
     * The registration hook, installed by the client bridge module.
     *
     * It is a settable function rather than a direct `ForkAddonManager` reference so the `api` module stays free of
     * `core` — the api artifact must not depend on the implementation that hosts it.
     */
    @Volatile
    private var registrar: ((Any) -> Unit)? = null

    /** Addons queued through [onReady] before the API came up. */
    private val pending = CopyOnWriteArrayList<Runnable>()

    /**
     * Registers [addon], an instance of a class annotated with [ForkAddon] (or upstream's `@Addon`).
     *
     * Throws when the class carries neither annotation, when the id is blank, or when the id is already registered.
     */
    @JvmStatic
    fun register(addon: Any) {
        val sink = registrar ?: error(
            "Dream DisplaysX API is not available yet. Use DreamDisplaysX.onReady { register(MyAddon()) } " +
                "or register from your mod's initialiser.",
        )
        sink(addon)
    }

    /** True once `Dream DisplaysX` published its API and addons can be registered. */
    @JvmStatic
    val isAvailable: Boolean get() = registrar != null

    /** Runs [action] as soon as the API is available: right away if it already is, otherwise once it loads. */
    @JvmStatic
    fun onReady(action: Runnable) {
        if (registrar != null) action.run() else pending += action
    }

    /**
     * Installs the registration hook. Called by the fork's own bridge module, never by an addon.
     *
     * Passing `null` withdraws it again during shutdown. Queued [onReady] actions run as soon as a hook is installed.
     */
    @JvmStatic
    fun installRegistrar(sink: ((Any) -> Unit)?) {
        registrar = sink
        if (sink == null) return
        val queued = pending.toList()
        pending.clear()
        queued.forEach { action -> runCatching { action.run() } }
    }
}

/**
 * Convenience: true when [addon]'s class is annotated with [ForkAddon] or upstream's `@Addon`.
 *
 * Useful for a mod that optionally supports either flavour of this mod's API and wants to warn about a forgotten
 * annotation before calling [DreamDisplaysX.register].
 */
fun isAddon(addon: Any): Boolean =
    addon.javaClass.isAnnotationPresent(ForkAddon::class.java) ||
        addon.javaClass.isAnnotationPresent(com.dreamdisplays.api.addon.Addon::class.java)

/**
 * The `DisplayService` of the running side, or `null` when it is not available.
 *
 * A shortcut for `context.service(DisplayService::class.java)`, kept here so the common case needs no generic dance.
 */
fun ForkAddonContext.displayService(): DisplayService? = service(DisplayService::class.java)

/** The display with [id], or `null` when it does not exist. */
fun ForkAddonContext.display(id: DisplayId) = displayService()?.getDisplay(id)

/** Every display currently known to this side. */
fun ForkAddonContext.displays() = displayService()?.listDisplays().orEmpty()