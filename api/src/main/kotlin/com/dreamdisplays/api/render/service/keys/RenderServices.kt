package com.dreamdisplays.api.render.service.keys

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.render.backend.service.RenderSurface
import com.dreamdisplays.api.render.service.DisplayRenderer
import com.dreamdisplays.api.render.texture.service.TextureUploaderFactory
import com.dreamdisplays.api.runtime.registry.model.ServiceKey

/**
 * Render service keys.
 *
 * @since 1.8.x
 */
@Unstable
object RenderServices {
    /** API surface renderer used to render registered [RenderSurface] instances. */
    val DISPLAY_RENDERER: ServiceKey<DisplayRenderer> =
        ServiceKey("dreamdisplays:display_renderer", DisplayRenderer::class.java)

    /** Factory for creating texture uploaders on a render context. */
    val TEXTURE_UPLOADER_FACTORY: ServiceKey<TextureUploaderFactory> =
        ServiceKey("dreamdisplays:texture_uploader_factory", TextureUploaderFactory::class.java)
}
