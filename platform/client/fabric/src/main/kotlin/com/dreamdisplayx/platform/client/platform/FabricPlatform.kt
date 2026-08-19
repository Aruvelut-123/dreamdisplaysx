package com.dreamdisplayx.platform.client.platform

import com.dreamdisplayx.api.platform.capability.PlatformLogger
import com.dreamdisplayx.api.platform.capability.PlatformPaths
import com.dreamdisplayx.api.platform.capability.PlatformScheduler
import com.dreamdisplayx.api.platform.identity.Platform
import com.dreamdisplayx.api.platform.identity.PlatformId
import com.dreamdisplayx.api.platform.identity.PlatformSide
import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.util.GeneralUtil
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path
import kotlin.jvm.optionals.getOrNull

/** Fabric client [Platform]. Versions and paths come from [FabricLoader] metadata. */
object FabricPlatform : Platform {

    override val platformId: PlatformId = PlatformId.FABRIC
    override val id: String get() = platformId.wire
    override val side: PlatformSide = PlatformSide.CLIENT

    override val minecraftVersion: String by lazy {
        FabricLoader.getInstance().getModContainer("minecraft").getOrNull()
            ?.metadata?.version?.friendlyString ?: "unknown"
    }

    override val modVersion: String by lazy {
        FabricLoader.getInstance().getModContainer(Initializer.MOD_ID).getOrNull()
            ?.metadata?.version?.friendlyString ?: GeneralUtil.getModVersion()
    }

    override val scheduler: PlatformScheduler = MinecraftClientScheduler
    override val logger: PlatformLogger = Slf4jPlatformLogger("DreamDisplaysX")

    /** Mirrors the mod's existing layout: `config/dreamdisplayx` for config and caches, `libs` for binaries. */
    override val paths: PlatformPaths = object : PlatformPaths {
        override val configDir: Path get() = FabricLoader.getInstance().configDir.resolve(Initializer.MOD_ID)
        override val cacheDir: Path get() = configDir.resolve("yt-cache")
        override val dataDir: Path get() = FabricLoader.getInstance().gameDir.resolve("libs")
        override val modDir: Path get() = FabricLoader.getInstance().gameDir.resolve("mods")
    }

    override val isDevEnvironment: Boolean
        get() = FabricLoader.getInstance().isDevelopmentEnvironment
}
