package com.dreamdisplays.core.bridge

import com.dreamdisplays.api.Display
import com.dreamdisplays.api.DisplayBudget
import com.dreamdisplays.api.Displays
import com.dreamdisplays.api.Session
import com.dreamdisplays.api.display.event.Events
import com.dreamdisplays.api.display.model.property.DisplayId as CompatDisplayId
import com.dreamdisplays.api.display.placement.DisplayPlacementProvider
import com.dreamdisplays.api.display.placement.DisplayPlacements
import com.dreamdisplays.api.internal.ApiBackend
import com.dreamdisplays.api.media.search.MediaSearchPage
import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.api.media.search.MediaSearchService
import com.dreamdisplays.api.media.search.SortOrder
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.media.source.MediaResolverService
import com.dreamdisplays.api.media.source.MediaSource as CompatMediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia as CompatResolvedMedia
import com.dreamdisplays.api.platform.identity.Platform as CompatPlatform
import com.dreamdisplays.api.watchparty.WatchPartyService
import com.dreamdisplays.api.watchparty.WatchPartySession
import com.dreamdisplayx.api.display.service.DisplayService
import com.dreamdisplayx.api.media.search.service.MediaSearchService as XMediaSearchService
import com.dreamdisplayx.api.media.source.model.MediaSource as XMediaSource
import com.dreamdisplayx.api.media.source.model.ResolvedMedia as XResolvedMedia
import com.dreamdisplayx.api.media.source.service.MediaResolverRegistry as XMediaResolverRegistry
import com.dreamdisplayx.api.media.source.service.MediaResolverService as XMediaResolverService
import com.dreamdisplayx.api.platform.identity.Platform as XPlatform
import com.dreamdisplayx.api.playback.service.PlaybackService
import com.dreamdisplayx.api.watchparty.service.WatchPartyService as XWatchPartyService
import org.slf4j.LoggerFactory

/**
 * The live `ApiBackend` behind the compatible `DreamDisplays` entry point.
 *
 * It wires the fork's own services (`DisplayService`, `PlaybackService`, `WatchPartyService`, media search, resolver
 * registry, platform) behind the upstream-shaped interfaces, so a third-party addon compiled against upstream runs
 * unchanged.
 *
 * Capabilities the fork genuinely lacks keep the upstream contract but do nothing: placements are never registered
 * (every display sits where it is stored) and the display budget is unbounded.
 *
 * @since 1.10.x
 */
class ForkApiBackend(
    internal val displayService: DisplayService,
    internal val playbackService: PlaybackService?,
    internal val platformService: XPlatform,
    private val watchPartyService: XWatchPartyService? = null,
    private val searchService: XMediaSearchService? = null,
    private val resolverRegistry: XMediaResolverRegistry? = null,
    private val addonSink: ((Any) -> Unit)? = null,
) : ApiBackend {

    private val logger = LoggerFactory.getLogger(ForkApiBackend::class.java)

    override val displays: Displays = CompatDisplays(displayService, playbackService)

    override val events: Events = CompatEvents(displayService, playbackService)

    override val placements: DisplayPlacements = EmptyPlacements

    override val budget: DisplayBudget = UnboundedBudget

    override val watchParty: WatchPartyService = CompatWatchParty(watchPartyService)

    override val search: MediaSearchService = CompatSearch(searchService)

    override val resolvers: MediaResolverRegistry = CompatResolvers(resolverRegistry)

    override val platform: CompatPlatform get() = PlatformBridge(platformService)

    override fun display(id: CompatDisplayId): Display? =
        displayService.getDisplay(id.toFork())?.let { CompatDisplay(displayService, playbackService, id) }

    override fun session(name: String): Session = CompatSession(name, this)

    override fun registerAddon(addon: Any) {
        val sink = addonSink ?: run {
            logger.warn("Addon {} was registered before the host was ready and is ignored.", addon.javaClass.name)
            return
        }
        runCatching { sink(addon) }
            .onFailure { logger.error("Addon {} could not be registered.", addon.javaClass.name, it) }
    }

    /** No addon can move a display in this build, so no placement is ever provided. */
    private object EmptyPlacements : DisplayPlacements {
        override fun register(provider: DisplayPlacementProvider): AutoCloseable = AutoCloseable {}
    }

    /** The fork plays every display it can see, so the budget accepts any limit and enforces none. */
    private object UnboundedBudget : DisplayBudget {
        override var maxActive: Int = 0
        override val maxActiveLimit: Int = Int.MAX_VALUE
    }
}

/** Routes the compatible watch-party surface to the fork service; no-ops when the fork has none. */
internal class CompatWatchParty(private val delegate: XWatchPartyService?) : WatchPartyService {
    override fun start(displayId: CompatDisplayId, url: String?): Boolean =
        delegate?.start(displayId.toFork(), url) ?: false

    override fun setReady(displayId: CompatDisplayId, ready: Boolean) {
        delegate?.setReady(displayId.toFork(), ready)
    }

    override fun begin(displayId: CompatDisplayId) {
        delegate?.begin(displayId.toFork())
    }

