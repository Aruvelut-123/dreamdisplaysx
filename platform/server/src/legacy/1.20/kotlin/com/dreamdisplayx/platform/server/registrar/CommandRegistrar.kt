package com.dreamdisplayx.platform.server.registrar

/**
 * Legacy (1.20.1) stand-in for the Paper command registrar.
 *
 * The modern implementation is built on `io.papermc.paper.command.brigadier`, an API Paper only
 * introduced for 1.21.6+; `paper-api:1.20.1` does not contain that package at all, so the file
 * cannot compile against it. 1.20.1 is a Fabric-only target here: the Fabric entry point registers
 * its command tree through [VanillaCommandTree]/[FabricCommandRegistrar] against vanilla brigadier,
 * and nothing calls into this object. PlatformWeaver would strip the `@PaperOnly` body anyway, but
 * stripping happens in the IR phase, long after imports are resolved, so the imports have to exist
 * for the front end. Keeping the file as an empty shell is what makes that possible.
 */
@Deprecated("Unavailable on 1.20.1; Paper 1.20.1 has no io.papermc.paper.command.brigadier API")
object CommandRegistrar {
    /**
     * Bukkit-era command registration: 1.20.1 has no `LifecycleEvents.COMMANDS`, so the plugin
     * declares `/display` in `plugin.yml` and the executor is attached in `onLoad`.
     * Unused in this fork (1.20.1 ships Fabric only), kept for source compatibility.
     */
    @Suppress("UNUSED_PARAMETER")
    fun registerBukkit(plugin: Any) = Unit
}
