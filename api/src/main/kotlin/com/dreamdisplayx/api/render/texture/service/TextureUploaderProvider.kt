package com.dreamdisplayx.api.render.texture.service

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Supplies the [TextureUploaderFactory].
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
fun interface TextureUploaderProvider {
    /** Creates the factory instance. */
    fun create(): TextureUploaderFactory
}