    override fun end(displayId: CompatDisplayId) {
        delegate?.end(displayId.toFork())
    }

    override fun restart(displayId: CompatDisplayId) {
        delegate?.restart(displayId.toFork())
    }

    override fun close(displayId: CompatDisplayId) {
        delegate?.close(displayId.toFork())
    }

    override fun getSession(displayId: CompatDisplayId): WatchPartySession? =
        delegate?.getSession(displayId.toFork())?.toCompat()
}

/** Routes the compatible search surface to the fork service, translating every result type. */
internal class CompatSearch(private val delegate: XMediaSearchService?) : MediaSearchService {
    override fun search(query: String, limit: Int): List<MediaSearchResult> =
        delegate?.search(query, limit).orEmpty().map { it.toCompat() }

    override fun related(videoId: String, limit: Int): List<MediaSearchResult> =
        delegate?.related(videoId, limit).orEmpty().map { it.toCompat() }

    override fun searchPage(query: String, limit: Int, sortOrder: SortOrder): MediaSearchPage =
        delegate?.searchPage(query, limit, sortOrder.toFork())?.toCompat() ?: MediaSearchPage(emptyList())

    override fun searchMore(continuationToken: String, limit: Int): MediaSearchPage =
        delegate?.searchMore(continuationToken, limit)?.toCompat() ?: MediaSearchPage(emptyList())

    override fun relatedPage(videoId: String, limit: Int): MediaSearchPage =
        delegate?.relatedPage(videoId, limit)?.toCompat() ?: MediaSearchPage(emptyList())

    override fun relatedMore(continuationToken: String, limit: Int): MediaSearchPage =
        delegate?.relatedMore(continuationToken, limit)?.toCompat() ?: MediaSearchPage(emptyList())

    override fun extractVideoId(url: String): String? = delegate?.extractVideoId(url)

    override fun metadata(videoId: String): MediaSearchResult? = delegate?.metadata(videoId)?.toCompat()
}

/** Routes the compatible resolver registry to the fork registry, adapting sources and results. */
internal class CompatResolvers(private val delegate: XMediaResolverRegistry?) : MediaResolverRegistry {
    override fun register(resolver: MediaResolverService) {
        delegate?.register(BridgedResolver(resolver))
    }

    override fun unregister(resolver: MediaResolverService) {
        val bridged = delegate?.resolvers?.filterIsInstance<BridgedResolver>()?.firstOrNull { it.wraps(resolver) }
        if (bridged != null) delegate.unregister(bridged)
    }

    override fun resolve(source: CompatMediaSource): CompatResolvedMedia {
        val registry = delegate ?: throw com.dreamdisplays.api.media.model.DreamMediaException.NotFound(
            "No media resolver registry is available on this platform.",
        )
        return registry.resolve(source.toFork()).toCompat()
    }

    override fun prefetch(source: CompatMediaSource) {
        delegate?.prefetch(source.toFork())
    }

    override val resolvers: List<MediaResolverService>
        get() = delegate?.resolvers.orEmpty().map { ForkResolverView(it) }
}

/**
 * Presents a fork resolver as an upstream [MediaResolverService].
 *
 * A resolver that was registered *through the compatibility surface* is already a [BridgedResolver]; it is unwrapped
 * again so an addon reading `resolvers` gets back the instance it registered instead of a bridge-of-a-bridge.
 */
internal class ForkResolverView(private val delegate: XMediaResolverService) : MediaResolverService {
    override val priority: Int get() = delegate.priority
    override fun canResolve(source: CompatMediaSource): Boolean =
        runCatching { delegate.canResolve(source.toFork()) }.getOrDefault(false)
    override fun resolve(source: CompatMediaSource): CompatResolvedMedia =
        delegate.resolve(source.toFork()).toCompat()
    override fun prefetch(source: CompatMediaSource): Boolean =
        runCatching { delegate.prefetch(source.toFork()) }.getOrDefault(false)

    /** Returns the addon-supplied resolver hidden behind a bridge, or this view itself. */
    fun unwrap(): MediaResolverService = (delegate as? BridgedResolver)?.original ?: this
}

/** Adapts an addon-supplied resolver to the fork's resolver contract. */
internal class BridgedResolver(private val delegate: MediaResolverService) : XMediaResolverService {
    override val priority: Int get() = delegate.priority

    override fun canResolve(source: XMediaSource): Boolean =
        runCatching { delegate.canResolve(source.toCompat()) }.getOrDefault(false)

    override fun resolve(source: XMediaSource): XResolvedMedia = delegate.resolve(source.toCompat()).toFork()

    override fun prefetch(source: XMediaSource): Boolean =
        runCatching { delegate.prefetch(source.toCompat()) }.getOrDefault(false)

    /** True when this bridge wraps [resolver], so `unregister` can find the instance it registered. */
    fun wraps(resolver: MediaResolverService): Boolean = delegate === resolver

    /** The addon-supplied resolver this bridge forwards to. */
    val original: MediaResolverService get() = delegate
}