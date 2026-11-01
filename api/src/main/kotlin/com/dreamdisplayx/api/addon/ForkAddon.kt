package com.dreamdisplayx.api.addon

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.platform.identity.PlatformSide

/**
 * Marks a class as a `Dream DisplaysX` addon.
 *
 * This is the fork's own annotation. It sits beside — not on top of — upstream's `com.dreamdisplays.api.addon.Addon`
 * so that an addon compiled against upstream keeps working untouched, while an addon written for this fork gets the
 * richer handlers below and direct access to the fork's own services through [ForkAddonContext.service].
 *
 * An addon may carry *either* annotation, never both: the same class registered under two ids would be
 * indistinguishable in logs and dependency graphs.
 *
 * ```kotlin
 * @ForkAddon(id = "example-quality-watch", name = "Quality Watch")
 * class QualityWatch : ForkAddonLifecycle {
 *     override fun onEnable(context: ForkAddonContext) {
 *         context.own(ForkAddons.onQualityChanged { display, quality ->
 *             context.logger.info("${display.id} now plays at $quality")
 *         })
 *     }
 * }
 * ```
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
annotation class ForkAddon(
    /** Unique id, lower-case and hyphenated by convention (`example-quality-watch`). Required. */
    val id: String,
    /** Human-readable name, shown in logs. */
    val name: String = "",
    /** Addon version, shown in logs. */
    val version: String = "",
    /** One-line description of what the addon does. */
    val description: String = "",
    /** Side(s) this addon runs on; [PlatformSide.BOTH] is enabled on both. */
    val side: PlatformSide = PlatformSide.BOTH,
    /** Ids of addons that must be enabled before this one. */
    val dependsOn: Array<String> = [],
)

/**
 * Lifecycle of a [ForkAddon].
 *
 * [onEnable] runs once the addon is enabled; everything it subscribes to must be handed to
 * [ForkAddonContext.own] so it is closed again in reverse order when the addon is disabled.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
interface ForkAddonLifecycle {
    /** Called when the addon is enabled. Subscribe with [ForkAddonContext.own] so teardown is automatic. */
    fun onEnable(context: ForkAddonContext)

    /** Called when the addon is disabled, after every resource it owns has been closed. */
    fun onDisable() {}
}

/**
 * Per-addon handle passed to [ForkAddonLifecycle.onEnable].
 *
 * It is a superset of upstream's `AddonContext`: besides [id], [side] and [own] it can hand out any service the
 * running client registered, which is how a fork-only addon reaches things upstream has no concept of (stream
 * selection, capability negotiation, display interaction, …). [service] returns `null` instead of throwing when the
 * service is absent, so an addon can feature-detect and stay loadable on a server-side or reduced client.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
interface ForkAddonContext {
    /** Id of the addon this context belongs to. */
    val id: String

    /** Side this context lives on. */
    val side: PlatformSide

    /** Registers [resource] for automatic closing when the addon is disabled. */
    fun <T : AutoCloseable> own(resource: T): T

    /**
     * The service bound to [type] in this side's registry, or `null` when nothing is bound.
     *
     * Use it for fork-only services, for example
     * `context.service(DisplayInteractionService::class.java)`.
     */
    fun <T : Any> service(type: Class<T>): T?

    /** Logs through the platform logger with this addon's id as a prefix. */
    fun info(message: String)

    /** Logs a warning through the platform logger with this addon's id as a prefix. */
    fun warn(message: String)

    /** Logs an error through the platform logger with this addon's id as a prefix. */
    fun error(message: String, cause: Throwable? = null)
}

/** The service bound to [T], or `null` when nothing is bound. */
@DreamDisplaysXUnstableApi
inline fun <reified T : Any> ForkAddonContext.service(): T? = service(T::class.java)