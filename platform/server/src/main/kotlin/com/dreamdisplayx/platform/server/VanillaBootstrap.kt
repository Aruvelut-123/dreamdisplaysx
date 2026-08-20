package com.dreamdisplayx.platform.server

import com.dreamdisplayx.platform.server.cast.CastManager
import com.dreamdisplayx.platform.server.credentials.CredentialActions
import com.dreamdisplayx.platform.server.credentials.CredentialStore
import com.dreamdisplayx.platform.server.ModLoaderOnly
import com.dreamdisplayx.platform.server.managers.DisplayManager
import com.dreamdisplayx.platform.server.managers.StateManager
import com.dreamdisplayx.platform.server.managers.StorageManager
import com.dreamdisplayx.platform.server.meta.ServerCoroutines
import com.dreamdisplayx.platform.server.meta.Updater
import com.dreamdisplayx.platform.server.playback.*
import com.dreamdisplayx.platform.server.proxy.VanillaProxyBridge
import com.dreamdisplayx.platform.server.storage.StorageBackend
import com.dreamdisplayx.platform.server.utils.net.VanillaNetworking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.server.MinecraftServer
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shared `Fabric` / `NeoForge` server-lifecycle bootstrap: storage bring-up, display registration, playback transport wiring,
 * and the repeating background tasks.
 */
@ModLoaderOnly
object VanillaBootstrap {
    /** Connects storage, loads displays, binds playback, and starts the repeating tasks. */
    fun onServerStarted(server: MinecraftServer, dataDir: File) {
        val s = VanillaServerState.config.storage
        val storage = StorageManager(
            backend = StorageBackend.fromConfig(s.type), dataDir = dataDir,
            tablePrefix = s.tablePrefix,
            host = s.host, port = s.port, database = s.database,
            username = s.username, password = s.password, useSSL = s.useSSL,
        )
        VanillaServerState.storage = storage
        storage.createSchema()
        DisplayManager.register(storage.loadAllVanillaDisplays())
        VanillaPlaybackTransport.bind(server)
        WatchPartyManager.init(VanillaPlaybackTransport)
        TimelineManager.init(VanillaPlaybackTransport)
        FullscreenBroadcastManager.init(VanillaPlaybackTransport)
        FullscreenBroadcastManager.restore()
        PipPinManager.init(VanillaPlaybackTransport)
        PipPinManager.restore()
        ScheduledPlaybackManager.init(VanillaPlaybackTransport)
        ScheduledPlaybackManager.restoreOnStartup()
        VanillaProxyBridge.init(
            VanillaServerState.config.proxy.enabled,
            VanillaServerState.config.proxy.clock_sync_interval
        )
        val screenShare = VanillaServerState.config.settings.screenShare
        if (screenShare.enabled) {
            CastManager.start(screenShare.port, screenShare.public_host)
        }
        CredentialStore.init(dataDir)
        startRepeatingTasks(server)
    }

    /** Persists all displays and disconnects storage. */
    fun onServerStopping() {
        CastManager.stop()
        DisplayManager.save { VanillaServerState.storage?.saveDisplay(it) }
        ServerCoroutines.shutdown()
        VanillaServerState.storage?.disconnect()
    }

    /** Starts repeating coroutines for display updates and update checking on [ServerCoroutines.io]. */
    private fun startRepeatingTasks(server: MinecraftServer) {
        val settings = VanillaServerState.config.settings

        ServerCoroutines.io.launch {
            while (!server.isStopped) {
                delay(1000L.milliseconds)
                runCatching {
                    server.execute {
                        DisplayManager.updateAllDisplays(server)
                        StateManager.tickBroadcast(server)
                        TimelineManager.tick()
                        WatchPartyManager.tick()
                        FullscreenBroadcastManager.tick()
                        VanillaProxyBridge.tick(server)
                    }
                }
            }
        }

        if (settings.updatesEnabled) {
            ServerCoroutines.io.launch {
                delay(1000L.milliseconds)
                runCatching { Updater.checkForUpdates(settings.repoOwner, settings.repoName) }
                while (!server.isStopped) {
                    delay((60L * 60L * 1000L).milliseconds)
                    runCatching { Updater.checkForUpdates(settings.repoOwner, settings.repoName) }
                }
            }
        }

        // Hourly Bilibili session refresh: extend every stored credential before it expires.
        ServerCoroutines.io.launch {
            // First refresh after a short delay to let the server settle
            delay(30_000L.milliseconds)
            while (!server.isStopped) {
                try {
                    CredentialActions.refreshAllBilibili { playerUuid, credentials ->
                        val player = server.playerList.getPlayer(java.util.UUID.fromString(playerUuid))
                        if (player != null) {
                            VanillaNetworking.adapter.sendV2(listOf(player), credentials)
                        }
                    }
                } catch (e: Exception) {
                    com.dreamdisplayx.platform.server.VanillaServerState.logger.warn("Bilibili session refresh sweep failed.", e)
                }
                delay(1.hours)
            }
        }
    }
}
