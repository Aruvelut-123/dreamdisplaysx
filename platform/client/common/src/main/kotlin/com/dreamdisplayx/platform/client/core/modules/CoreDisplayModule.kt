package com.dreamdisplayx.platform.client.core.modules

import com.dreamdisplayx.api.display.service.*
import com.dreamdisplayx.api.display.service.keys.DisplayServices
import com.dreamdisplayx.api.playback.service.PlaybackPort
import com.dreamdisplayx.api.runtime.module.DreamDisplaysXModule
import com.dreamdisplayx.api.runtime.module.ModuleContext
import com.dreamdisplayx.api.runtime.registry.service.register
import com.dreamdisplayx.api.watchparty.service.WatchPartyPort
import com.dreamdisplayx.core.services.DefaultDisplayService
import com.dreamdisplayx.core.services.DefaultDisplaySystem
import com.dreamdisplayx.platform.client.displays.MinecraftDisplayCommands

/** Installs the client-side display system and its public [DisplayService]. */
object CoreDisplayModule : DreamDisplaysXModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplayx:core_display"

    /** Installs the display system and its public [DisplayService]. */
    override fun install(context: ModuleContext) {
        val displaySystem = DefaultDisplaySystem(MinecraftDisplayCommands())
        val displayService = DefaultDisplayService(displaySystem, displaySystem)
        val services = context.services

        services.register<DisplaySystem>(displaySystem)
        services.register<DisplayLookup>(displaySystem)
        services.register<DisplayMutationPort>(displaySystem)
        services.register<PlaybackPort>(displaySystem)
        services.register<WatchPartyPort>(displaySystem)
        services.register(DisplayServices.DISPLAY, displayService)
    }
}
