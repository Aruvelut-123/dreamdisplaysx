//? if <1.20.2 {
package com.dreamdisplays.platform.client.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics
//?} else
/*import com.dreamdisplays.platform.client.ui.compat.GuiGraphics*/
import net.minecraft.resources.ResourceLocation
import java.util.concurrent.ConcurrentHashMap

/** Pre-1.20.2 stand-in for vanilla's `WidgetSprites`. */
class WidgetSprites(
    private val enabled: ResourceLocation,
    private val disabled: ResourceLocation,
    private val enabledFocused: ResourceLocation,
) {
    fun get(enabled: Boolean, focused: Boolean): ResourceLocation =
        if (!enabled) disabled else if (focused) enabledFocused else this.enabled
}

/** Pre-1.20.2 stand-in for `GuiGraphics.blitSprite`, filling ([x], [y], [width], [height]) with [sprite]. */
fun GuiGraphics.blitSprite(sprite: ResourceLocation, x: Int, y: Int, width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    LegacyGuiSprites.resolve(sprite).draw(this, x, y, width, height)
}

private object LegacyGuiSprites {
    private val WIDGETS = ResourceLocation("textures/gui/widgets.png")
    private val SLIDER = ResourceLocation("textures/gui/slider.png")

    private val VANILLA = mapOf(
        "widget/button" to (WIDGETS to 66),
        "widget/button_disabled" to (WIDGETS to 46),
        "widget/button_highlighted" to (WIDGETS to 86),
        //? if >=1.19.4 {
        "widget/slider" to (SLIDER to 0),
        "widget/slider_highlighted" to (SLIDER to 20),
        "widget/slider_handle" to (SLIDER to 40),
        "widget/slider_handle_highlighted" to (SLIDER to 60),
        //?} else
        /*
        "widget/slider" to (WIDGETS to 46),
        "widget/slider_highlighted" to (WIDGETS to 46),
        "widget/slider_handle" to (WIDGETS to 66),
        "widget/slider_handle_highlighted" to (WIDGETS to 86),*/
    )

    private val cache = ConcurrentHashMap<ResourceLocation, Sprite>()

    fun resolve(id: ResourceLocation): Sprite = cache.computeIfAbsent(id, ::load)

    private fun load(id: ResourceLocation): Sprite {
        if (id.namespace == ResourceLocation.DEFAULT_NAMESPACE) {
            VANILLA[id.path]?.let { (sheet, v) -> return Sprite(sheet, 256, 256, 0, v, 200, 20, Border(3, 3, 3, 3)) }
        }
        val texture = ResourceLocation(id.namespace, "textures/gui/sprites/${id.path}.png")
        val resources = Minecraft.getInstance().resourceManager
        val meta = ResourceLocation(id.namespace, "textures/gui/sprites/${id.path}.png.mcmeta")
        //? if >=1.19 {
        val (w, h) = resources.getResource(texture).map { res ->
            res.open().use { NativeImage.read(it).use { img -> img.width to img.height } }
        }.orElse(16 to 16)
        val border = resources.getResource(meta)
            .map { res -> res.open().use { nineSliceBorder(JsonParser.parseReader(it.reader()).asJsonObject) } }
            .orElse(null)
        //?} else
        /*val (w, h) = runCatching {
            resources.getResource(texture).inputStream.use { NativeImage.read(it).use { img -> img.width to img.height } }
        }.getOrDefault(16 to 16)
        val border = runCatching {
            resources.getResource(meta).inputStream.use { nineSliceBorder(JsonParser.parseReader(it.reader()).asJsonObject) }
        }.getOrNull()*/
        return Sprite(texture, w, h, 0, 0, w, h, border)
    }

    private fun nineSliceBorder(meta: JsonObject): Border? {
        val scaling = meta.getAsJsonObject("gui")?.getAsJsonObject("scaling") ?: return null
        if (scaling.get("type")?.asString != "nine_slice") return null
        val border = scaling.get("border") ?: return null
        if (border.isJsonPrimitive) return border.asInt.let { Border(it, it, it, it) }
        val o = border.asJsonObject
        return Border(o.get("left").asInt, o.get("top").asInt, o.get("right").asInt, o.get("bottom").asInt)
    }

    class Border(val left: Int, val top: Int, val right: Int, val bottom: Int)

    class Sprite(
        private val texture: ResourceLocation,
        private val texW: Int,
        private val texH: Int,
        private val u: Int,
        private val v: Int,
        private val w: Int,
        private val h: Int,
        private val border: Border?,
    ) {
        fun draw(g: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
            if (border == null) {
                part(g, x, y, width, height, 0, 0, w, h)
                return
            }
            val l = minOf(border.left, width / 2)
            val r = minOf(border.right, width - l)
            val t = minOf(border.top, height / 2)
            val b = minOf(border.bottom, height - t)
            val midW = width - l - r
            val midH = height - t - b
            val srcMidW = w - border.left - border.right
            val srcMidH = h - border.top - border.bottom
            val sxR = w - r
            val syB = h - b
            part(g, x, y, l, t, 0, 0, l, t)
            part(g, x + l, y, midW, t, border.left, 0, srcMidW, t)
            part(g, x + l + midW, y, r, t, sxR, 0, r, t)
            part(g, x, y + t, l, midH, 0, border.top, l, srcMidH)
            part(g, x + l, y + t, midW, midH, border.left, border.top, srcMidW, srcMidH)
            part(g, x + l + midW, y + t, r, midH, sxR, border.top, r, srcMidH)
            part(g, x, y + t + midH, l, b, 0, syB, l, b)
            part(g, x + l, y + t + midH, midW, b, border.left, syB, srcMidW, b)
            part(g, x + l + midW, y + t + midH, r, b, sxR, syB, r, b)
        }

        private fun part(g: GuiGraphics, x: Int, y: Int, width: Int, height: Int, su: Int, sv: Int, sw: Int, sh: Int) {
            if (width <= 0 || height <= 0 || sw <= 0 || sh <= 0) return
            g.blit(texture, x, y, width, height, (u + su).toFloat(), (v + sv).toFloat(), sw, sh, texW, texH)
        }
    }
}
//?}
