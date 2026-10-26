package com.dreamdisplayx.platform.server.utils.net

/**
 * Legacy (1.20.1) stand-in for the NeoForge proxy channel.
 *
 * 1.20.1 is a Fabric-only target ([FabricProxyNetworking] handles the channel), and the original
 * body is `@NeoForgeOnly` against `PayloadRegistrar`.
 */
@Deprecated("NeoForge is not built for 1.20.1")
object NeoForgeProxyNetworking {
    /** No-op on 1.20.1; see the file comment. */
    @Suppress("UNUSED_PARAMETER")
    fun registerReceivers(registrar: Any) = Unit
}