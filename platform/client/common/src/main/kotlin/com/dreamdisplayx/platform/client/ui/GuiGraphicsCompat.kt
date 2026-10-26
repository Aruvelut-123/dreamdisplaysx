package com.dreamdisplayx.platform.client.ui

import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor

//?} else
/*import net.minecraft.client.gui.GuiGraphics*/

//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation
//?}

/**
 * Version-neutral alias for the per-frame GUI draw target. Minecraft 26+ draws into a [GuiGraphicsExtractor];
 * older versions use the vanilla `GuiGraphics` type directly.
 */
//? if >=26 {
typealias GuiGraphicsCompat = GuiGraphicsExtractor
//?} else
/*typealias GuiGraphicsCompat = GuiGraphics*/

/**
 * Draws a single line of [text] with [font] at ([x], [y]) in ARGB [color], optionally with a drop
 * [shadow]. Maps to `text()` on 26+ and `drawString()` on pre-26. The one call that was responsible
 * for the bulk of the duplicated render code.
 */
//? if >=26 {
fun GuiGraphicsCompat.drawText(font: Font, text: String, x: Int, y: Int, color: Int, shadow: Boolean) {
    this.text(font, text, x, y, color, shadow)
}
//?} else
/*fun GuiGraphicsCompat.drawText(font: Font, text: String, x: Int, y: Int, color: Int, shadow: Boolean) {
    this.drawString(font, text, x, y, color, shadow)
}*/

/** [Component] overload of [drawText]: `text()` on 26+, `drawString()` on pre-26. */
//? if >=26 {
fun GuiGraphicsCompat.drawText(font: Font, text: Component, x: Int, y: Int, color: Int, shadow: Boolean) {
    this.text(font, text, x, y, color, shadow)
}
//?} else
/*fun GuiGraphicsCompat.drawText(font: Font, text: Component, x: Int, y: Int, color: Int, shadow: Boolean) {
    this.drawString(font, text, x, y, color, shadow)
}*/

//? if <1.21.11 {
fun GuiGraphicsCompat.enableScissorPoseAware(x1: Int, y1: Int, x2: Int, y2: Int) {
    val m = pose().last().pose()
    val p1 = m.transformPosition(org.joml.Vector3f(x1.toFloat(), y1.toFloat(), 0f))
    val p2 = m.transformPosition(org.joml.Vector3f(x2.toFloat(), y2.toFloat(), 0f))
    enableScissor(p1.x.toInt(), p1.y.toInt(), p2.x.toInt(), p2.y.toInt())
}

/**
 * Draws a GUI sprite scaled into a `w` x `h` rectangle.
 *
 * Pre-1.21 `GuiGraphics` has no `blitSprite`; the equivalent is `blit` over the whole 16x16 GUI
 * atlas cell these widget sprites are authored at, which is what the vanilla widget code passed
 * implicitly. Keeping the call sites on this name lets one branch serve both eras.
 */
fun GuiGraphicsCompat.blitSpriteCompat(sprite: ResourceLocation, x: Int, y: Int, w: Int, h: Int) {
    blit(sprite, x, y, 0f, 0f, w, h, 16, 16)
}
//?}
