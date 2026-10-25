package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.core.runtime.DreamDisplaysModule
import com.dreamdisplays.core.runtime.ModuleContext
import com.dreamdisplays.core.runtime.register
import com.dreamdisplays.platform.client.input.*

/** Installs display interaction, key binding, and input dispatch services. */
object ClientInputModule : DreamDisplaysModule {
    override val id: String = "dreamdisplays:client_input"

    override val dependencies: List<String> = [CoreDisplayModule.id]

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
