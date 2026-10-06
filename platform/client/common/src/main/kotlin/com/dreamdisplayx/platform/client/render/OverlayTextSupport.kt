package com.dreamdisplayx.platform.client.render

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime-safe probe and fallback metrics for optional subtitle/danmaku text rendering.
 *
 * This class deliberately contains no `java.awt` types in imports, fields, or method signatures. A
 * few Android launchers do not expose `java.desktop` at all; the AWT implementation lives in the
 * separate optional backend files and is loaded only after [available] succeeds.
 */
internal object OverlayTextSupport {
    private const val FONT_CLASS = "java.awt.Font"
    private const val IMAGE_CLASS = "java.awt.image.BufferedImage"
    private const val GRAPHICS_CLASS = "java.awt.Graphics2D"
    private const val METRICS_CLASS = "java.awt.FontMetrics"
    private const val FAILURE_INTERVAL_NANOS = 60_000_000_000L

    private val logger = LoggerFactory.getLogger("DreamDisplaysX/OverlayTextSupport")
    private val lastFailureNanos = ConcurrentHashMap<String, AtomicLong>()

    /** True when AWT classes can construct a font, image, graphics context, metrics, and glyphs. */
    fun available(): Boolean = awtAvailable

    private val awtAvailable: Boolean by lazy {
        probe(OverlayTextSupport::class.java.classLoader)
    }

    /**
     * Performs a real AWT operation rather than merely checking whether classes exist.
     *
     * The class-loader parameter is intentionally package-visible for tests: it lets the JVM test
     * the same fail-closed path with a loader that hides `java.awt`, without modifying the host JVM.
     */
    internal fun probe(classLoader: ClassLoader?): Boolean {
        var graphics: Any? = null
        var disposeMethod: java.lang.reflect.Method? = null
        return try {
            val fontClass = Class.forName(FONT_CLASS, true, classLoader)
            val imageClass = Class.forName(IMAGE_CLASS, true, classLoader)
            val graphicsClass = Class.forName(GRAPHICS_CLASS, true, classLoader)
            val metricsClass = Class.forName(METRICS_CLASS, true, classLoader)
            val intType = Int::class.javaPrimitiveType
            val imageType = imageClass.getField("TYPE_INT_ARGB").getInt(null)
            val image = imageClass.getConstructor(intType, intType, intType)
                .newInstance(8, 8, imageType)
            val font = fontClass.getConstructor(String::class.java, intType, intType)
                .newInstance("SansSerif", 0, 12)
            disposeMethod = graphicsClass.getMethod("dispose")
            graphics = imageClass.getMethod("createGraphics").invoke(image)
            graphicsClass.getMethod("setFont", fontClass).invoke(graphics, font)
            val metrics = graphicsClass.getMethod("getFontMetrics").invoke(graphics)
                ?: error("AWT returned no font metrics")
            val ascent = metricsClass.getMethod("getAscent").invoke(metrics) as? Int
                ?: error("AWT returned invalid font metrics")
            graphicsClass.getMethod(
                "drawString",
                String::class.java,
                intType,
                intType,
            ).invoke(graphics, "probe", 0, ascent.coerceAtLeast(1))
            true
        } catch (error: Exception) {
            reportFailure("runtime probe", error)
            false
        } catch (error: LinkageError) {
            reportFailure("runtime probe", error)
            false
        } finally {
            if (graphics != null && disposeMethod != null) {
                try {
                    disposeMethod.invoke(graphics)
                } catch (error: Exception) {
                    reportFailure("runtime probe disposal", error)
                } catch (error: LinkageError) {
                    reportFailure("runtime probe disposal", error)
                }
            }
        }
    }

    /**
     * Executes optional rendering code and returns null for ordinary runtime/linkage failures.
     * Fatal VM errors are intentionally not caught so a broken optional backend cannot disguise an
     * out-of-memory, stack-overflow, or similar process-level failure.
     */
    internal inline fun <T> optionalOrNull(operation: String, block: () -> T): T? {
        return try {
            block()
        } catch (error: Exception) {
            reportFailure(operation, error)
            null
        } catch (error: LinkageError) {
            reportFailure(operation, error)
            null
        }
    }

    /** Records an optional-backend failure at most once per interval for each operation. */
    internal fun reportFailure(operation: String, error: Throwable) {
        val now = System.nanoTime()
        val marker = lastFailureNanos.computeIfAbsent(operation) { AtomicLong(Long.MIN_VALUE) }
        while (true) {
            val previous = marker.get()
            if (previous != Long.MIN_VALUE && now - previous < FAILURE_INTERVAL_NANOS) return
            if (marker.compareAndSet(previous, now)) break
        }
        logger.warn("Optional AWT {} failed; the overlay will use its fallback path.", operation, error)
    }

    /** Approximate metrics used only when the optional AWT backend is unavailable. */
    fun fallbackMetrics(text: String, sizePx: Int): Pair<Float, Float> {
        val size = sizePx.coerceAtLeast(1)
        val width = text.codePointCount(0, text.length) * size * 0.9f
        return width.coerceAtLeast(1f) to size.toFloat()
    }
}
