package com.dreamdisplayx.platform.server

import com.dreamdisplayx.platform.server.managers.StorageManager
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

/**
 * Shared runtime state for the vanilla Minecraft API loaders (`Fabric` and `NeoForge`). Both loaders populate the same
 * fields so shared code never needs a loader check.
 */
object VanillaServerState {
    /** Logger shared by both vanilla loaders. */
    val logger = LoggerFactory.getLogger("DreamDisplaysX")

    /** Set once during `onLoad` / `init`, before [server] or [storage] exist. */
    lateinit var config: VanillaConfig

    /**
     * The mod version string, resolved by whichever loader is running (`Fabric` via its mod
     * container's metadata, `NeoForge` via a bundled Gradle-templated resource) — the resolution
     * mechanism itself is genuinely loader-specific, but readers don't need to care which.
     */
    var serverVersion: String? = null

    /**
     * Which loader is actually running (`"fabric"` or `"neoforge"`), set once by that loader's own entrypoint — used
     * wherever behavior must branch by loader.
     */
    var platformName: String = ""

    //? if <1.20.2 {
    /**
     * The client-reported language of a player. Vanilla 1.20.1 keeps it private, so the loader
     * supplies the accessor (`Forge` exposes it as `getLanguage`).
     */
    var playerLanguage: (net.minecraft.server.level.ServerPlayer) -> String? = { null }
    //?}

    /** Set once the underlying server starts. */
    var server: MinecraftServer? = null

    /** Set once storage connects at server start; cleared on stop. */
    var storage: StorageManager? = null
}
