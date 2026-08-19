package com.dreamdisplayx.api.media.service.keys

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.search.service.MediaSearchService
import com.dreamdisplayx.api.media.source.service.MediaResolverRegistry
import com.dreamdisplayx.api.runtime.registry.model.ServiceKey
import com.dreamdisplayx.api.runtime.registry.model.serviceKey

/**
 * Media service keys.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
object MediaServices {
    /** Ordered resolver chain for media sources. */
    val RESOLVER_REGISTRY: ServiceKey<MediaResolverRegistry> = serviceKey("dreamdisplayx:media_resolver_registry")

    /** Search and related-video lookup service. */
    val SEARCH: ServiceKey<MediaSearchService> = serviceKey("dreamdisplayx:media_search")
}
