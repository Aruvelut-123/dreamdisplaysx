package com.dreamdisplayx.platform.client.core.modules

import com.dreamdisplays.api.internal.ApiHolder
import com.dreamdisplays.core.addon.ClientAddonBinder
import com.dreamdisplays.core.bridge.ForkApiBackend
import com.dreamdisplayx.api.DreamDisplaysX
import com.dreamdisplayx.api.display.service.keys.DisplayServices
import com.dreamdisplayx.api.media.service.keys.MediaServices
import com.dreamdisplayx.api.platform.identity.PlatformSide
import com.dreamdisplayx.api.platform.service.keys.PlatformServices
import com.dreamdisplayx.api.playback.service.keys.PlaybackServices
import com.dreamdisplayx.api.runtime.module.DreamDisplaysXModule
import com.dreamdisplayx.api.runtime.module.ModuleContext
import com.dreamdisplayx.api.runtime.registry.service.ServiceRegistry
import com.dreamdisplayx.api.runtime.registry.service.get
import com.dreamdisplayx.api.watchparty.service.keys.WatchPartyServices
import com.dreamdisplayx.core.addon.ForkAddonManager
import com.dreamdisplayx.core.addon.ForkClientAddonBinder
import com.dreamdisplayx.core.addon.UpstreamBinderAdapter
import org.slf4j.LoggerFactory

/**
 * Publishes the upstream-compatible `DreamDisplays` API and hosts client-side addons of **both** kinds.
 *
 * Registration paths:
 *  * an addon written for upstream calls `com.dreamdisplays.api.DreamDisplays.register(addon)`; that reaches
 *    [ApiHolder] and lands in the same [ForkAddonManager] through the backend's `registerAddon`;
 *  * an addon written for this fork calls `DreamDisplaysX.register(addon)` (see `ForkDreamDisplays`).
 *
 * Either way the manager holds it, so both annotation families share one enable/disable order, one dependency graph
 * and one teardown path. The binder list is what differs per family, and it is applied to every addon — an upstream
 * addon simply has no fork annotations to bind, and vice versa.
 *
 * Ordering matters: the backend is attached *before* the manager starts, so addons that registered while the client
 * was still booting are flushed out of `ApiHolder`'s pending queue into the not-yet-started manager, and only then
 * does the manager enable them. Starting first would strand those pending registrations.
 *
 * The manager is a module-level constant on purpose: a client stop/start cycle (world reload) must keep the
 * registrations so the same addons come back up.
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
     * Both binder families and `ForkAddonContext.service` read services lazily through it, so an addon enabled at any
     * later point still resolves live instances instead of stale copies.
     */
    @Volatile
    private var registry: ServiceRegistry? = null

    /**
     * Addons registered on this client, kept across stop/start cycles.
     *
     * The binders run only while the manager enables an addon, which happens after [install] captured [registry] and
     * attached the backend, so nothing is read before the services exist.
     */
    private val addons: ForkAddonManager by lazy {
        ForkAddonManager(
            side = PlatformSide.CLIENT,
            binders = listOf(
                // Upstream handlers (@OnDisplay / @OnPlaying / @OnFailed) through the upstream binder.
                UpstreamBinderAdapter(ClientAddonBinder { ApiHolder.current!!.displays }),
                // Fork-only handlers (@OnQualityChanged, @OnPlaybackPosition, …) read the live DisplayService.
                ForkClientAddonBinder { registry!!.get(DisplayServices.DISPLAY) },
            ),
            services = { registry },
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
        DreamDisplaysX.installRegistrar(addons::register)
        ApiHolder.attach(backend)
        addons.start()
        logger.info("Dream Displays compatibility API attached ({} addons enabled).", addons.enabledIds.size)
    }

    /** Disables addons, then withdraws the API so a late caller sees `isAvailable == false`. */
    override fun uninstall(context: ModuleContext) {
        addons.stop()
        DreamDisplaysX.installRegistrar(null)
        ApiHolder.detach()
        logger.info("Dream Displays compatibility API detached.")
    }
}