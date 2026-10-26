package com.dreamdisplayx.platform.server.utils.net

/**
 * Legacy (1.20.1) stand-in for the NeoForge v1 detector.
 *
 * 1.20.1 is a Fabric-only target: the LegacyProbe is registered by [FabricV1Detector] and
 * [VanillaNetworking.adapter] is always [FabricNetworkingAdapter], so nothing here is ever called.
 * The original body is `@NeoForgeOnly` and written against `PayloadRegistrar` /
 * `net.neoforged.neoforge.network.*`, none of which exist on a Fabric-only classpath.
 * PlatformWeaver strips those bodies in the IR phase, which is after import resolution, so this
 * declaration has to exist in a NeoForge-free form for the front end to accept it.
 */
@Deprecated("NeoForge is not built for 1.20.1")
object NeoForgeV1Detector {
    /** No-op on 1.20.1; see the file comment. */
    @Suppress("UNUSED_PARAMETER")
    fun registerReceivers(registrar: Any) = Unit
}