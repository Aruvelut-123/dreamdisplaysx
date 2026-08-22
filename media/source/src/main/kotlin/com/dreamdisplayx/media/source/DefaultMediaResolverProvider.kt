package com.dreamdisplayx.media.source

import com.dreamdisplayx.api.media.source.service.MediaResolverService
import com.dreamdisplayx.api.media.source.service.MediaResolverProvider
import com.dreamdisplayx.media.source.bilibili.BilibiliResolver
import com.dreamdisplayx.media.source.direct.DirectStreamResolver
import com.dreamdisplayx.media.source.ingest.IngestResolver
import com.dreamdisplayx.media.source.kick.KickResolver
import com.dreamdisplayx.media.source.twitch.TwitchResolver
import com.dreamdisplayx.media.source.vimeo.VimeoResolver

/**
 * Built-in resolver chain, fastest first: direct URL probe, then in-process platform resolvers
 * (Twitch, Vimeo, Kick, Bilibili). Live ingest endpoints (screen sharing) are claimed by [IngestResolver].
 */
object DefaultMediaResolverProvider : MediaResolverProvider {
    override fun resolvers(): List<MediaResolverService> = listOf(
        DirectStreamResolver,
        IngestResolver,
        TwitchResolver,
        VimeoResolver,
        KickResolver,
        BilibiliResolver,
    )
}
