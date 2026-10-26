package com.dreamdisplayx.platform.server.utils.net

import net.minecraft.server.level.ServerPlayer

/**
 * Legacy (1.20.1) stand-in for the NeoForge v2 transport.
 *
 * 1.20.1 is a Fabric-only target, where v2 traffic goes through [FabricV2Networking] and
 * [VanillaNetworking.adapter] is always [FabricNetworkingAdapter]; nothing calls this object.
 * The original is `@NeoForgeOnly` and imports `PayloadRegistrar` and
 * `net.neoforged.neoforge.network.*`, which are absent from a Fabric-only classpath, so the
 * declaration is restated here in NeoForge-free terms for the front end to resolve.
 */
@Deprecated("NeoForge is not built for 1.20.1")
object NeoForgeV2Networking {
    /** No-op on 1.20.1; see the file comment. */
    @Suppress("UNUSED_PARAMETER")
    fun send(players: List<ServerPlayer>, packet: Any) = Unit

    /** No-op on 1.20.1; see the file comment. */
    @Suppress("UNUSED_PARAMETER")
    fun sendBatch(players: List<ServerPlayer>, packets: List<Any>) = Unit

    /** No-op on 1.20.1; see the file comment. */
    @Suppress("UNUSED_PARAMETER")
    fun registerReceivers(registrar: Any) = Unit

    /** Unreachable on 1.20.1; see the file comment. */
    @Suppress("UNUSED_PARAMETER")
    internal fun dispatch(player: ServerPlayer, server: Any, packet: Any) = Unit
}