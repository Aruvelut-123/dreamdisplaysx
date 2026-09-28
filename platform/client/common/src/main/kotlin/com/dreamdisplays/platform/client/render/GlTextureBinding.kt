package com.dreamdisplays.platform.client.render

//? if >=1.21.11 <26.3 {
import com.mojang.blaze3d.opengl.GlStateManager
//?}
//? if <1.21.11 {
import com.mojang.blaze3d.platform.GlStateManager
//?}
import org.lwjgl.opengl.GL11

/**
 * Binds a `GL_TEXTURE_2D` without desynchronising Minecraft's texture binding cache.
 *
 * @see <a href="https://github.com/arnodoelinger/dreamdisplays/issues/229">Game crash when open the video control GUI on Linux issue</a>
 */
internal fun bindTexture2D(stateCache: Boolean, id: Int) {
    if (!stateCache) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
        return
    }
    GlStateManager._bindTexture(0)
    GlStateManager._bindTexture(id)
}
