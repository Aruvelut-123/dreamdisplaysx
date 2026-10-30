package com.dreamdisplayx.platform.server

import com.dreamdisplayx.platform.client.Client
import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.server.listeners.ForgePlayerConnectionListener
import com.dreamdisplayx.platform.server.listeners.ForgeProtectionListener
import com.dreamdisplayx.platform.server.listeners.ForgeSelectionListener
import com.dreamdisplayx.platform.server.managers.StorageManager
import com.dreamdisplayx.platform.server.registrar.ForgeCommandRegistrar
import com.dreamdisplayx.platform.server.utils.net.ForgeChannels
import com.dreamdisplayx.platform.server.utils.net.ForgeNetworkingAdapter
import com.dreamdisplayx.platform.server.utils.net.VanillaNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.fml.loading.FMLPaths
import org.slf4j.LoggerFactory

/** `Forge` server entrypoint. */
@Mod(Initializer.MOD_ID)
class ForgeServer {
    init {
        logger.info("Initializing server-side mod...")
        configInstance = VanillaConfig(FMLPaths.CONFIGDIR.get().resolve("dreamdisplayx").toFile())
        VanillaServerState.config = configInstance
        VanillaServerState.serverVersion = serverVersion
        VanillaServerState.platformName = "forge"
        VanillaServerState.playerLanguage = { it.language }
        VanillaNetworking.adapter = ForgeNetworkingAdapter

        ForgeChannels.register()
        MinecraftForge.EVENT_BUS.register(this)
        MinecraftForge.EVENT_BUS.register(ForgePlayerConnectionListener)
        MinecraftForge.EVENT_BUS.register(ForgeProtectionListener)
        MinecraftForge.EVENT_BUS.register(ForgeSelectionListener)
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RegisterCommandsEvent::class.java) {
            ForgeCommandRegistrar.register(it)
        }
        logger.info("Server-side initialization complete.")

        if (FMLEnvironment.dist.isClient) Client.init()
    }

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        val server = event.server
        val dataDir = server.getWorldPath(LevelResource.LEVEL_DATA_FILE)
            .parent.resolve("dreamdisplayx").toFile().also { it.mkdirs() }

        VanillaServerState.server = server
        VanillaBootstrap.onServerStarted(server, dataDir)

        logger.info("Server started. Storage connected.")
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        logger.info("Server stopping. Saving displays...")
        VanillaBootstrap.onServerStopping()
    }

    companion object {
        val logger = LoggerFactory.getLogger(javaClass)

        val serverVersion: String? by lazy {
            runCatching {
                ForgeServer::class.java.classLoader
                    .getResourceAsStream("assets/dreamdisplayx/version.txt")
                    ?.use { it.readBytes().decodeToString().trim() }
            }.getOrNull()
        }

        private lateinit var configInstance: VanillaConfig

        val config: VanillaConfig; get() = configInstance
        val server: MinecraftServer?; get() = VanillaServerState.server
        val storage: StorageManager?; get() = VanillaServerState.storage
    }
}
