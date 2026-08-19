package com.dreamdisplayx.platform.client.render

import com.dreamdisplayx.api.render.texture.service.TextureUploaderFactory
import com.dreamdisplayx.api.render.texture.service.TextureUploaderProvider

/** Supplies a [TextureUploaderFactory] backed by [AsyncTextureUploader]. */
object DefaultTextureUploaderProvider : TextureUploaderProvider {
    override fun create(): TextureUploaderFactory = TextureUploaderFactory { AsyncTextureUploader(stateCache = it) }
}
