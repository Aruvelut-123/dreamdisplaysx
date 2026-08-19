package com.dreamdisplayx.platform.client.core.modules

import com.dreamdisplayx.api.runtime.module.DreamDisplaysXModule
import com.dreamdisplayx.api.runtime.module.ModuleContext
import com.dreamdisplayx.api.runtime.registry.service.register
import com.dreamdisplayx.platform.client.input.*

/** Installs display interaction, key binding, and input dispatch services. */
object ClientInputModule : DreamDisplaysXModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplayx:client_input"

    /** Dependencies of this module. */
    override val dependencies: List<String> = listOf(CoreDisplayModule.id)

    /** Installs the display interaction service, key binding registry, and input dispatch service. */
    override fun install(context: ModuleContext) {
        val services = context.services
        services.register<DisplayInteractionService>(MinecraftDisplayInteractionService)
        services.register<KeyBindingRegistry>(
            DefaultKeyBindingRegistry().apply { register(DisplayMenuInputHandler.OPEN_MENU_BINDING) },
        )
        services.register<InputHandler>(
            CompositeInputHandler().apply { register(DisplayMenuInputHandler()) },
        )
    }
}
