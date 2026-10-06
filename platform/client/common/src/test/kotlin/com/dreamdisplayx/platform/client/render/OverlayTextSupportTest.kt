package com.dreamdisplayx.platform.client.render

import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayTextSupportTest {
    @Test
    fun `optional backend falls back for ordinary failures but propagates fatal VM errors`() {
        assertNull(OverlayTextSupport.optionalOrNull("test") { throw IllegalStateException("unavailable") })
        assertNull(OverlayTextSupport.optionalOrNull("test") { throw NoClassDefFoundError("java/awt/Font") })
        assertFailsWith<OutOfMemoryError> {
            OverlayTextSupport.optionalOrNull("test") { throw OutOfMemoryError("synthetic") }
        }
        // Use the same operation name: rate-limited diagnostics must not suppress fatal causes.
        repeat(2) {
            assertFailsWith<OutOfMemoryError> {
                OverlayTextSupport.optionalOrNull("test") {
                    throw java.lang.reflect.InvocationTargetException(OutOfMemoryError("synthetic wrapped"))
                }
            }
        }
    }

    @Test
    fun `subtitle raster wraps CJK tokens and keeps opaque black background`() {
        assertTrue(OverlayTextSupport.available())
        val raster = AwtSubtitleRasterizer.rasterize("中文字幕😀".repeat(40))
        assertTrue(raster.width <= 936)
        assertTrue(raster.height > 20)
        assertEquals(0xFF000000.toInt(), raster.argb.first())
        assertTrue(raster.argb.any { it and 0xFFFFFF != 0 })
    }

    @Test
    fun `danmaku raster matches measurement and preserves transparent background`() {
        assertTrue(OverlayTextSupport.available())
        val text = "中文 A😀"
        val metrics = com.dreamdisplayx.platform.client.danmaku.AwtDanmakuRasterizer.measure(text, 2f)
        val raster = com.dreamdisplayx.platform.client.danmaku.AwtDanmakuRasterizer.rasterize(text, 0xFFFF0000.toInt(), 2f)
        assertEquals(metrics.width.toInt(), raster.width)
        assertEquals(metrics.height.toInt(), raster.height)
        assertTrue(raster.argb.any { it ushr 24 == 0 })
        assertTrue(raster.argb.any {
            val rgb = it and 0xFFFFFF
            it ushr 24 != 0 && (rgb ushr 16) > ((rgb ushr 8) and 0xFF) && (rgb ushr 16) > (rgb and 0xFF)
        })
    }

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

        assertTrue(first, "Desktop test JVM must support offscreen AWT; do not silently skip raster tests")
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
        assertTrue(OverlayTextSupport.available())

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
        assertTrue(OverlayTextSupport.available())

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
        assertTrue(OverlayTextSupport.available())

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
