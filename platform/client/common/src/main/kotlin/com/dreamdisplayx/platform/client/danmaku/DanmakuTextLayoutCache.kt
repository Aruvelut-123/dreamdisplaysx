package com.dreamdisplayx.platform.client.danmaku

import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.render.DisplayUnlitRenderTypes
import com.dreamdisplayx.platform.client.render.OverlayTextSupport
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
    private const val MAX_ENTRIES = 2048
    private const val BASE_FONT_PX = 12

    private data class Key(val text: String, val argb: Int, val scale: Float)

    private val cache = object : LinkedHashMap<Key, DanmakuGlyph>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, DanmakuGlyph>): Boolean {
            val evict = size > MAX_ENTRIES
            if (evict) OverlayTextSupport.optionalOrNull("evicted danmaku texture close") {
                eldest.value.texture.close()
            }
            return evict
        }
    }

    /** Measures [text] at [scale] in virtual-canvas pixels, using the same AWT metrics as rasterization. */
    fun measure(text: String, scale: Float): DanmakuMetrics {
        val safe = text.takeIf { it.isNotBlank() } ?: return DanmakuMetrics(1f, 1f)
        if (!OverlayTextSupport.available()) {
            val (width, height) = OverlayTextSupport.fallbackMetrics(safe, (BASE_FONT_PX * scale).toInt())
            return DanmakuMetrics(width, height)
        }
        return OverlayTextSupport.optionalOrNull("danmaku measurement") {
            AwtDanmakuRasterizer.measure(safe, scale)
        } ?: run {
            val (width, height) = OverlayTextSupport.fallbackMetrics(safe, (BASE_FONT_PX * scale).toInt())
            DanmakuMetrics(width, height)
        }
    }

    /** Returns the cached (or newly rasterized) glyph for [text] at [argb] color and [scale]. Render thread only. */
    fun glyph(text: String, argb: Int, scale: Float): DanmakuGlyph? {
        val safe = text.takeIf { it.isNotBlank() } ?: return null
        if (!OverlayTextSupport.available()) return null
        val key = Key(safe, argb, scale)
        cache[key]?.let { return it }
        val created = rasterize(safe, argb, scale) ?: return null
        cache[key] = created
        return created
    }

    /** Releases every cached glyph texture. Call when the owning display is unregistered. */
    fun clear() {
        cache.values.forEach { glyph ->
            OverlayTextSupport.optionalOrNull("danmaku texture close") { glyph.texture.close() }
        }
        cache.clear()
    }

    private fun rasterize(text: String, argb: Int, scale: Float): DanmakuGlyph? =
        OverlayTextSupport.optionalOrNull("danmaku rasterization") {
            val raster = AwtDanmakuRasterizer.rasterize(text, argb, scale)
            val native = toNativeImage(raster)
            val id = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "dynamic/danmaku_${INSTANCE_ID.incrementAndGet()}")
            var dynamic: DynamicTexture? = null
            try {
                val created =
                    //? if >=1.21.11 {
                    DynamicTexture({ "dreamdisplays-danmaku" }, native)
                //?} else
                /*DynamicTexture(native)*/
                dynamic = created
                created.upload()
                Minecraft.getInstance().textureManager.register(id, created)
                val renderType = DisplayUnlitRenderTypes.create("dream-displays-danmaku", id)
                DanmakuGlyph(id, created, renderType, raster.width.toFloat(), raster.height.toFloat())
            } catch (error: Exception) {
                dynamic?.let { texture ->
                    OverlayTextSupport.optionalOrNull("danmaku texture cleanup") { texture.close() }
                }
                throw error
            } catch (error: LinkageError) {
                dynamic?.let { texture ->
                    OverlayTextSupport.optionalOrNull("danmaku texture cleanup") { texture.close() }
                }
                throw error
            }
        }

    private fun toNativeImage(raster: DanmakuRaster): NativeImage {
        val w = raster.width
        val h = raster.height
        val native = NativeImage(NativeImage.Format.RGBA, w, h, false)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = raster.argb[y * w + x]
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
