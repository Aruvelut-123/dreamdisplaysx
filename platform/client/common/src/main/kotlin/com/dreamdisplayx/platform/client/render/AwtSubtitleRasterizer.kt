package com.dreamdisplayx.platform.client.render

import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil

/** AWT-free result passed back to [SubtitleOverlayTexture]. */
internal data class SubtitleRaster(val width: Int, val height: Int, val argb: IntArray)

/** Optional AWT subtitle backend, loaded only after [OverlayTextSupport.available] succeeds. */
internal object AwtSubtitleRasterizer {
    private const val FONT_SIZE_PX = 34
    private const val PADDING_X = 18
    private const val PADDING_Y = 10
    private const val MAX_TEXT_WIDTH_PX = 900
    private const val MAX_LINES = 3

    fun rasterize(text: String): SubtitleRaster {
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val probeGraphics = probe.createGraphics()
        try {
            configure(probeGraphics)
            val lines = wrap(text, probeGraphics)
            val lineHeight = lineHeight(probeGraphics)
            val textWidth = lines.maxOf { measureWidth(it, probeGraphics) }
            val width = (ceil(textWidth.toDouble()).toInt() + PADDING_X * 2).coerceAtLeast(1)
            val height = (lineHeight * lines.size + PADDING_Y * 2).coerceAtLeast(1)

            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                configure(graphics)
                graphics.color = Color.BLACK
                graphics.fillRect(0, 0, width, height)
                graphics.color = Color.WHITE
                for ((index, line) in lines.withIndex()) {
                    if (line.isEmpty()) continue
                    val layout = AwtOverlayFontProvider.layout(
                        line,
                        java.awt.Font.BOLD,
                        FONT_SIZE_PX,
                        graphics.fontRenderContext,
                    )
                    val x = (width - layout.advance) / 2f
                    val baseline = PADDING_Y + index * lineHeight + layout.ascent
                    layout.draw(graphics, x, baseline)
                }
            } finally {
                graphics.dispose()
            }

            val pixels = IntArray(width * height)
            image.getRGB(0, 0, width, height, pixels, 0, width)
            return SubtitleRaster(width, height, pixels)
        } finally {
            probeGraphics.dispose()
        }
    }

    private fun configure(graphics: Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    private fun lineHeight(graphics: Graphics2D): Int =
        AwtOverlayFontProvider.layout(
            "Ag",
            java.awt.Font.BOLD,
            FONT_SIZE_PX,
            graphics.fontRenderContext,
        ).lineHeight

    private fun measureWidth(text: String, graphics: Graphics2D): Float {
        if (text.isEmpty()) return 0f
        return AwtOverlayFontProvider.layout(
            text,
            java.awt.Font.BOLD,
            FONT_SIZE_PX,
            graphics.fontRenderContext,
        ).advance.coerceAtLeast(1f)
    }

    private fun wrap(text: String, graphics: Graphics2D): List<String> {
        val out = ArrayList<String>()
        for (rawLine in text.split('\n')) {
            var current = StringBuilder()
            val words = rawLine.split(Regex("\\s+")).filter { it.isNotEmpty() }
            for (word in words) {
                for (chunk in splitLongWord(word, graphics)) {
                    val candidate = if (current.isEmpty()) chunk else "$current $chunk"
                    if (measureWidth(candidate, graphics) > MAX_TEXT_WIDTH_PX && current.isNotEmpty()) {
                        out += current.toString()
                        current = StringBuilder(chunk)
                    } else {
                        current = StringBuilder(candidate)
                    }
                }
            }
            if (current.isNotEmpty()) out += current.toString()
        }
        return out.ifEmpty { listOf("") }.take(MAX_LINES)
    }

    private fun splitLongWord(word: String, graphics: Graphics2D): List<String> {
        if (measureWidth(word, graphics) <= MAX_TEXT_WIDTH_PX) return listOf(word)
        val chunks = ArrayList<String>()
        var start = 0
        while (start < word.length) {
            var end = word.offsetByCodePoints(start, 1)
            var bestEnd = start
            while (end <= word.length && measureWidth(word.substring(start, end), graphics) <= MAX_TEXT_WIDTH_PX) {
                bestEnd = end
                if (end == word.length) break
                end = word.offsetByCodePoints(end, 1)
            }
            if (bestEnd == start) bestEnd = word.offsetByCodePoints(start, 1)
            chunks += word.substring(start, bestEnd)
            start = bestEnd
        }
        return chunks
    }
}
