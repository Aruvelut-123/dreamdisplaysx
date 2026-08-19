package com.dreamdisplayx.platform.client.danmaku

//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.render.DisplayUnlitRenderTypes
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Renders a Bilibili danmaku overlay for one display.
 *
 * Danmaku text is drawn with AWT into a [BufferedImage], uploaded as an RGBA [NativeImage] /
 * [DynamicTexture], and drawn as a second quad on top of the video by [com.dreamdisplayx.platform.client.render.ScreenRenderer].
 * This avoids version-specific `Font` world-space rendering APIs entirely.
 */
class DanmakuOverlay(private val widthBlocks: Int, private val heightBlocks: Int) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/DanmakuOverlay")

    /** Logical texture resolution; aspect-corrected from the block size. */
    private val texW = (640 * widthBlocks / maxOf(1, widthBlocks)).coerceIn(320, 1280)
    private val texH = (texW * heightBlocks / maxOf(1, widthBlocks)).coerceIn(180, 720)

    private var image: NativeImage? = null
    private var texture: DynamicTexture? = null
    private var textureId: Identifier? = null
    private var renderType: RenderType? = null

    /** Active danmaku lines: (text, color, x position in px, y track, speed px/tick). */
    private data class Line(val text: String, val color: Int, val x: Float, val y: Int, val speed: Float)

    private val lines = CopyOnWriteArrayList<Line>()
    private var lastTrackAssign = 0
    private var dirty = false
    private val trackCount = 8

    init {
        ensureTexture()
    }

    /** Pushes [msg] onto the overlay; render thread advances it during [tick]. */
    fun add(msg: DanmakuMessage) {
        lines += Line(
            text = msg.text,
            color = msg.color,
            x = texW.toFloat(),
            y = pickTrack(),
            speed = (3f + Math.random().toFloat() * 2f),
        )
        if (lines.size > 40) lines.removeAt(0)
        dirty = true
        if (lines.size == 1) logger.info("Danmaku overlay first line added: {}", msg.text.take(30))
    }

    /** Advances scrolling; uploads the frame to GPU. Call once per client tick (main thread). */
    fun tick() {
        if (lines.isEmpty()) return
        val it = lines.iterator()
        while (it.hasNext()) {
            val line = it.next()
            if (line.x + line.text.length * 14f < -20f) it.remove() else {
                lines[lines.indexOf(line)] = line.copy(x = line.x - line.speed)
            }
        }
        dirty = true
        if (dirty) {
            upload()
            dirty = false
        }
    }

    /** The [RenderType] for the overlay quad, or null if unavailable. */
    fun overlayRenderType(): RenderType? = renderType

    /** Releases GPU resources. */
    fun dispose() {
        val manager = net.minecraft.client.Minecraft.getInstance().textureManager
        texture?.close()
        textureId?.let { runCatching { manager.release(it) } }
        texture = null
        image?.close()
        image = null
    }

    private fun pickTrack(): Int {
        lastTrackAssign = (lastTrackAssign + 1) % trackCount
        return 24 + lastTrackAssign * 26
    }

    private fun ensureTexture() {
        if (texture != null) return
        val img = NativeImage(NativeImage.Format.RGBA, texW, texH, false)
        //? if >=1.21.11 {
        val tex = DynamicTexture({ "dreamdisplayx:danmaku" }, img)
        val id = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "danmaku")
        //?} else
        /*val tex = DynamicTexture(img)
        val id = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "danmaku")*/
        val rt = DisplayUnlitRenderTypes.create("dreamdisplayx_danmaku", id)
        image = img
        texture = tex
        textureId = id
        renderType = rt
        net.minecraft.client.Minecraft.getInstance().textureManager.register(id, tex)
        logger.debug("Danmaku overlay allocated {}x{}", texW, texH)
    }

    private fun upload() {
        ensureTexture()
        val img = image ?: return
        val buf = BufferedImage(texW, texH, BufferedImage.TYPE_INT_ARGB)
        val g = buf.createGraphics() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.font = Font("Microsoft YaHei", Font.BOLD, 20)
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, texW, texH)
            for (line in lines) {
                val argb = line.color
                val r = (argb shr 16) and 0xFF
                val gg = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                // Outline for readability on any video.
                g.color = Color(0, 0, 0, 180)
                for (dx in -1..1) for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    g.drawString(line.text, line.x.toInt() + dx, line.y + dy)
                }
                g.color = Color(r, gg, b, 255)
                g.drawString(line.text, line.x.toInt(), line.y)
            }
        } finally {
            g.dispose()
        }

        for (y in 0 until texH) {
            for (x in 0 until texW) {
                val argb = buf.getRGB(x, y)
                val abgr = ((argb and 0xFF) shl 16) or (argb and 0xFF00) or ((argb ushr 16) and 0xFF) or (argb and 0xFF000000.toInt())
                //? if >=1.21.11 {
                img.setPixelABGR(x, y, abgr)
                //?} else
                /*img.setPixelRGBA(x, y, abgr)*/
            }
        }
        texture?.upload()
    }
}