package com.dreamdisplayx.platform.server.registrar

import net.minecraftforge.event.RegisterCommandsEvent

/** `Forge` event-registration adapter for [VanillaCommandTree]. */
object ForgeCommandRegistrar {
    /** Registers the `/display` command tree against a `RegisterCommandsEvent`. */
    fun register(event: RegisterCommandsEvent) {
        event.dispatcher.root.addChild(VanillaCommandTree.build())
    }
}
