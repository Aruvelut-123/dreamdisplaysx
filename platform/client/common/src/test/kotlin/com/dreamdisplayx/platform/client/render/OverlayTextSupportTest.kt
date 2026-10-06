package com.dreamdisplayx.platform.client.render

import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayTextSupportTest {
    @Test
    fun `fallback metrics count Unicode code points`() {
        val (width, height) = OverlayTextSupport.fallbackMetrics("中😀", 20)

        assertEquals(36f, width)
        assertEquals(20f, height)
    }

    @Test
    fun `runtime probe is fail closed and repeatable`() {
        val first = OverlayTextSupport.available()
        val second = OverlayTextSupport.available()

        assertEquals(first, second)
    }

    @Test
    fun `runtime probe fails closed when java desktop is absent`() {
        val hidingLoader = object : ClassLoader(OverlayTextSupport::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("java.awt.")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }

        assertFalse(OverlayTextSupport.probe(hidingLoader))
    }

    @Test
    fun `logical composite remains preferred when it covers the whole Unicode string`() {
        if (!OverlayTextSupport.available()) return

        val logical = Font(Font.SANS_SERIF, Font.BOLD, 24)
        val text = "Latin العربية עברית हिन्दी 中文 😀"
        val runs = AwtOverlayFontProvider.fontRuns(text, logical)

        if (logical.canDisplayUpTo(text) == -1) {
            assertEquals(1, runs.size)
            assertEquals(logical, runs.single().font)
        } else {
            assertTrue(runs.isNotEmpty())
            assertEquals(text.length, runs.sumOf { it.endExclusive - it.start })
            for (run in runs) {
                var offset = run.start
                while (offset < run.endExclusive) {
                    val codePoint = text.codePointAt(offset)
                    if (logical.canDisplay(codePoint)) {
                        assertEquals(logical, run.font)
                    }
                    offset += Character.charCount(codePoint)
                }
            }
        }
    }

    @Test
    fun `font runs never split a surrogate pair and keep mixed scripts covered`() {
        if (!OverlayTextSupport.available()) return

        val logical = Font(Font.SANS_SERIF, Font.PLAIN, 20)
        val text = "A😀中 العربية हिन्दी"
        val runs = AwtOverlayFontProvider.fontRuns(text, logical)

        assertTrue(runs.isNotEmpty())
        assertEquals(0, runs.first().start)
        assertEquals(text.length, runs.last().endExclusive)
        for ((previous, current) in runs.zipWithNext()) {
            assertEquals(previous.endExclusive, current.start)
        }
        for (run in runs) {
            assertTrue(run.start == 0 || !Character.isLowSurrogate(text[run.start]))
            assertTrue(run.endExclusive == text.length || !Character.isLowSurrogate(text[run.endExclusive]))
        }
    }

    @Test
    fun `shared Unicode layout measures and draws real pixels`() {
        if (!OverlayTextSupport.available()) return

        val image = BufferedImage(512, 96, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val layout = AwtOverlayFontProvider.layout(
                "A😀 中文 العربية हिन्दी",
                Font.BOLD,
                28,
                graphics.fontRenderContext,
            )
            assertTrue(layout.advance > 0f)
            layout.draw(graphics, 4f, layout.ascent + 4f)
            assertTrue((0 until image.width).any { x -> (0 until image.height).any { y -> image.getRGB(x, y) ushr 24 != 0 } })
        } finally {
            graphics.dispose()
        }
    }
}
