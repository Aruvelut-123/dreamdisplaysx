package com.dreamdisplayx.platform.server

/**
 * Legacy (1.20.1) stand-in for the NeoForge server entry point.
 *
 * The original is `@NeoForgeOnly` and `@Mod`-annotated, constructed by FancyModLoader with an
 * `IEventBus`, and imports `net.neoforged.neoforge.*` plus `net.neoforged.fml.*`. 1.20.1 is a
 * Fabric-only target: [Server] (FabricServer.kt, `@FabricOnly`) is the entry point that actually
 * runs, and nothing constructs this class. PlatformWeaver strips the `@NeoForgeOnly` declaration in
 * the IR phase, long after imports are resolved, so the class has to exist here in a
 * NeoForge-free form.
 */
@Deprecated("NeoForge is not built for 1.20.1")
class NeoForgeServer