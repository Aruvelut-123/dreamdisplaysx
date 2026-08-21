package com.dreamdisplayx.platform.client.danmaku

//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplayx.api.display.model.settings.ClientDisplaySettings
import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.managers.ClientStateManager
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders a Bilibili danmaku overlay for one display.
 *
 * Danmaku text is drawn with AWT into a [BufferedImage], uploaded as an RGBA [NativeImage] /
 * [DynamicTexture], and drawn as a second quad on top of the video by [com.dreamdisplayx.platform.client.render.ScreenRenderer].
 * This avoids version-specific `Font` world-space rendering APIs entirely.
 *
 * Settings (opacity, font size, speed, display area, type filters) are read live from the
 * [settings] provider each frame, so the UI can update them without recreating the overlay.
 */
class DanmakuOverlay(
    private val widthBlocks: Int,
    private val heightBlocks: Int,
    private val settings: () -> ClientDisplaySettings,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/DanmakuOverlay")

    /** Logical texture resolution; aspect-corrected from the block size. */
    private val texW = (640 * widthBlocks / maxOf(1, widthBlocks)).coerceIn(320, 1280)
    private val texH = (texW * heightBlocks / maxOf(1, widthBlocks)).coerceIn(180, 720)

    private var image: NativeImage? = null
    private var texture: DynamicTexture? = null
    private var textureId: Identifier? = null
    private var renderType: RenderType? = null

    /** Cached AWT fonts keyed by pixel size (derived from base 20px x danmakuFontSize). */
    private val fontCache = HashMap<Int, Font>()
    private val metricsCache = HashMap<Int, java.awt.FontMetrics>()
    /** Tracks the last used font pixel size so we can prune stale cache entries. */
    private var lastFontPx = 20

    /** Active danmaku lines: (text, color, x position in px, y track, speed px/tick, mode kind, pixel width). */
    private enum class Kind { SCROLL, TOP, BOTTOM }

    private data class Line(
        val text: String, val color: Int, val x: Float, val y: Int,
        val speed: Float, val kind: Kind, val bornAtMillis: Long,
        val width: Int, val fontPx: Int,
    )

    private val lines = CopyOnWriteArrayList<Line>()
    private var lastTrackAssign = 0
    private var dirty = false
    /** Scroll-danmaku tracks: maps track index to the last placement time (ms). Tracks are created on demand. */
    private val scrollTracks = mutableMapOf<Int, Long>()
    /** TOP/BOTTOM danmaku tracks: maps track index to the last placement time (ms). */
    private val topBottomTracks = mutableMapOf<Int, Long>()

    init {
        ensureTexture()
    }

    /** Returns true if [msg] passes the current type and colour filters. */
    private fun shouldShow(msg: DanmakuMessage): Boolean {
        // Global client config toggle
        if (!ClientStateManager.config.danmakuEnabled) return false

        val s = settings()
        if (!s.danmakuEnabled) return false

        val kind = when (msg.mode) {
            4 -> Kind.BOTTOM
            5 -> Kind.TOP
            else -> Kind.SCROLL
        }
        return when (kind) {
            Kind.SCROLL -> s.danmakuFilterScroll
            Kind.TOP -> s.danmakuFilterTop
            Kind.BOTTOM -> s.danmakuFilterBottom
        } && (s.danmakuFilterColor || msg.color == 0xFFFFFF)
    }

    /** Pushes [msg] onto the overlay; render thread advances it during [tick]. */
    fun add(msg: DanmakuMessage) {
        if (!shouldShow(msg)) return

        val s = settings()
        val kind = when (msg.mode) {
            4 -> Kind.BOTTOM
            5 -> Kind.TOP
            else -> Kind.SCROLL
        }
        val font = fontForScale(s.danmakuFontSize)
        val fontPx = font.size
        val metrics = metricsFor(font)
        val textWidth = metrics.stringWidth(msg.text)
        val x = when (kind) {
            Kind.SCROLL -> texW.toFloat()
            else -> ((texW - textWidth) / 2f).coerceAtLeast(0f)
        }
        val y = when (kind) {
            Kind.SCROLL -> pickTrack(s.danmakuDisplayArea)
            Kind.TOP -> areaTop(s.danmakuDisplayArea) + 24 + pickVerticalTrack(Kind.TOP)
            Kind.BOTTOM -> areaBottom(s.danmakuDisplayArea) - 24 - pickVerticalTrack(Kind.BOTTOM)
        }
        val speed = when (kind) {
            Kind.SCROLL -> (3f + Math.random().toFloat() * 2f) * s.danmakuSpeed.coerceIn(0.5f, 2f)
            else -> 0f
        }
        lines += Line(
            text = msg.text, color = msg.color, x = x, y = y,
            speed = speed, kind = kind, bornAtMillis = System.currentTimeMillis(),
            width = textWidth, fontPx = fontPx,
        )
        if (lines.size > 40) lines.removeAt(0)
        dirty = true
        if (lines.size == 1) logger.info("Danmaku overlay first line added: {}", msg.text.take(30))
    }

    /** Advances scrolling; uploads the frame to GPU. Call once per client tick (main thread). */
    fun tick() {
        if (lines.isEmpty()) return
        val now = System.currentTimeMillis()
        // Build a new list instead of calling iterator.remove() on a CopyOnWriteArrayList,
        // whose iterator snapshot does NOT support remove().
        val kept = ArrayList<Line>(lines.size)
        for (line in lines) {
            when (line.kind) {
                Kind.SCROLL -> if (line.x + line.width >= -20f) {
                    kept += line.copy(x = line.x - line.speed)
                }
                Kind.TOP, Kind.BOTTOM -> if (now - line.bornAtMillis <= 5000) {
                    kept += line
                }
            }
        }
        lines.clear()
        lines += kept
        if (lines.isNotEmpty() || dirty) {
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

    // ── Display area helpers ──────────────────────────────────────────────────────────────────────

    /** Top padding of the danmaku band. */
    private fun areaTop(displayArea: Float): Int = 24

    /** Bottom pixel of the danmaku band for the given [displayArea] fraction [0,1]. */
    private fun areaBottom(displayArea: Float): Int =
        ((texH - 48) * displayArea.coerceIn(0f, 1f)).toInt().coerceIn(24, texH - 48) + 24

    /** Track height (line spacing) in pixels for the current font [scale]. */
    private fun trackHeight(scale: Float): Int {
        val px = (20 * scale.coerceIn(0.5f, 2f)).roundToInt().coerceIn(8, 48)
        // Line height = font pixel size + padding (4px top + 2px bottom = 6px)
        return px + 6
    }

    /** Returns how many scroll tracks fit in the [displayArea] band given the current font [scale]. */
    private fun scrollTrackCount(scale: Float, displayArea: Float): Int {
        val th = trackHeight(scale)
        val usable = areaBottom(displayArea) - 24
        return max(1, usable / th)
    }

    /** Picks a scroll track evenly distributed within the [displayArea] band, based on current font size. */
    private fun pickTrack(displayArea: Float): Int {
        val s = settings()
        val scale = s.danmakuFontSize.coerceIn(0.5f, 2f)
        val th = trackHeight(scale)
        val count = scrollTrackCount(scale, displayArea)
        val bottom = areaBottom(displayArea)
        val usable = (bottom - 24).coerceAtLeast(th)
        val step = usable / count
        lastTrackAssign = (lastTrackAssign + 1) % count
        // Prune stale tracks to avoid unbounded map growth
        if (scrollTracks.size > count * 2) {
            scrollTracks.keys.removeAll { it >= count }
        }
        scrollTracks[lastTrackAssign] = System.currentTimeMillis()
        return 24 + lastTrackAssign * step
    }

    /** Picks a TOP or BOTTOM track, sized by the current font [scale], with 1.5× line spacing. */
    private fun pickVerticalTrack(kind: Kind): Int {
        val s = settings()
        val scale = s.danmakuFontSize.coerceIn(0.5f, 2f)
        val th = trackHeight(scale)
        val spacing = (th * 1.5f).roundToInt()
        val now = System.currentTimeMillis()
        // Find a free track or the oldest one
        var best = 0
        var bestTime = Long.MAX_VALUE
        val maxTracks = 6
        for (i in 0 until maxTracks) {
            val t = topBottomTracks[i] ?: -1L
            if (t < 0 || now - t > 3000) {
                topBottomTracks[i] = now
                return i * spacing
            }
            if (t < bestTime) {
                bestTime = t
                best = i
            }
        }
        topBottomTracks[best] = now
        return best * spacing
    }

    // ── Font helpers ──────────────────────────────────────────────────────────────────────────────

    /** Returns an AWT [Font] whose pixel size reflects [scale] (1.0 = 20px base). */
    private fun fontForScale(scale: Float): Font {
        val px = (20 * scale.coerceIn(0.5f, 2f)).roundToInt().coerceIn(8, 48)
        // Prune stale cache entries when the font size changes, keeping only the current size
        // to prevent unbounded growth when the user rapidly scrolls through font sizes.
        if (px != lastFontPx) {
            if (fontCache.size > 3) {
                fontCache.keys.removeAll { it != px }
                metricsCache.keys.removeAll { it != px }
            }
            lastFontPx = px
        }
        return fontCache.getOrPut(px) { Font("Microsoft YaHei", Font.BOLD, px) }
    }

    /** Returns an AWT font metrics for [font], cached per pixel size. */
    private fun metricsFor(font: Font): java.awt.FontMetrics =
        metricsCache.getOrPut(font.size) {
            val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.font = font
            g.fontMetrics.also { g.dispose() }
        }

    // ── Texture management ────────────────────────────────────────────────────────────────────────

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
            val s = settings()
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, texW, texH)

            val opacity = (s.danmakuOpacity.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255)
            val outlineAlpha = (180 * opacity / 255).coerceIn(0, 255)

            for (line in lines) {
                // Each line uses its own font size (captured at creation time), so changing the
                // font setting only affects new danmaku — existing ones keep their original size.
                val lineFont = fontCache.getOrPut(line.fontPx) { Font("Microsoft YaHei", Font.BOLD, line.fontPx) }
                g.font = lineFont
                val argb = line.color
                val r = (argb shr 16) and 0xFF
                val gg = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                // Outline for readability on any video.
                g.color = Color(0, 0, 0, outlineAlpha)
                for (dx in -1..1) for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    g.drawString(line.text, line.x.toInt() + dx, line.y + dy)
                }
                g.color = Color(r, gg, b, opacity)
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