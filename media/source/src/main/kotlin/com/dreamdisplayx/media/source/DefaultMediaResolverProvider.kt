package com.dreamdisplayx.media.source

import com.dreamdisplayx.api.media.source.service.MediaResolverService
import com.dreamdisplayx.api.media.source.service.MediaResolverProvider
import com.dreamdisplayx.media.source.bilibili.BilibiliResolver
import com.dreamdisplayx.media.source.direct.DirectStreamResolver
import com.dreamdisplayx.media.source.ingest.IngestResolver
import com.dreamdisplayx.media.source.kick.KickResolver
import com.dreamdisplayx.media.source.twitch.TwitchResolver
import com.dreamdisplayx.media.source.vimeo.VimeoResolver
import com.dreamdisplayx.media.source.youtube.NewPipeResolver
import com.dreamdisplayx.media.source.youtube.YtDlpResolver

/**
 * Built-in resolver chain, fastest first: direct URL probe, then in-process platform resolvers (`NewPipeExtractor`, Twitch, Vimeo, Kick,
 * Bilibili), then `yt-dlp` fallback. Live ingest endpoints (screen sharing) are claimed by [IngestResolver].
 */
object DefaultMediaResolverProvider : MediaResolverProvider {
    override fun resolvers(): List<MediaResolverService> = listOf(
        DirectStreamResolver,
        IngestResolver,
        NewPipeResolver,
        TwitchResolver,
        VimeoResolver,
        KickResolver,
        BilibiliResolver,
        YtDlpResolver,
    )
}
