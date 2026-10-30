package com.dreamdisplayx.api.media.service.keys

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.search.service.MediaSearchService
import com.dreamdisplayx.api.media.source.service.MediaResolverRegistry
import com.dreamdisplayx.api.runtime.registry.model.ServiceKey

/**
 * Media service keys.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
object MediaServices {
    /** Ordered resolver chain for media sources. */
    val RESOLVER_REGISTRY: ServiceKey<MediaResolverRegistry> =
        ServiceKey("dreamdisplayx:media_resolver_registry", MediaResolverRegistry::class.java)

    /** Search and related-video lookup service. */
    val SEARCH: ServiceKey<MediaSearchService> =
        ServiceKey("dreamdisplayx:media_search", MediaSearchService::class.java)
}
