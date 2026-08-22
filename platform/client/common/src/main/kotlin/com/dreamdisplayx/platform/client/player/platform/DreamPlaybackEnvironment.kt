package com.dreamdisplayx.platform.client.player.platform

import com.dreamdisplayx.api.media.service.keys.MediaServices
import com.dreamdisplayx.api.media.player.*
import com.dreamdisplayx.api.media.source.service.MediaResolverRegistry
import com.dreamdisplayx.api.media.stream.service.StreamSelector
import com.dreamdisplayx.api.runtime.registry.service.get
import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.media.source.bilibili.BilibiliResolver
import com.dreamdisplayx.media.source.direct.DirectStreamResolver
import com.dreamdisplayx.media.source.kick.KickResolver
import com.dreamdisplayx.media.source.twitch.TwitchResolver
import com.dreamdisplayx.media.source.vimeo.VimeoResolver
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import com.dreamdisplayx.platform.client.render.DisplayYuvRenderTypes
import com.dreamdisplayx.platform.client.render.GpuFrameUploader
import net.minecraft.client.Minecraft

/**
 * Minecraft-client implementation of [PlaybackEnvironment]: bridges the platform-agnostic media
 * player to the live client configuration, the render thread, the GPU uploader, the URL cache, and
 * the service registry. One shared instance is passed to every [MediaPlayer].
 */
object DreamPlaybackEnvironment : PlaybackEnvironment {
    /** Live playback config backed by the client config and state. */
    override val config: PlaybackConfig = object : PlaybackConfig {
        /** Default volume for new displays. */
        override val defaultDisplayVolume: Double get() = ClientStateManager.config.defaultDisplayVolume

        /** Whether to use hardware acceleration. */
        override val useHwAccel: Boolean get() = ClientStateManager.config.useHwAccel

        /** Is the client premium? */
        override val isPremium: Boolean get() = ClientStateManager.isPremium

        /** Active GPU YUV render type. */
        override val gpuYuvActive: Boolean get() = DisplayYuvRenderTypes.active
    }

    /** Runs tasks on Minecraft's render/main thread. */
    override val renderExecutor: RenderExecutor =
        RenderExecutor { task -> Minecraft.getInstance().execute(task) }

    /** Creates per-player GPU frame uploaders. */
    override val uploaderFactory: FrameUploaderFactory = FrameUploaderFactory { GpuFrameUploader() as FrameUploader }

    /**
     * Invalidates every resolved-URL cache for a stream (Twitch, Vimeo, Kick, Bilibili, direct),
     * forcing a fresh resolve on next play.
     */
    override val cacheInvalidator: CacheInvalidator = CacheInvalidator { url ->
        TwitchResolver.invalidate(url)
        VimeoResolver.invalidate(url)
        KickResolver.invalidate(url)
        BilibiliResolver.invalidate(url)
        DirectStreamResolver.invalidate(url)
    }

    /** The registered media resolver chain. */
    override fun resolverChain(): MediaResolverRegistry = DreamServices.registry.get(MediaServices.RESOLVER_REGISTRY)

    /** The registered stream selector. */
    override fun streamSelector(): StreamSelector = DreamServices.registry.get<StreamSelector>()
}
