package com.dreamdisplayx.api.media.source.service

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Supplies the ordered set of [MediaResolverService]s a [MediaResolverRegistry] is assembled from.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
fun interface MediaResolverProvider {
    /** The resolvers to register, in any order; [MediaResolverRegistry] sorts by [MediaResolverService.priority]. */
    fun resolvers(): List<MediaResolverService>
}
