package com.dreamdisplayx.platform.client.render

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Optional LambDynamicLights bridge. The dependency stays soft: worlds without LambDynamicLights
 * never load its classes and keep the vanilla display lighting path unchanged.
 *
 * LambDynamicLights exposes monochrome Minecraft light levels, so the sampled RGB value is retained
 * for future colour-capable shader integrations while luminance drives the native light level.
 */
object DynamicDisplayLights {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/DynamicDisplayLights")
    private val sources = ConcurrentHashMap<UUID, Any>()
    private val colors = ConcurrentHashMap<UUID, Int>()
    private var manager: Any? = null
    private var behaviorClass: Class<*>? = null
    private var boxClass: Class<*>? = null
    private var unavailable = false

    fun update(id: UUID, pos: BlockPos, color: Int, playing: Boolean) {
        colors[id] = color
        val m = resolveManager() ?: return
        if (!playing) {
            remove(id, m)
            return
        }
        val source = sources[id] ?: createSource(id, pos, color, m) ?: return
        SourceState.update(source, pos, color)
    }

    fun remove(id: UUID) {
        remove(id, manager)
        colors.remove(id)
    }

    fun color(id: UUID): Int? = colors[id]

    private fun resolveManager(): Any? {
        if (unavailable) return null
        manager?.let { return it }
        return runCatching {
            val lights = Class.forName("dev.lambdaurora.lambdynlights.LambDynLights")
            val instance = lights.getMethod("get").invoke(null)
            val value = instance.javaClass.getMethod("dynamicLightBehaviorManager").invoke(instance)
            manager = value
            behaviorClass = Class.forName("dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior")
            boxClass = Class.forName("dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior\$BoundingBox")
            value
        }.getOrElse {
            unavailable = true
            null
        }
    }

    private fun createSource(id: UUID, pos: BlockPos, color: Int, m: Any): Any? = runCatching {
        val iface = behaviorClass ?: return null
        lateinit var source: Any
        source = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            when (method.name) {
                "lightAtPos" -> SourceState.lightAtPos(id, args?.getOrNull(0) as? BlockPos)
                "getBoundingBox" -> SourceState.boundingBox(id)
                "hasChanged" -> SourceState.hasChanged(id)
                "isRemoved" -> false
                "toString" -> "DreamDisplaysX-$id"
                "hashCode" -> id.hashCode()
                "equals" -> args?.firstOrNull() === source
                else -> null
            }
        }
        // Cranky: LambDynLights' onEndLevelTick calls getBoundingBox() during its spatial lookup and
        // NEVER null-checks it (DeferredDynamicLightSource.splitIntoDynamicLightEntries -> boundingBox.endX()).
        // Register the position/level BEFORE exposing the source, and only mutate its source set on the
        // render thread, otherwise the libvlc callback thread and the render thread race on the HashMap
        // and a not-yet-initialized source makes the whole client crash with an NPE.
        SourceState.update(source, pos, color)
        onRenderThread { runCatching { m.javaClass.getMethod("add", iface).invoke(m, source) } }
        sources[id] = source
        source
    }.onFailure { logger.debug("LambDynamicLights bridge unavailable: {}", it.message) }.getOrNull()

    private fun remove(id: UUID, m: Any?) {
        val source = sources.remove(id) ?: return
        onRenderThread {
            runCatching { behaviorClass?.let { m?.javaClass?.getMethod("remove", it)?.invoke(m, source) } }
        }
    }

    /** Runs [action] on the render thread. LambDynLights' light source set must only be mutated there. */
    private fun onRenderThread(action: () -> Unit) {
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) action() else mc.execute(action)
    }

    private object SourceState {
        private val positions = ConcurrentHashMap<UUID, BlockPos>()
        private val levels = ConcurrentHashMap<UUID, Int>()
        private val revisions = ConcurrentHashMap<UUID, Int>()
        private val observedRevisions = ConcurrentHashMap<UUID, Int>()

        fun update(source: Any, pos: BlockPos, color: Int) {
            val id = source.toString().removePrefix("DreamDisplaysX-").let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
            positions[id] = pos
            val r = (color ushr 16) and 255
            val g = (color ushr 8) and 255
            val b = color and 255
            levels[id] = ((0.2126 * r + 0.7152 * g + 0.0722 * b) / 17.0).toInt().coerceIn(1, 15)
            revisions[id] = (revisions[id] ?: 0) + 1
        }

        fun hasChanged(id: UUID): Boolean = observedRevisions[id] != (revisions[id] ?: 0)

        fun lightAtPos(id: UUID, query: BlockPos?): Double {
            val origin = positions[id] ?: return 0.0
            val q = query ?: return 0.0
            val dx = q.x - origin.x.toDouble()
            val dy = q.y - origin.y.toDouble()
            val dz = q.z - origin.z.toDouble()
            observedRevisions[id] = revisions[id] ?: 0
            return (levels[id] ?: 0) - sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun boundingBox(id: UUID): Any? = runCatching {
            val p = positions[id]
            val fallback = p ?: FALLBACK_POS
            boxClass?.getConstructor(Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java)
                ?.newInstance(fallback.x, fallback.y, fallback.z, fallback.x + 1, fallback.y + 1, fallback.z + 1)
        }.getOrNull()

        /** Position used when the source has no registered position yet; keeps getBoundingBox() non-null. */
        private val FALLBACK_POS = BlockPos.ZERO
    }

    fun sample(buf: ByteBuffer, w: Int, h: Int, bytesPerPixel: Int): Int {
        if (w <= 0 || h <= 0 || bytesPerPixel < 3) return 0
        val base = buf.position()
        val limit = buf.limit()
        var r = 0L; var g = 0L; var b = 0L; var count = 0
        val sx = (w / 32).coerceAtLeast(1); val sy = (h / 18).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val i = base + (y * w + x) * bytesPerPixel
                if (i + 2 < limit) {
                    // LibVLC's BGRA32 callback is byte-addressed as B, G, R, A.
                    b += buf.get(i).toLong() and 255; g += buf.get(i + 1).toLong() and 255; r += buf.get(i + 2).toLong() and 255; count++
                }
                x += sx
            }
            y += sy
        }
        if (count == 0) return 0
        return ((r / count).toInt() shl 16) or ((g / count).toInt() shl 8) or (b / count).toInt()
    }
}
