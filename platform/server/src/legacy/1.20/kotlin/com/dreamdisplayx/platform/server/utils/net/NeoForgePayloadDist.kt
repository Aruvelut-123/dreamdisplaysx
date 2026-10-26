package com.dreamdisplayx.platform.server.utils.net

/**
 * Legacy (1.20.1) stand-in for the NeoForge payload-distribution helpers.
 *
 * Everything here is `@NeoForgeOnly` and built on `net.neoforged.neoforge.network.*`, which a
 * Fabric-only target neither has on its classpath nor ever calls: [VanillaNetworking] picks
 * [FabricNetworkingAdapter] there. PlatformWeaver deletes the bodies in the IR phase, too late to
 * rescue the imports, so the declarations have to exist in a form the front end can resolve.
 */
@Deprecated("NeoForge is not built for 1.20.1")
@Suppress("UNUSED_PARAMETER")
val isClientDist: Boolean = false
