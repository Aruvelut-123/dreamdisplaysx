package com.dreamdisplayx.platform.client.core.modules

import com.dreamdisplayx.api.display.service.keys.DisplayServices
import com.dreamdisplayx.api.playback.service.PlaybackPort
import com.dreamdisplayx.api.playback.service.keys.PlaybackServices
import com.dreamdisplayx.api.runtime.module.DreamDisplaysXModule
import com.dreamdisplayx.api.runtime.module.ModuleContext
import com.dreamdisplayx.api.runtime.registry.service.get
import com.dreamdisplayx.api.runtime.registry.service.register
import com.dreamdisplayx.api.watchparty.service.WatchPartyPort
import com.dreamdisplayx.api.watchparty.service.keys.WatchPartyServices
import com.dreamdisplayx.core.services.DefaultPlaybackService
import com.dreamdisplayx.core.services.DefaultWatchPartyService
import com.dreamdisplayx.media.runtime.session.DefaultMediaSessionManager
import com.dreamdisplayx.media.runtime.session.MediaSessionManager

/** Installs playback, media-session, and watch-party services backed by the core display ports. */
object CorePlaybackModule : DreamDisplaysXModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplayx:core_playback"

    /** Dependencies of this module. */
    override val dependencies: List<String> = listOf(CoreDisplayModule.id)

    /** Installs the playback service, media-session manager, and watch-party service. */
    override fun install(context: ModuleContext) {
        val services = context.services
        val playbackService = DefaultPlaybackService(services.get<PlaybackPort>())

        services.register(PlaybackServices.PLAYBACK, playbackService)
        services.register<MediaSessionManager>(
            DefaultMediaSessionManager(playbackService, services.get(DisplayServices.DISPLAY)),
        )
        services.register(WatchPartyServices.WATCH_PARTY, DefaultWatchPartyService(services.get<WatchPartyPort>()))
    }
}
