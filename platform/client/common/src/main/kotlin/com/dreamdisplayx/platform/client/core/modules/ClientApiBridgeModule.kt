package com.dreamdisplayx.platform.client.core.modules

import com.dreamdisplays.api.internal.ApiHolder
import com.dreamdisplays.core.addon.AddonManager
import com.dreamdisplays.core.addon.ClientAddonBinder
import com.dreamdisplays.core.bridge.ForkApiBackend
import com.dreamdisplayx.api.DreamDisplaysX
import com.dreamdisplayx.api.display.service.keys.DisplayServices
import com.dreamdisplayx.api.media.service.keys.MediaServices
import com.dreamdisplayx.api.platform.service.keys.PlatformServices
import com.dreamdisplayx.api.playback.service.keys.PlaybackServices
import com.dreamdisplayx.api.runtime.module.DreamDisplaysXModule
import com.dreamdisplayx.api.runtime.module.ModuleContext
import com.dreamdisplayx.api.runtime.registry.service.ServiceRegistry
import com.dreamdisplayx.api.runtime.registry.service.get
import com.dreamdisplayx.api.watchparty.service.keys.WatchPartyServices
import com.dreamdisplayx.core.addon.ForkHandlersBinder
import com.dreamdisplays.api.addon.AddonSide
import org.slf4j.LoggerFactory

/**
 * Publishes the upstream-compatible `DreamDisplays` API on the client and hosts the addons registered through it.
 *
 * There is exactly one addon kind — a class carrying `com.dreamdisplays.api.addon.Addon`, registered with
 * `com.dreamdisplays.api.DreamDisplays.register(...)` — so there is exactly one [AddonManager]. This fork contributes
 * *more binders*, not a second manager:
 *
 *  * [ClientAddonBinder] — upstream's `@OnDisplay` / `@OnPlaying` / `@OnFailed`;
 *  * [ForkHandlersBinder] — this fork's `@OnQualityChanged` / `@OnVolumeChanged` / `@OnBrightnessChanged` /
 *    `@OnPlaybackPosition` / `@OnStateChanged` / `@OnFirstFrame` / `@OnEnded`.
 *
 * Both run for every addon. An upstream addon carries none of the fork annotations, so the second binder does one
 * reflective scan and returns without subscribing — which is what makes an upstream-built addon load here untouched.
 *
 * Ordering matters: the backend is attached *before* the manager starts, so addons registered while the client was
 * still booting are flushed out of `ApiHolder`'s pending queue into the not-yet-started manager and enabled at
 * [AddonManager.start]. Starting first would strand those pending registrations, because `ApiHolder.detach` only
 * clears `current` without draining the queue.
 *
 * The manager survives a client stop/start cycle (world reload), so the same addons come back up with their
 * registrations intact.
 *
 * @since 1.10.x
 */
object ClientApiBridgeModule : DreamDisplaysXModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplayx:client_api_bridge"

    /** Needs the display, playback and media services the compatible backend reads from. */
    override val dependencies: List<String> = listOf(
        CoreDisplayModule.id,
        CorePlaybackModule.id,
        MediaResolverModule.id,
    )

    /** Logger. */
    private val logger = LoggerFactory.getLogger(ClientApiBridgeModule::class.java)

    /**
     * The client registry, captured at install.
     *
     * Both binders read services lazily through it, so an addon enabled at any later point resolves live instances
     * instead of stale copies.
     */
    @Volatile
    private var registry: ServiceRegistry? = null

    /** Addons registered on this client, kept across stop/start cycles. */
    private val addons: AddonManager by lazy {
        AddonManager(
            AddonSide.CLIENT,
            listOf(
                ClientAddonBinder { ApiHolder.current!!.displays },
                ForkHandlersBinder { registry!!.get(DisplayServices.DISPLAY) },
            ),
        )
    }

    /** Attaches the compatible backend and enables every addon that is already registered. */
    override fun install(context: ModuleContext) {
        val services = context.services
        val backend = ForkApiBackend(
            displayService = services.get(DisplayServices.DISPLAY),
            playbackService = services.getOrNull(PlaybackServices.PLAYBACK),
            platformService = services.get(PlatformServices.PLATFORM),
            watchPartyService = services.getOrNull(WatchPartyServices.WATCH_PARTY),
            searchService = services.getOrNull(MediaServices.SEARCH),
            resolverRegistry = services.getOrNull(MediaServices.RESOLVER_REGISTRY),
            addonSink = addons::register,
        )
        registry = services
        DreamDisplaysX.installServices { registry }
        ApiHolder.attach(backend)
        addons.start()
        logger.info("Dream Displays compatibility API attached ({} addons enabled).", addons.enabledIds.size)
    }

    /** Disables addons, then withdraws the API so a late caller sees `isAvailable == false`. */
    override fun uninstall(context: ModuleContext) {
        addons.stop()
        DreamDisplaysX.installServices(null)
        ApiHolder.detach()
        logger.info("Dream Displays compatibility API detached.")
    }
}
