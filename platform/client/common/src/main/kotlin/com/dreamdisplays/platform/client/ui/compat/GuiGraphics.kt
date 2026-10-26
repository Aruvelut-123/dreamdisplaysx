//? if <1.20 {
package com.dreamdisplays.platform.client.ui.compat

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiComponent
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/** Pre-1.20 stand-in for vanilla's `GuiGraphics`. */
class GuiGraphics(private val stack: PoseStack) {
    fun pose(): PoseStack = stack

    fun fill(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) = GuiComponent.fill(stack, x1, y1, x2, y2, color)

    fun fillGradient(x1: Int, y1: Int, x2: Int, y2: Int, colorFrom: Int, colorTo: Int) =
        Gradient.draw(stack, x1, y1, x2, y2, colorFrom, colorTo)

    fun drawString(font: Font, text: String, x: Int, y: Int, color: Int, shadow: Boolean): Int =
        if (shadow) font.drawShadow(stack, text, x.toFloat(), y.toFloat(), color)
        else font.draw(stack, text, x.toFloat(), y.toFloat(), color)

    fun drawString(font: Font, text: Component, x: Int, y: Int, color: Int, shadow: Boolean): Int =
        if (shadow) font.drawShadow(stack, text, x.toFloat(), y.toFloat(), color)
        else font.draw(stack, text, x.toFloat(), y.toFloat(), color)

    fun drawString(font: Font, text: Component, x: Int, y: Int, color: Int): Int = drawString(font, text, x, y, color, true)

    fun blit(texture: ResourceLocation, x: Int, y: Int, u: Float, v: Float, width: Int, height: Int, texWidth: Int, texHeight: Int) {
        bind(texture)
        GuiComponent.blit(stack, x, y, u, v, width, height, texWidth, texHeight)
    }

    fun blit(
        texture: ResourceLocation, x: Int, y: Int, width: Int, height: Int,
        u: Float, v: Float, uWidth: Int, vHeight: Int, texWidth: Int, texHeight: Int,
    ) {
        bind(texture)
        GuiComponent.blit(stack, x, y, width, height, u, v, uWidth, vHeight, texWidth, texHeight)
    }

    fun setColor(r: Float, g: Float, b: Float, a: Float) = RenderSystem.setShaderColor(r, g, b, a)

    //? if >=1.19.4 {
    fun enableScissor(x1: Int, y1: Int, x2: Int, y2: Int) = GuiComponent.enableScissor(x1, y1, x2, y2)

    fun disableScissor() = GuiComponent.disableScissor()
    //?} else
    /*
    fun enableScissor(x1: Int, y1: Int, x2: Int, y2: Int) {
        val window = Minecraft.getInstance().window
        val scale = window.guiScale
        RenderSystem.enableScissor(
            (x1 * scale).toInt(), (window.height - y2 * scale).toInt(),
            ((x2 - x1).coerceAtLeast(0) * scale).toInt(), ((y2 - y1).coerceAtLeast(0) * scale).toInt(),
        )
    }

    fun disableScissor() = RenderSystem.disableScissor()*/

    /** Pre-1.20 GUI draws are immediate, so there is nothing to flush. */
    fun flush() {}

    fun renderComponentTooltip(font: Font, lines: List<Component>, x: Int, y: Int) {
        Minecraft.getInstance().screen?.renderComponentTooltip(stack, lines, x, y)
    }

    fun guiWidth(): Int = Minecraft.getInstance().window.guiScaledWidth

    fun guiHeight(): Int = Minecraft.getInstance().window.guiScaledHeight

    // Translucent textures (sprites, thumbnails) need blending
    private fun bind(texture: ResourceLocation) {
        RenderSystem.setShaderTexture(0, texture)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
    }

    private object Gradient : GuiComponent() {
        fun draw(stack: PoseStack, x1: Int, y1: Int, x2: Int, y2: Int, from: Int, to: Int) =
            fillGradient(stack, x1, y1, x2, y2, from, to)
    }
}
//?}
