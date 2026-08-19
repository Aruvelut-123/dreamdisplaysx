package com.dreamdisplayx.platform.client.core.modules

import com.dreamdisplayx.api.render.service.keys.RenderServices
import com.dreamdisplayx.api.runtime.module.DreamDisplaysXModule
import com.dreamdisplayx.api.runtime.module.ModuleContext
import com.dreamdisplayx.api.runtime.registry.service.register
import com.dreamdisplayx.platform.client.render.*

/** Installs client render services, API surface renderer, and texture uploader factory. */
object ClientRenderModule : DreamDisplaysXModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplayx:client_render"

    /** Installs the render service, API surface renderer, and texture uploader factory. */
    override fun install(context: ModuleContext) {
        val services = context.services
        services.register<ClientRenderService>(ScreenRenderer)
        services.register(RenderServices.DISPLAY_RENDERER, DefaultRendererProvider.create())
        services.register(RenderServices.TEXTURE_UPLOADER_FACTORY, DefaultTextureUploaderProvider.create())
        services.register<RenderHook>(RenderHook { renderContext ->
            services.getOrNull(RenderServices.DISPLAY_RENDERER)?.takeIf { it.registeredCount > 0 }
                ?.renderAll(renderContext)
        })
    }
}
