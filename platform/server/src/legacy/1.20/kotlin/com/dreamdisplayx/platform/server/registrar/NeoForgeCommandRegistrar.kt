package com.dreamdisplayx.platform.server.registrar

/**
 * Legacy (1.20.1) stand-in for the NeoForge command-registration adapter.
 *
 * The original is `@NeoForgeOnly` and takes a `net.neoforged.neoforge.event.RegisterCommandsEvent`,
 * a type a Fabric-only classpath does not carry. 1.20.1 registers its tree through
 * [FabricCommandRegistrar] on Fabric's command callback instead, and PlatformWeaver strips the
 * NeoForge body only in the IR phase, after imports are resolved.
 */
@Deprecated("NeoForge is not built for 1.20.1")
object NeoForgeCommandRegistrar {
    /** No-op on 1.20.1; see the file comment. */
    @Suppress("UNUSED_PARAMETER")
    fun register(event: Any) = Unit
}