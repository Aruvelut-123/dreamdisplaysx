package com.dreamdisplayx.platform.client.danmaku

import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.render.DisplayUnlitRenderTypes
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType
import net.minecraft.util.FastColor*/
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Text dimensions of one danmaku line in virtual-canvas pixels. */
data class DanmakuMetrics(val width: Float, val height: Float)

/**
 * Cached GPU glyph for one unique danmaku text: a transparent-background [DynamicTexture] rasterized
 * from AWT text, drawn as a quad by [DanmakuRenderer]. Mirrors VideoPlayer's `DanmakuTextLayoutCache`
 * (no background box — just the glyphs, like Bilibili).
 */
class DanmakuGlyph(
    val identifier: Identifier,
    val texture: DynamicTexture,
    val renderType: RenderType,
    val width: Float,
    val height: Float,
)

/**
 * AWT text layout + GPU texture cache for danmaku lines. Measures and rasterizes each unique
 * (text, color, scale) once into a small transparent [DynamicTexture], evicting least-recently-used
 * entries so a busy video cannot grow the cache without bound.
 */
object DanmakuTextLayoutCache {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/DanmakuTextLayoutCache")

    private const val MAX_ENTRIES = 2048
    private const val BASE_FONT_PX = 12

    private data class Key(val text: String, val argb: Int, val scale: Float)

    private val cache = object : LinkedHashMap<Key, DanmakuGlyph>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, DanmakuGlyph>): Boolean {
            val evict = size > MAX_ENTRIES
            if (evict) runCatching { eldest.value.texture.close() }
                .onFailure { logger.debug("Failed to close evicted danmaku texture: {}.", it.message) }
            return evict
        }
    }

    /** Measures [text] at [scale] in virtual-canvas pixels, using the same AWT metrics as rasterization. */
    fun measure(text: String, scale: Float): DanmakuMetrics {
        val safe = text.takeIf { it.isNotBlank() } ?: return DanmakuMetrics(1f, 1f)
        val metrics = layout(scale).second
        val width = metrics.stringWidth(safe).coerceAtLeast(1)
        val height = metrics.height.coerceAtLeast(1)
        return DanmakuMetrics(width.toFloat(), height.toFloat())
    }

    /** Returns the cached (or newly rasterized) glyph for [text] at [argb] color and [scale]. Render thread only. */
    fun glyph(text: String, argb: Int, scale: Float): DanmakuGlyph? {
        val safe = text.takeIf { it.isNotBlank() } ?: return null
        val key = Key(safe, argb, scale)
        cache[key]?.let { return it }
        val created = rasterize(safe, argb, scale) ?: return null
        cache[key] = created
        return created
    }

    /** Releases every cached glyph texture. Call when the owning display is unregistered. */
    fun clear() {
        cache.values.forEach { runCatching { it.texture.close() }.onFailure { } }
        cache.clear()
    }

    /** Shared (font, metrics) for both measuring and rasterizing at [scale], so they always agree. */
    private fun layout(scale: Float): Pair<Font, FontMetrics> {
        val font = Font(Font.SANS_SERIF, Font.BOLD, (BASE_FONT_PX * scale).toInt().coerceAtLeast(6))
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val metrics = probe.createGraphics().let { g ->
            g.font = font
            g.fontMetrics.also { g.dispose() }
        }
        return font to metrics
    }

    private fun rasterize(text: String, argb: Int, scale: Float): DanmakuGlyph? = runCatching {
        val (font, metrics) = layout(scale)
        val width = metrics.stringWidth(text).coerceAtLeast(1)
        val height = metrics.height.coerceAtLeast(1)

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = font
        g.color = Color(argb, true)
        g.drawString(text, 0, metrics.ascent)
        g.dispose()

        val native = toNativeImage(image)
        val id = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "dynamic/danmaku_${INSTANCE_ID.incrementAndGet()}")
        val dynamic =
            //? if >=1.21.11 {
            DynamicTexture({ "dreamdisplays-danmaku" }, native)
        //?} else
        /*DynamicTexture(native)*/
        dynamic.upload()
        Minecraft.getInstance().textureManager.register(id, dynamic)
        val renderType = DisplayUnlitRenderTypes.create("dream-displays-danmaku", id)
        DanmakuGlyph(id, dynamic, renderType, width.toFloat(), height.toFloat())
    }.onFailure { e ->
        logger.debug("Failed to rasterize danmaku text: {}.", e.message)
    }.getOrNull()

    private fun toNativeImage(image: BufferedImage): NativeImage {
        val w = image.width
        val h = image.height
        val native = NativeImage(NativeImage.Format.RGBA, w, h, false)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = image.getRGB(x, y)
                //? if >=1.21.11 {
                native.setPixel(x, y, argb)
                //?} else
                /*val a = (argb ushr 24) and 0xFF
                val r = (argb ushr 16) and 0xFF
                val gr = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                native.setPixelRGBA(x, y, FastColor.ABGR32.color(a, r, gr, b))*/
            }
        }
        return native
    }

    private val INSTANCE_ID = AtomicInteger(0)
}
