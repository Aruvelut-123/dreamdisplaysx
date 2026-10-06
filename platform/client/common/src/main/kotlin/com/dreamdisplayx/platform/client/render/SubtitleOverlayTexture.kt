package com.dreamdisplayx.platform.client.render

import com.dreamdisplayx.platform.client.Initializer
import com.mojang.blaze3d.platform.NativeImage
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType
import net.minecraft.util.FastColor*/
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import java.util.concurrent.atomic.AtomicInteger

/**
 * Rasterizes the current subtitle line into a small opaque GPU texture (a dark rounded-ish box
 * behind white text, sized to the wrapped text itself) that [ScreenRenderer] draws as a quad over
 * the bottom of the display.
 *
 * Regenerated only when the text changes. Rendered with plain AWT text layout.
 */
class SubtitleOverlayTexture {
    private var lastText: String? = null
    private var identifier: Identifier? = null
    private var texture: DynamicTexture? = null
    private var cachedRenderType: RenderType? = null

    var aspectRatio: Float = 1f
        private set

    /** Updates the texture if [text] differs from what's currently baked. Render thread only. */
    fun update(text: String?) {
        // Some Android launchers expose Cacio-backed java.desktop while others do not. Keep the
        // optional overlay safe on both: FCL/Pojav can render it, and bare runtimes simply skip it.
        val normalized = text?.takeIf { it.isNotBlank() }
        if (normalized == lastText) return
        if (normalized == null) {
            lastText = null
            releaseTexture()
            cachedRenderType = null
            return
        }
        if (!OverlayTextSupport.available()) {
            lastText = normalized
            releaseTexture()
            cachedRenderType = null
            return
        }

        val raster = OverlayTextSupport.optionalOrNull("subtitle rasterization") {
            AwtSubtitleRasterizer.rasterize(normalized)
        } ?: run {
            // Do not retry a permanently unavailable AWT path on every render tick, and never keep
            // displaying the previous cue after the new one failed to rasterize.
            lastText = normalized
            releaseTexture()
            cachedRenderType = null
            return
        }
        val id = identifier ?: Identifier.fromNamespaceAndPath(
            Initializer.MOD_ID,
            "dynamic/subtitle_${INSTANCE_ID.incrementAndGet()}",
        ).also { identifier = it }
        releaseTexture()
        cachedRenderType = null
        aspectRatio = raster.width.toFloat() / raster.height.toFloat()

        val dynamic = OverlayTextSupport.optionalOrNull("subtitle texture upload") {
            val native = toNativeImage(raster)
            val created =
                //? if >=1.21.11 {
                DynamicTexture({ "dreamdisplayx-subtitle" }, native)
            //?} else
                /*DynamicTexture(native)*/
            try {
                created.upload()
                Minecraft.getInstance().textureManager.register(id, created)
                created
            } catch (error: Exception) {
                closeTextureAfterFailure(created)
                throw error
            } catch (error: LinkageError) {
                closeTextureAfterFailure(created)
                throw error
            }
        } ?: run {
            lastText = normalized
            return
        }
        lastText = normalized
        texture = dynamic
    }

    /** True while a cue is currently baked and ready to draw. */
    @Suppress("UNUSED")
    fun hasContent(): Boolean = texture != null

    /** The unlit [RenderType] sampling the current texture, or null when there's nothing to show. */
    fun renderType(): RenderType? {
        val id = identifier ?: return null
        if (texture == null) return null
        return cachedRenderType ?: DisplayUnlitRenderTypes.create("dream-displays-subtitle", id).also { cachedRenderType = it }
    }

    /** Releases the GPU texture. Call once when the owning display is unregistered. */
    fun dispose() {
        releaseTexture()
        cachedRenderType = null
        lastText = null
    }

    /** Removes the identifier from Minecraft's texture manager as well as closing the GL resource. */
    private fun releaseTexture() {
        val old = texture ?: return
        OverlayTextSupport.optionalOrNull("subtitle texture release") {
            identifier?.let { Minecraft.getInstance().textureManager.release(it) }
        }
        // TextureManager.release normally closes the registered texture, but close explicitly as a
        // fallback for reload paths where the manager has already dropped the identifier entry.
        OverlayTextSupport.optionalOrNull("subtitle texture close") { old.close() }
        texture = null
    }

    private fun closeTextureAfterFailure(texture: DynamicTexture) {
        OverlayTextSupport.optionalOrNull("subtitle texture cleanup") { texture.close() }
    }

    private fun toNativeImage(raster: SubtitleRaster): NativeImage {
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

    companion object {
        private val INSTANCE_ID = AtomicInteger(0)
    }
}
