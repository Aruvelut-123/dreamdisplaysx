package com.dreamdisplayx.api

import com.dreamdisplayx.api.display.model.Display
import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.display.service.DisplayService
import com.dreamdisplayx.api.runtime.registry.service.ServiceRegistry

/**
 * The fork's additions on top of upstream's addon API.
 *
 * There is **one** kind of addon: a class carrying `com.dreamdisplays.api.addon.Addon`, registered with
 * `com.dreamdisplays.api.DreamDisplays.register(...)`. Everything here works on exactly such a class, so an addon never
 * has to pick a side and both flavours stay interchangeable in one dependency graph.
 *
 * What this fork adds:
 *
 *  * handler annotations an upstream addon has no equivalent for — `@OnQualityChanged`, `@OnVolumeChanged`,
 *    `@OnBrightnessChanged`, `@OnPlaybackPosition`, `@OnStateChanged`, `@OnFirstFrame`, `@OnEnded` (see
 *    `com.dreamdisplayx.api.addon`);
 *  * a service lookup, [service], which hands out any service the running side registered. Upstream's `AddonContext`
 *    only offers `id`, `side` and `own`, so this is the only way an addon reaches `DisplayService` and the rest of
 *    this fork's services without depending on Minecraft classes.
 *
 * ```kotlin
 * @Addon(id = "example-follow-volume")
 * class FollowVolume {
 *     @OnVolumeChanged
 *     fun changed(display: Display, now: Float) {
 *         val displays = DreamDisplaysX.displayService() ?: return
 *         // …
 *     }
 * }
 *
 * DreamDisplays.register(FollowVolume())
 * ```
 *
 * Not to be confused with [DreamDisplaysXUnstableApi], which marks *unstable* members of the fork's own API.
 *
 * @since 1.10.x
 */
object DreamDisplaysX {

    /**
     * Where services are read from, installed by the side that hosts this API.
     *
     * A provider rather than a [ServiceRegistry] so the `api` artifact keeps no `core` dependency and so a registry
     * swapped during a reload is observed immediately.
     */
    @Volatile
    private var registryProvider: (() -> ServiceRegistry?)? = null

    /** True once the hosting side published its services and [service] can return something. */
    @JvmStatic
    val isAvailable: Boolean get() = registryProvider?.invoke() != null

    /**
     * The service bound to [type] on this side, or `null` when nothing is.
     *
     * Returns `null` instead of throwing, so an addon can feature-detect: `DreamDisplaysX.service(X::class.java)
     * ?: return` keeps it loadable on a side that does not have `X`.
     */
    @JvmStatic
    fun <T : Any> service(type: Class<T>): T? = registryProvider?.invoke()?.getOrNull(type)

    /** The `DisplayService` of this side, or `null`. */
    @JvmStatic
    fun displayService(): DisplayService? = service(DisplayService::class.java)

    /** The display with [id], or `null` when it does not exist. */
    @JvmStatic
    fun display(id: DisplayId): Display? = displayService()?.getDisplay(id)

    /** Every display currently known to this side; empty when the service is not there. */
    @JvmStatic
    fun displays(): List<Display> = displayService()?.listDisplays().orEmpty()

    /**
     * Installs the service provider. Called by the hosting bridge, never by an addon.
     *
     * Passing `null` withdraws it again during shutdown, after which [isAvailable] is `false` and [service] returns
     * `null` instead of reaching into a torn-down registry.
     */
    @JvmStatic
    fun installServices(provider: (() -> ServiceRegistry?)?) {
        registryProvider = provider
    }
}

/** The service bound to [T] on this side, or `null` when nothing is. */
@DreamDisplaysXUnstableApi
inline fun <reified T : Any> service(): T? = DreamDisplaysX.service(T::class.java)

/**
 * True when [addon]'s class carries upstream's `@Addon`.
 *
 * Useful for a mod that optionally supports this API and wants to warn about a forgotten annotation before calling
 * `DreamDisplays.register`.
 */
fun isAddon(addon: Any): Boolean =
    addon.javaClass.isAnnotationPresent(com.dreamdisplays.api.addon.Addon::class.java)
