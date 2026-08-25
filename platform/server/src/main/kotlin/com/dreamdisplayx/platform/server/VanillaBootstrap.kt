package com.dreamdisplayx.platform.server

import com.dreamdisplayx.platform.server.credentials.CredentialActions
import com.dreamdisplayx.platform.server.credentials.CredentialStore
import com.dreamdisplayx.platform.server.credentials.SqlCredentialSyncBackend
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
        // Singleplayer / integrated (client-side) servers never use MySQL: the client does not bundle
        // the MySQL driver, so force SQLite. Dedicated Fabric/NeoForge servers still honor the config.
        val dedicated = server.isDedicatedServer
        val configuredBackend = StorageBackend.fromConfig(s.type)
        val effectiveBackend = if (dedicated) configuredBackend else StorageBackend.SQLITE
        val effectiveJdbcUrl = if (dedicated) s.jdbcUrl else ""
        // A Flashback replay server runs against a temporary world copy under flashback/temp/.../saves/replay/
        // that Flashback deletes when playback starts/stops. Never open a database there: the open SQLite
        // file would block that cleanup, and persistence is meaningless on a transient replay. Skip storage.
        val replayServer = isFlashbackReplayServer(server, dataDir)
        if (!replayServer) {
            val storage = StorageManager(
                backend = effectiveBackend, dataDir = dataDir,
                tablePrefix = s.tablePrefix,
                host = s.host, port = s.port, database = s.database,
                username = s.username, password = s.password, useSSL = s.useSSL, jdbcUrl = effectiveJdbcUrl,
            )
            VanillaServerState.storage = storage
            storage.createSchema()
            DisplayManager.register(storage.loadAllVanillaDisplays())
        } else {
            VanillaServerState.storage = null
            DisplayManager.register(emptyList())
        }
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
        CredentialStore.init(dataDir)

        // Set up credential sync in the same database as displays (SQLite or MySQL)
        if (!replayServer) {
            try {
                val syncBackend = SqlCredentialSyncBackend(
                    backend = effectiveBackend, dataDir = dataDir, tablePrefix = s.tablePrefix,
                    host = s.host, port = s.port, database = s.database,
                    username = s.username, password = s.password, useSSL = s.useSSL, jdbcUrl = effectiveJdbcUrl,
                )
                CredentialStore.loadFromSyncBackend(syncBackend)
                CredentialStore.setSyncBackend(syncBackend)
            } catch (e: Exception) {
                VanillaServerState.logger.warn("Failed to initialize credential sync backend.", e)
            }
        }

        startRepeatingTasks(server)
    }

    /** Persists all displays and disconnects storage. */
    fun onServerStopping() {
        DisplayManager.save { VanillaServerState.storage?.saveDisplay(it) }
        ServerCoroutines.shutdown()
        VanillaServerState.storage?.disconnect()
    }

    /** True when [server] is a Flashback replay server (a transient world copy used for playback). */
    private fun isFlashbackReplayServer(server: MinecraftServer, dataDir: File): Boolean {
        // Flashback's ReplayServer is a real IntegratedServer, but the strongest signal is the world
        // path: replay worlds live under flashback/temp/server/<uuid>/saves/replay/. Check both.
        val byClass = server.javaClass.name.contains("flashback", ignoreCase = true) ||
            server.javaClass.name.contains("ReplayServer")
        if (byClass) return true
        val path = dataDir.path.replace('\\', '/').lowercase()
        return path.contains("flashback/temp/") || path.contains("/replay/")
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
    }
}
