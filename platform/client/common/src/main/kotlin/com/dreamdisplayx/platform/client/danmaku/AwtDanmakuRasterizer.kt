package com.dreamdisplayx.platform.client.danmaku

import com.dreamdisplayx.platform.client.render.AwtOverlayFontProvider
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil

/** AWT-free pixel result consumed by the Minecraft texture wrapper. */
internal data class DanmakuRaster(val width: Int, val height: Int, val argb: IntArray)

/** Optional AWT backend for danmaku measurement and rasterization. */
internal object AwtDanmakuRasterizer {
    private const val BASE_FONT_PX = 12

    fun measure(text: String, scale: Float): DanmakuMetrics {
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val graphics = probe.createGraphics()
        try {
            configure(graphics)
            val layout = layout(text, scale, graphics)
            return DanmakuMetrics(
                ceil(layout.advance.toDouble()).toFloat().coerceAtLeast(1f),
                layout.lineHeight.toFloat().coerceAtLeast(1f),
            )
        } finally {
            graphics.dispose()
        }
    }

    fun rasterize(text: String, argb: Int, scale: Float): DanmakuRaster {
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val probeGraphics = probe.createGraphics()
        val width: Int
        val height: Int
        try {
            configure(probeGraphics)
            val measured = layout(text, scale, probeGraphics)
            width = ceil(measured.advance.toDouble()).toInt().coerceAtLeast(1)
            height = measured.lineHeight.coerceAtLeast(1)
        } finally {
            probeGraphics.dispose()
        }

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            configure(graphics)
            graphics.color = Color(argb, true)
            val drawn = layout(text, scale, graphics)
            drawn.draw(graphics, 0f, drawn.ascent)
        } finally {
            graphics.dispose()
        }

        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)
        return DanmakuRaster(width, height, pixels)
    }

    private fun configure(graphics: Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    private fun layout(text: String, scale: Float, graphics: Graphics2D): AwtOverlayFontProvider.OverlayLayout =
        AwtOverlayFontProvider.layout(
            text,
            java.awt.Font.BOLD,
            (BASE_FONT_PX * scale).toInt().coerceAtLeast(6),
            graphics.fontRenderContext,
        )
}
