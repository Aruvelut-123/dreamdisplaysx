package com.dreamdisplayx.platform.client.render

import org.slf4j.LoggerFactory
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.FileInputStream

/**
 * Runtime-safe AWT text support for subtitle and danmaku textures.
 *
 * Pojav/FCL exposes a Cacio-backed `java.desktop` module, while some Android launchers do not. The
 * caller checks [available] before touching the AWT classes so the optional overlay never becomes a
 * hard Android startup dependency.
 */
internal object OverlayTextSupport {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/OverlayTextSupport")

    private const val CJK_PROBE = '\u4E2D'
    private const val BUNDLED_FONT_RESOURCE = "/assets/dreamdisplayx/fonts/NotoSansCJK-Regular.ttc"

    /** True when the current launcher exposes the AWT classes required by the rasterizer. */
    fun available(): Boolean = awtAvailable

    private val awtAvailable: Boolean by lazy {
        runCatching {
            Class.forName("java.awt.Font", false, OverlayTextSupport::class.java.classLoader)
            Class.forName("java.awt.image.BufferedImage", false, OverlayTextSupport::class.java.classLoader)
            true
        }.getOrDefault(false)
    }

    /** Returns a size/style-adjusted font with a real CJK-capable face when one is installed. */
    fun font(style: Int, sizePx: Int): Font = baseFont.deriveFont(style, sizePx.coerceAtLeast(1).toFloat())

    /** Approximate metrics used only when AWT is unavailable and the overlay must stay disabled. */
    fun fallbackMetrics(text: String, sizePx: Int): Pair<Float, Float> {
        val width = text.codePointCount(0, text.length) * sizePx.coerceAtLeast(1) * 0.9f
        return width.coerceAtLeast(1f) to sizePx.coerceAtLeast(1).toFloat()
    }

    private val baseFont: Font by lazy {
        runCatching { loadCjkFont() }.getOrElse {
            logger.warn("CJK overlay font initialization failed; using logical SansSerif.", it)
            Font(Font.SANS_SERIF, Font.PLAIN, 12)
        }
    }

    private fun loadCjkFont(): Font {
        val candidates = linkedSetOf<String>()
        System.getProperty("dreamdisplayx.fontFile")?.trim()?.takeIf { it.isNotEmpty() }?.let(candidates::add)
        candidates += listOf(
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/NotoSansCJK-Regular.otf",
            "/system/fonts/NotoSansCJKsc-VF.ttf",
            "/system/fonts/DroidSansFallback.ttf",
            "/data/fonts/NotoSansCJK-Regular.ttc",
        )
        val javaHome = System.getProperty("java.home")?.takeIf { it.isNotBlank() }
        if (javaHome != null) {
            candidates += "$javaHome/lib/fonts/NotoSansCJK-Regular.ttc"
            candidates += "$javaHome/lib/fonts/NotoSansSC-Regular.otf"
            candidates += "$javaHome/lib/fonts/DroidSansFallback.ttf"
        }

        for (path in candidates) {
            loadFont(File(path))?.let { font ->
                if (font.canDisplay(CJK_PROBE)) {
                    logger.info("Using CJK overlay font {}.", path)
                    return font
                }
            }
        }

        runCatching {
            OverlayTextSupport::class.java.getResourceAsStream(BUNDLED_FONT_RESOURCE)?.use { input ->
                Font.createFonts(input).firstOrNull { it.canDisplay(CJK_PROBE) }
            }
        }.getOrNull()?.let { font ->
            logger.info("Using bundled CJK overlay font {}.", BUNDLED_FONT_RESOURCE)
            return font
        }

        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.firstOrNull { it.canDisplay(CJK_PROBE) }
        }.getOrNull()?.let { font ->
            logger.info("Using registered CJK overlay font {}.", font.family)
            return font
        }

        logger.warn("No CJK-capable overlay font found; falling back to the logical SansSerif font.")
        return Font(Font.SANS_SERIF, Font.PLAIN, 12)
    }

    private fun loadFont(file: File): Font? {
        if (!file.isFile || !file.canRead()) return null
        return runCatching {
            FileInputStream(file).use { input ->
                Font.createFonts(input).firstOrNull { it.canDisplay(CJK_PROBE) }
            }
        }.getOrNull()
    }
}
