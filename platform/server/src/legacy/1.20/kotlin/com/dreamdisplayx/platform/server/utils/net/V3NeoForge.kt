package com.dreamdisplayx.platform.server.utils.net

/**
 * Legacy (1.20.1) stand-in for the NeoForge v3 transport.
 *
 * 1.20.1 is a Fabric-only target (v3 goes through [FabricV3Networking]), and the original body is
 * `@NeoForgeOnly` against `PayloadRegistrar`, which a Fabric-only classpath does not carry.
 */
@Deprecated("NeoForge is not built for 1.20.1")
object NeoForgeV3Networking {
    /** No-op on 1.20.1; see the file comment. */
    @Suppress("UNUSED_PARAMETER")
    fun registerReceivers(registrar: Any) = Unit
}