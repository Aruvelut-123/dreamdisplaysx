package com.dreamdisplayx.api.render.texture.service

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/** Creates [TextureUploaderService] instances per GL context (popout window, PiP overlay, etc.). */
@DreamDisplaysXUnstableApi
fun interface TextureUploaderFactory {
    /** @param stateCache true to route GL calls through Minecraft's cached `GlStateManager`. */
    fun create(stateCache: Boolean): TextureUploaderService
}
