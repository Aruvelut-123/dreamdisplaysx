package com.dreamdisplayx.platform.client.managers

import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.api.platform.service.keys.PlatformServices
import com.dreamdisplayx.api.runtime.module.DreamDisplaysXModule
import com.dreamdisplayx.api.runtime.registry.service.register
import com.dreamdisplayx.media.player.cdn.CdnSpeedProbe
import com.dreamdisplayx.media.source.bilibili.BilibiliApi
import com.dreamdisplayx.platform.client.Config
import com.dreamdisplayx.platform.client.Focuser
import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.core.ClientApplication
import com.dreamdisplayx.platform.client.core.DefaultClientApplication
import com.dreamdisplayx.platform.client.core.DefaultClientContext
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.core.modules.*
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.dreamdisplayx.platform.client.storage.ClientSettingsStore
import com.dreamdisplayx.platform.client.storage.CustomVideoStore
import com.dreamdisplayx.platform.client.storage.WatchedVideoStore
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Handles client bootstrapping and background maintenance coroutines.
 */
object ClientStartupManager {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ClientStartupManager")

    /** The client configuration, loaded from the mod's config directory. */
    val config: Config = Config(File("./config/${Initializer.MOD_ID}"))

    /** How often the background loop re-pushes each display's quality setting. */
    private val qualityRefreshInterval = 2500.milliseconds

    /**
     * Owns every background maintenance coroutine. Runs on [Dispatchers.IO] (a pool of daemon threads), so a hung task
     * never blocks the client's main thread.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The default module set installed into every [ClientApplication] built by [start]. */
    private val defaultModules: List<DreamDisplaysXModule> = listOf(
        ClientStorageModule,
        CoreDisplayModule,
        CorePlaybackModule,
        ClientAudioModule,
        MediaResolverModule,
        ClientOverlayModule,
        ClientInputModule,
        ClientRenderModule,
        ClientCapabilityModule,
    )

    /** Loads config, wires services, hosts the application, prewarms backends, and launches maintenance loops. */
    fun start() {
        config.reload()
        ClientSettingsStore.load()
        WatchedVideoStore.load()
        CustomVideoStore.load()

        val platform = DreamServices.registry.get(PlatformServices.PLATFORM)
        val application = DefaultClientApplication(DefaultClientContext(platform))
        DreamServices.registry.register<ClientApplication>(application)
        defaultModules.forEach(application::registerModule)
        application.start()

        Focuser().start()
        scope.launch {
            while (isActive) {
                runCatching { DisplayRegistry.getScreens().forEach(DisplayScreen::reloadQuality) }
                    .onFailure { e -> logger.warn("Quality refresh failed.", e) }
                delay(qualityRefreshInterval)
            }
        }

        // Background Bilibili CDN mirror probe — runs once at startup so the first playback
        // already has a full bandwidth ranking of all known mirrors (based on PiliPlus's
        // CDNService approach).  If the sample video is unreachable (no network, video removed),
        // the probe is skipped and playback falls back to on-the-fly probing.
        refreshCdnRanking(6_000L) // initial delay 6s for startup
    }

    /**
     * Clears the cached CDN scores and triggers a fresh bandwidth probe of all known
     * [BilibiliCdnMirror] hosts in the background.  Called from startup and when the
     * config screen is opened.
     *
     * @param initialDelayMs  delay before starting the probe (ms); 0 for immediate.
     */
    fun refreshCdnRanking(initialDelayMs: Long = 0) {
        scope.launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            CdnSpeedProbe.clearScores()
            runCatching {
                val sample = BilibiliApi.resolve(MediaSource.Bilibili(
                    url = "https://www.bilibili.com/video/BV1fK4y1t7hj",
                    bvid = "BV1fK4y1t7hj",
                    part = 1,
                )) ?: return@runCatching
                val urls = sample.streams.flatMap { listOf(it.url) + it.backupUrls }
                if (urls.isEmpty()) return@runCatching
                CdnSpeedProbe.startupProbe(urls, "https://www.bilibili.com")
            }.onFailure { e ->
                logger.debug("Bilibili CDN ranking refresh skipped (sample video unreachable): ${e.message}")
            }
        }
    }

    /** Cancels the background refresh / sweep coroutines. Safe to call multiple times. */
    fun stop() {
        scope.cancel()
    }
}
