package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.core.services.DisplayService
import com.dreamdisplays.core.services.DisplayLookup
import com.dreamdisplays.core.services.DisplayMutationPort
import com.dreamdisplays.core.services.DisplaySystem
import com.dreamdisplays.core.services.keys.DisplayServices
import com.dreamdisplays.core.services.PlaybackPort
import com.dreamdisplays.core.runtime.DreamDisplaysModule
import com.dreamdisplays.core.runtime.ModuleContext
import com.dreamdisplays.core.runtime.register
import com.dreamdisplays.core.services.WatchPartyPort
import com.dreamdisplays.core.services.DefaultDisplayService
import com.dreamdisplays.core.services.DefaultDisplaySystem
import com.dreamdisplays.platform.client.displays.MinecraftDisplayCommands

/** Installs the client-side display system and its public [DisplayService]. */
object CoreDisplayModule : DreamDisplaysModule {
    override val id: String = "dreamdisplays:core_display"

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
