package com.dreamdisplayx.platform.server

import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.server.listeners.NeoForgePlayerListener
import com.dreamdisplayx.platform.server.listeners.NeoForgeProtectionListener
import com.dreamdisplayx.platform.server.listeners.NeoForgeSelectionListener
import com.dreamdisplayx.platform.server.managers.StorageManager
import com.dreamdisplayx.platform.server.registrar.NeoForgeBareTokenArgumentType
import com.dreamdisplayx.platform.server.registrar.NeoForgeCommandRegistrar
import com.dreamdisplayx.platform.server.utils.net.NeoForgeNetworkingAdapter
import com.dreamdisplayx.platform.server.utils.net.NeoForgeProxyNetworking
import com.dreamdisplayx.platform.server.utils.net.NeoForgeV2Networking
import com.dreamdisplayx.platform.server.utils.net.VanillaNetworking
import com.dreamdisplayx.platform.server.utils.net.VanillaServerPacketHandler
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.bus.api.IEventBus
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.registries.RegisterEvent
import org.slf4j.LoggerFactory

/**
 * `NeoForge`-specific implementation of [PaperServer]. See `FabricServer.kt` for the `Fabric`
 * mirror and `VanillaBootstrap.kt` for the storage / playback bring-up shared by both.
 */
@NeoForgeOnly
@Mod(Initializer.MOD_ID)
class NeoForgeServer(modEventBus: IEventBus) {
    init {
        logger.info("Initializing server-side mod...")

        configInstance = VanillaConfig(FMLPaths.CONFIGDIR.get().resolve("dreamdisplayx").toFile())
        VanillaServerState.config = configInstance
        VanillaServerState.serverVersion = serverVersion
        VanillaServerState.platformName = "neoforge"
        VanillaNetworking.adapter = NeoForgeNetworkingAdapter

        modEventBus.addListener(::registerArgumentTypes)
        modEventBus.addListener(::registerPayloads)
        NeoForge.EVENT_BUS.register(this)
        NeoForge.EVENT_BUS.register(NeoForgePlayerListener)
        NeoForge.EVENT_BUS.register(NeoForgeProtectionListener)
        NeoForge.EVENT_BUS.register(NeoForgeSelectionListener)
        NeoForge.EVENT_BUS.addListener(NeoForgeCommandRegistrar::register)

        logger.info("Server-side initialization complete.")
    }

    /**
     * Registers [NeoForgeBareTokenArgumentType] once `NeoForge` unfreezes `COMMAND_ARGUMENT_TYPE`
     * for this event; doing it eagerly from the constructor is too early - that registry is still
     * frozen at mod-construction time and only opens up for this `RegisterEvent` pass.
     */
    private fun registerArgumentTypes(event: RegisterEvent) {
        if (event.registryKey == Registries.COMMAND_ARGUMENT_TYPE) {
            NeoForgeBareTokenArgumentType.register()
        }
    }

    /** Registers all custom payload types for clientbound and serverbound play channels. */
    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(Initializer.MOD_ID).optional().versioned("1")
        NeoForgeV2Networking.registerReceivers(registrar)
        NeoForgeProxyNetworking.registerReceivers(registrar)
        VanillaServerPacketHandler.registerReceivers(registrar)
    }

    /** Storage bring-up, display registration, and repeating tasks; covers dedicated + integrated servers alike. */
    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        val server = event.server
        val dataDir = server.getWorldPath(LevelResource.LEVEL_DATA_FILE)
            .parent.resolve("dreamdisplayx").toFile().also { it.mkdirs() }

        VanillaServerState.server = server
        VanillaBootstrap.onServerStarted(server, dataDir)

        logger.info("Server started. Storage connected.")
    }

    /** Persists state and tears down resources. */
    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        logger.info("Server stopping. Saving displays...")
        VanillaBootstrap.onServerStopping()
    }

    companion object {
        /** Logger. */
        val logger = LoggerFactory.getLogger("DreamDisplaysX")

        /** The mod version string, read from the bundled, Gradle-templated `version.txt` resource. */
        val serverVersion: String? by lazy {
            runCatching {
                NeoForgeServer::class.java.classLoader
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
