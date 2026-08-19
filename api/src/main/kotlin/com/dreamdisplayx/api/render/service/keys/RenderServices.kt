package com.dreamdisplayx.api.render.service.keys

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.render.backend.service.RenderSurface
import com.dreamdisplayx.api.render.service.DisplayRenderer
import com.dreamdisplayx.api.render.texture.service.TextureUploaderFactory
import com.dreamdisplayx.api.runtime.registry.model.ServiceKey
import com.dreamdisplayx.api.runtime.registry.model.serviceKey

/**
 * Render service keys.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
object RenderServices {
    /** API surface renderer used to render registered [RenderSurface] instances. */
    val DISPLAY_RENDERER: ServiceKey<DisplayRenderer> = serviceKey("dreamdisplayx:display_renderer")

    /** Factory for creating texture uploaders on a render context. */
    val TEXTURE_UPLOADER_FACTORY: ServiceKey<TextureUploaderFactory> =
        serviceKey("dreamdisplayx:texture_uploader_factory")
}
