package com.dreamdisplays.api.addon

import com.dreamdisplays.api.Unstable

/**
 * Where an addon runs.
 *
 * @since 1.10.x
 */
@Unstable
enum class AddonSide {
    /** In the game client (`Fabric` / `NeoForge`). */
    CLIENT,

    /** On the server (`Paper` plugin). */
    SERVER,

    /** On both; it is enabled wherever `Dream Displays` runs. */
    BOTH,
}

/**
 * Marks a class as a `Dream Displays` addon.
 *
 * Register an instance with `DreamDisplays.register(...)` on the client or
 * `DreamDisplaysServer.register(...)` on the server; the class may also implement [DreamDisplaysAddon].
 * ```
 * @Addon(
 *     id = "my-addon",
 *     name = "My Addon",
 *     version = "1.0.0"
* )
 * class MyAddon {
 *     @OnDisplay
 *     fun track(display: Display) { ... }
 * }
 * ```
 *
 * Addons are experimental until 1.11.0 and may change.
 *
 * @since 1.10.x
 */
@Unstable
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Addon(
    /** Unique ID, lowercase with dashes. */
    val id: String,

    /** Display name; defaults to [id]. */
    val name: String = "",

    /** Addon version text. */
    val version: String = "",

    /** One-line description. */
    val description: String = "",

    /** Where the addon runs. */
    val side: AddonSide = AddonSide.BOTH,
)
