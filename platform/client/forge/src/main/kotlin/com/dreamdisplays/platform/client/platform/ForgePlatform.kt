package com.dreamdisplays.platform.client.platform

import com.dreamdisplays.api.platform.capability.PlatformLogger
import com.dreamdisplays.api.platform.capability.PlatformPaths
import com.dreamdisplays.api.platform.capability.PlatformScheduler
import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.api.platform.identity.PlatformId
import com.dreamdisplays.api.platform.identity.PlatformSide
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.shared.platform.PlatformService
import com.dreamdisplays.util.GeneralUtil
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.fml.loading.FMLPaths
import java.nio.file.Path

/** `Forge` client [Platform]. Versions come from [ModList]; paths from [FMLPaths]. */
object ForgePlatform : Platform {

    override val platformId: PlatformId = PlatformId.FORGE
    override val id: String get() = platformId.wire
    override val side: PlatformSide = PlatformSide.CLIENT

    override val minecraftVersion: String by lazy {
        ModList.get().getModContainerById("minecraft")
            .map { it.modInfo.version.toString() }
            .orElse("unknown")
    }

    override val modVersion: String by lazy {
        ModList.get().getModContainerById(Initializer.MOD_ID)
            .map { it.modInfo.version.toString() }
            .orElse(GeneralUtil.getModVersion())
    }

    override val scheduler: PlatformScheduler = MinecraftClientScheduler
    override val logger: PlatformLogger = Slf4jPlatformLogger("DreamDisplays")

    override val paths: PlatformPaths = object : PlatformPaths {
        override val configDir: Path get() = FMLPaths.CONFIGDIR.get().resolve(Initializer.MOD_ID)
        override val cacheDir: Path get() = configDir.resolve("yt-cache")
        override val dataDir: Path get() = FMLPaths.GAMEDIR.get().resolve("libs")
        override val modDir: Path get() = FMLPaths.MODSDIR.get()
    }

    override val isDevEnvironment: Boolean get() = !FMLEnvironment.production
}

/** Supplies the `Forge` [Platform] adapter. */
object ForgePlatformIntegrationProvider : PlatformService {
    override fun create(): Platform = ForgePlatform
}
