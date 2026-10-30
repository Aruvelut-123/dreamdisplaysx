package com.dreamdisplayx.api.render.service.keys

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.render.backend.service.RenderSurface
import com.dreamdisplayx.api.render.service.DisplayRenderer
import com.dreamdisplayx.api.render.texture.service.TextureUploaderFactory
import com.dreamdisplayx.api.runtime.registry.model.ServiceKey

/**
 * Render service keys.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
object RenderServices {
    /** API surface renderer used to render registered [RenderSurface] instances. */
    val DISPLAY_RENDERER: ServiceKey<DisplayRenderer> =
        ServiceKey("dreamdisplayx:display_renderer", DisplayRenderer::class.java)

    /** Factory for creating texture uploaders on a render context. */
    val TEXTURE_UPLOADER_FACTORY: ServiceKey<TextureUploaderFactory> =
        ServiceKey("dreamdisplayx:texture_uploader_factory", TextureUploaderFactory::class.java)
}
