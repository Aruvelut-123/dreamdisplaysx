package com.dreamdisplayx.platform.client.core.modules

import com.dreamdisplayx.api.media.service.keys.MediaServices
import com.dreamdisplayx.api.media.stream.service.StreamSelector
import com.dreamdisplayx.api.runtime.module.DreamDisplaysXModule
import com.dreamdisplayx.api.runtime.module.ModuleContext
import com.dreamdisplayx.api.runtime.registry.service.register
import com.dreamdisplayx.media.source.DefaultMediaResolverProvider
import com.dreamdisplayx.media.source.DefaultMediaResolverRegistry
import com.dreamdisplayx.media.source.DefaultStreamSelector
import com.dreamdisplayx.media.source.DirectSearchService

/** Installs the media resolver chain, search service, and stream selector. */
object MediaResolverModule : DreamDisplaysXModule {
    /** Media resolver module. */
    override val id: String = "dreamdisplayx:media_resolver"

    /** Installs the media resolver chain, search service, and stream selector. */
    override fun install(context: ModuleContext) {
        val resolverChain = DefaultMediaResolverRegistry().apply {
            DefaultMediaResolverProvider.resolvers().forEach(::register)
        }

        val services = context.services
        services.register(MediaServices.RESOLVER_REGISTRY, resolverChain)
        services.register(MediaServices.SEARCH, DirectSearchService)
        services.register<StreamSelector>(DefaultStreamSelector())
    }
}
