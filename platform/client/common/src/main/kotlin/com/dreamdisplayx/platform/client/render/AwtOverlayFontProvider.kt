package com.dreamdisplayx.platform.client.render

import org.slf4j.LoggerFactory
import java.awt.Font
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.io.File
import java.io.FileInputStream
import java.text.AttributedString
import kotlin.math.ceil

/** AWT-only font loader. Instantiate this backend only after [OverlayTextSupport.available]. */
internal object AwtOverlayFontProvider {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/AwtOverlayFontProvider")
    private const val CJK_PROBE = '\u4E2D'

    /** One code-point-safe font run used by the shared measurement and drawing layout. */
    internal data class FontRun(val start: Int, val endExclusive: Int, val font: Font)

    /** Shared shaped text layout; the same object can be measured and drawn with matching metrics. */
    internal class OverlayLayout internal constructor(private val delegate: TextLayout) {
        val advance: Float
            get() = delegate.advance
        val ascent: Float
            get() = delegate.ascent
        val lineHeight: Int
            get() = ceil((delegate.ascent + delegate.descent + delegate.leading).toDouble())
                .toInt()
                .coerceAtLeast(1)

        fun draw(graphics: Graphics2D, x: Float, baseline: Float) {
            delegate.draw(graphics, x, baseline)
        }
    }

    /**
     * Selects the logical composite for text it can display. A physical CJK face is returned only
     * when the whole string is one fallback run; mixed strings are shaped through [layout] so a CJK
     * fallback cannot replace Arabic, Hebrew, Devanagari, emoji, or any other logical-font run.
     */
    fun fontFor(text: String, style: Int, sizePx: Int): Font {
        val logical = logicalFont(style, sizePx)
        val runs = fontRuns(text, logical)
        return if (runs.size == 1) runs[0].font else logical
    }

    /** Builds Unicode-safe runs while preserving the logical composite wherever it has coverage. */
    internal fun fontRuns(text: String, style: Int, sizePx: Int): List<FontRun> =
        fontRuns(text, logicalFont(style, sizePx))

    /** Package-visible overload keeps font-run selection deterministic in JVM tests. */
    internal fun fontRuns(text: String, logical: Font, cjk: Font? = null): List<FontRun> {
        if (text.isEmpty()) return emptyList()
        if (logical.canDisplayUpTo(text) == -1) {
            return listOf(FontRun(0, text.length, logical))
        }

        val fallback = cjk ?: missingCjkFont(text, logical)
        val runs = ArrayList<FontRun>()
        var start = 0
        var selected: Font? = null
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val next = offset + Character.charCount(codePoint)
            val font = when {
                logical.canDisplay(codePoint) -> logical
                isCjk(codePoint) && fallback?.canDisplay(codePoint) == true -> fallback
                else -> logical
            }
            if (selected == null) {
                selected = font
                start = offset
            } else if (selected != font) {
                runs += FontRun(start, offset, selected)
                start = offset
                selected = font
            }
            offset = next
        }
        selected?.let { runs += FontRun(start, text.length, it) }
        return runs
    }

    /** Creates a shaped layout whose metrics and drawing use exactly the same font runs. */
    internal fun layout(text: String, style: Int, sizePx: Int, frc: FontRenderContext): OverlayLayout {
        require(text.isNotEmpty()) { "Text layout cannot be empty" }
        val logical = logicalFont(style, sizePx)
        val runs = fontRuns(text, logical)
        val attributed = AttributedString(text)
        for (run in runs) {
            attributed.addAttribute(TextAttribute.FONT, run.font, run.start, run.endExclusive)
        }
        return OverlayLayout(TextLayout(attributed.getIterator(), frc))
    }

    private fun logicalFont(style: Int, sizePx: Int): Font =
        Font(Font.SANS_SERIF, style, sizePx.coerceAtLeast(1)).deriveFont(
            style,
            sizePx.coerceAtLeast(1).toFloat(),
        )

    private fun missingCjkFont(text: String, logical: Font): Font? {
        var offset = 0
        var missing = false
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (isCjk(codePoint) && !logical.canDisplay(codePoint)) {
                missing = true
                break
            }
            offset += Character.charCount(codePoint)
        }
        return if (missing) cjkFont.deriveFont(logical.style, logical.size2D) else null
    }

    private val cjkFont: Font by lazy {
        try {
            loadCjkFont()
        } catch (error: Exception) {
            OverlayTextSupport.reportFailure("CJK font initialization", error)
            Font(Font.SANS_SERIF, Font.PLAIN, 12)
        } catch (error: LinkageError) {
            OverlayTextSupport.reportFailure("CJK font initialization", error)
            Font(Font.SANS_SERIF, Font.PLAIN, 12)
        }
    }

    private fun loadCjkFont(): Font {
        val candidates = linkedSetOf<String>()
        System.getProperty("dreamdisplayx.fontFile")?.trim()?.takeIf { it.isNotEmpty() }?.let(candidates::add)
        candidates += listOf(
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansCJKsc-Regular.otf",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/NotoSansCJK-Regular.otf",
            "/system/fonts/NotoSansCJK-VF.ttf",
            "/system/fonts/NotoSansCJKsc-VF.ttf",
            "/system/fonts/DroidSansFallback.ttf",
            "/system/fonts/SourceHanSansCN-Regular.otf",
            "/system_ext/fonts/NotoSansCJK-Regular.ttc",
            "/product/fonts/NotoSansCJK-Regular.ttc",
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

        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.firstOrNull { it.canDisplay(CJK_PROBE) }
        } catch (error: Exception) {
            OverlayTextSupport.reportFailure("registered CJK font lookup", error)
            null
        } catch (error: LinkageError) {
            OverlayTextSupport.reportFailure("registered CJK font lookup", error)
            null
        }?.let { font ->
            logger.info("Using registered CJK overlay font {}.", font.family)
            return font
        }

        logger.warn("No CJK-capable overlay font found; keeping the logical SansSerif composite.")
        return Font(Font.SANS_SERIF, Font.PLAIN, 12)
    }

    private fun loadFont(file: File): Font? {
        if (!file.isFile || !file.canRead()) return null
        return try {
            FileInputStream(file).use { input ->
                Font.createFonts(input).firstOrNull { it.canDisplay(CJK_PROBE) }
            }
        } catch (error: Exception) {
            OverlayTextSupport.reportFailure("font file ${file.path}", error)
            null
        } catch (error: LinkageError) {
            OverlayTextSupport.reportFailure("font file ${file.path}", error)
            null
        }
    }

    private fun isCjk(codePoint: Int): Boolean =
        codePoint in 0x3000..0x30FF || // CJK punctuation, Hiragana, Katakana
            codePoint in 0x3400..0x9FFF || // CJK unified ideographs
            codePoint in 0xAC00..0xD7AF || // Hangul syllables
            codePoint in 0xF900..0xFAFF || // CJK compatibility ideographs
            codePoint in 0x20000..0x2FA1F // CJK extensions and compatibility ideographs
}
