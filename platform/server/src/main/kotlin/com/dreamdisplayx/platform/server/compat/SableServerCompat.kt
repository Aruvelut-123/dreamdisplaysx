package com.dreamdisplayx.platform.server.compat

import com.dreamdisplayx.api.display.geometry.StoredSpace
import com.dreamdisplayx.api.display.geometry.SubLevelId
import com.dreamdisplayx.api.display.geometry.SubLevelLookupPolicy
import com.dreamdisplayx.util.log.DreamLog
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import java.lang.reflect.Method

/**
 * Reflection bridge to Sable's sub-levels on the **server**: the Sable half of [ValkyrienCompat], and
 * the counterpart of the client's `SableClientCompat`.
 *
 * Sable is never a compile dependency — it exists for one Minecraft version only and the fork builds
 * eleven artifacts — so the whole bridge is reflection and inert when the mod is absent. The names
 * below were read out of the shipped jar, not from memory:
 *
 * ```
 * dev.ryanhcode.sable.api.sublevel.SubLevelContainer    // getContainer(Level) is static
 * SubLevelContainer.getAllSubLevels(): List<? extends SubLevel>
 * SubLevelAccess.getUniqueId(): UUID
 * SubLevel.boundingBox(): BoundingBox3dc                // WORLD space
 * SubLevel.getPlot().getBoundingBox(): BoundingBox3ic   // STRUCTURE space
 * ```
 *
 * Two facts shape the implementation and both are easy to get wrong:
 *
 * 1. **`getContainer(Level, ...)` takes a plain `Level`.** `Class.getMethod` matches *declared*
 *    parameter types exactly, so asking for `ServerLevel` throws and — because a failed resolution is
 *    cached as "the mod is absent" — would silently disable the bridge for the whole session. This is
 *    the same trap that broke the Valkyrien Skies bridge once.
 * 2. **`getContaining(Level, int, int)` takes CHUNK coordinates** and does the `>>4` internally.
 *    Passing a block coordinate misses silently, so this bridge never calls it: it walks
 *    `getAllSubLevels()` and tests the boxes itself, with the shared
 *    [SubLevelLookupPolicy] rules so the client and the server agree about which box means what.
 *
 * The one real decision here — which box a stamp may trust — is delegated to
 * [SubLevelLookupPolicy.stampIdFor], where it is unit-tested without the mod on the classpath.
 */
object SableServerCompat {
    private val logger = DreamLog.of("SableServerCompat")

    /** The side-agnostic sub-level view: id and both boxes. */
    private const val SUB_LEVEL_ACCESS = "dev.ryanhcode.sable.companion.SubLevelAccess"

    /** The per-level container holding every loaded sub-level. */
    private const val CONTAINER = "dev.ryanhcode.sable.api.sublevel.SubLevelContainer"

    /** The concrete sub-level class, which is where `getPlot()` is declared. */
    private const val SUB_LEVEL = "dev.ryanhcode.sable.sublevel.SubLevel"

    /** Cached reflection state; `null` means "not resolved yet". */
    @Volatile
    private var state: Resolution? = null

    /**
     * Result of one resolution attempt.
     *
     * `Absent` and `Ready` have to stay distinguishable so a missing mod is not re-probed for every
     * display on every `/display create`.
     */
    private sealed class Resolution {
        /** Marker for "the mod is not on this server". */
        object Absent : Resolution()

        class Ready(
            /** `SubLevelContainer.getContainer(Level)`, a static returning the level's container. */
            val containerOf: Method,
            /** `SubLevelContainer.getAllSubLevels()`. */
            val allSubLevels: Method,
            /** `SubLevelAccess.getUniqueId()`. */
            val uniqueId: Method,
            /** `SubLevelAccess.boundingBox()` — the world-space box. */
            val worldBox: Method,
            /** `SubLevel.getPlot()`. */
            val plot: Method,
            /** `LevelPlot.getBoundingBox()` — the structure-space voxel box (`BoundingBox3ic`). */
            val plotBox: Method,
            /** `BoundingBox3dc.minX()` … `maxZ()`, doubles. */
            val worldMinX: Method,
            val worldMinY: Method,
            val worldMinZ: Method,
            val worldMaxX: Method,
            val worldMaxY: Method,
            val worldMaxZ: Method,
            /** `BoundingBox3ic.minX()` … `maxZ()`, ints. */
            val plotMinX: Method,
            val plotMinY: Method,
            val plotMinZ: Method,
            val plotMaxX: Method,
            val plotMaxY: Method,
            val plotMaxZ: Method,
        ) : Resolution()
    }

    /** True once a successful resolution proved the mod is present and usable. */
    val isAvailable: Boolean get() = resolve() != null

    /**
     * The structure id to persist for the sub-level that owns [pos] in [level], or [SubLevelId.NONE].
     *
     * Both of Sable's boxes are tested for every loaded sub-level, and the pair is reduced by
     * [SubLevelLookupPolicy.storedSpaceOf] exactly as the client does. Only a
     * [StoredSpace.STRUCTURE] hit yields an id:
     *
     * - a structure-space hit means the stored numbers are already plot-local, which is the only state
     *   a bare id can render correctly (`subLevelToWorld * pos`);
     * - a world-space hit means the display was built on loose blocks that were assembled into a
     *   sub-level afterwards. Stamping the id there would apply the structure twice — once by Sable to
     *   the world, once by the renderer to the position — so the row is deliberately left
     *   world-anchored and the client's own discovery probe adopts the structure with the
     *   world-to-structure correction it needs. That probe exists for exactly this case.
     *
     * The world box is still read on a structure-space miss, so the log can distinguish "this row
     * holds world coordinates and was intentionally not stamped" from "no structure here at all" —
     * two states that look identical in the database.
     *
     * Never throws: a broken or half-installed Sable must degrade to "no sub-levels here" so
     * `/display create` keeps working on every other server.
     */
    fun subLevelIdAt(level: ServerLevel, pos: BlockPos): Long {
        val ready = resolve() ?: return SubLevelId.NONE
        return runCatching {
            val candidates = subLevelsOf(ready, level) ?: return SubLevelId.NONE
            // The block's own coordinate is the value that can sit exactly on a voxel-aligned face,
            // and the centre is the value that cannot fall the wrong way out of a floating bound.
            val blockX = pos.x.toDouble()
            val blockY = pos.y.toDouble()
            val blockZ = pos.z.toDouble()
            val centreX = blockX + 0.5
            val centreY = blockY + 0.5
            val centreZ = blockZ + 0.5
            for (subLevel in candidates) {
                val id = idOf(ready, subLevel) ?: continue
                if (id == SubLevelId.NONE) continue
                val structureHit = SubLevelLookupPolicy.contains(plotBoxOf(ready, subLevel), blockX, blockY, blockZ)
                val worldHit = SubLevelLookupPolicy.contains(worldBoxOf(ready, subLevel), centreX, centreY, centreZ)
                val space = SubLevelLookupPolicy.storedSpaceOf(worldHit, structureHit)
                if (space === StoredSpace.UNKNOWN) continue
                if (space === StoredSpace.WORLD) {
                    // Deliberately not stamped, see the KDoc. Logged once per create, not per frame.
                    logger.info(
                        "Sable sub-level {} owns {} but the row holds world coordinates; leaving it world-anchored for the client to correct",
                        id, pos,
                    )
                    return SubLevelId.NONE
                }
                return SubLevelLookupPolicy.stampIdFor(space, id)
            }
            SubLevelId.NONE
        }.getOrElse {
            logger.debug("Sable sub-level lookup failed for {}: {}", pos, it.toString())
            SubLevelId.NONE
        }
    }

    /**
     * Every sub-level in [level], or `null` when the container cannot be read.
     *
     * The container's list is typed `List<? extends SubLevel>`, so a null element is only possible on a
     * badly torn-down level; filtering here keeps every later step on a non-null receiver.
     */
    private fun subLevelsOf(ready: Resolution.Ready, level: Level): List<Any>? {
        val container = runCatching { ready.containerOf.invoke(null, level) }.getOrNull() ?: return null
        val all = runCatching { ready.allSubLevels.invoke(container) }.getOrNull() as? Iterable<*> ?: return null
        return all.filterNotNull()
    }

    /** The persisted id of [subLevel], or `null` when its UUID cannot be read. */
    private fun idOf(ready: Resolution.Ready, subLevel: Any): Long? {
        val uuid = runCatching { ready.uniqueId.invoke(subLevel) }.getOrNull() as? java.util.UUID ?: return null
        return SubLevelId.of(uuid)
    }

    /** The structure-space box of [subLevel], or `null` when it cannot be read. */
    private fun plotBoxOf(ready: Resolution.Ready, subLevel: Any): DoubleArray? {
        val plot = runCatching { ready.plot.invoke(subLevel) }.getOrNull() ?: return null
        val box = runCatching { ready.plotBox.invoke(plot) }.getOrNull() ?: return null
        // `BoundingBox3ic` answers ints; widening to double is exact for a voxel bound, and the shared
        // policy rejects the `BoundingBox3i.EMPTY` an unfilled plot reports.
        return SubLevelLookupPolicy.boundsOf(
            (readInt(ready.plotMinX, box) ?: return null).toDouble(),
            (readInt(ready.plotMinY, box) ?: return null).toDouble(),
            (readInt(ready.plotMinZ, box) ?: return null).toDouble(),
            (readInt(ready.plotMaxX, box) ?: return null).toDouble(),
            (readInt(ready.plotMaxY, box) ?: return null).toDouble(),
            (readInt(ready.plotMaxZ, box) ?: return null).toDouble(),
        )
    }

    /** The world-space box of [subLevel], or `null` when it cannot be read. */
    private fun worldBoxOf(ready: Resolution.Ready, subLevel: Any): DoubleArray? {
        val box = runCatching { ready.worldBox.invoke(subLevel) }.getOrNull() ?: return null
        return SubLevelLookupPolicy.boundsOf(
            readDouble(ready.worldMinX, box) ?: return null,
            readDouble(ready.worldMinY, box) ?: return null,
            readDouble(ready.worldMinZ, box) ?: return null,
            readDouble(ready.worldMaxX, box) ?: return null,
            readDouble(ready.worldMaxY, box) ?: return null,
            readDouble(ready.worldMaxZ, box) ?: return null,
        )
    }

    /** Invokes a `double`-returning getter, or `null` when it cannot be read. */
    private fun readDouble(method: Method, receiver: Any): Double? =
        runCatching { method.invoke(receiver) as? Double }.getOrNull()

    /** Invokes an `int`-returning getter, or `null` when it cannot be read. */
    private fun readInt(method: Method, receiver: Any): Int? =
        runCatching { method.invoke(receiver) as? Int }.getOrNull()

    /**
     * Resolves a static helper against each candidate level facade in turn.
     *
     * `Class.getMethod` matches **declared** parameter types exactly and does not accept a subtype, so
     * asking for `ServerLevel` where Sable declares `Level` throws. The `ServerLevel` spelling is tried
     * first only because a future release might add a narrower overload.
     */
    private fun resolveLevelMethod(owner: Class<*>, name: String, candidates: List<Class<*>>): Method {
        for (param in candidates.distinct()) {
            runCatching { owner.getMethod(name, param) }.getOrNull()?.let { return it }
        }
        throw NoSuchMethodException("$name: tried ${candidates.distinct().joinToString { it.simpleName }}")
    }

    /** Resolves and caches the bridge, or records that the mod is absent. */
    private fun resolve(): Resolution.Ready? {
        when (val cached = state) {
            is Resolution.Ready -> return cached
            Resolution.Absent -> return null
            null -> Unit
        }
        synchronized(this) {
            when (val cached = state) {
                is Resolution.Ready -> return cached
                Resolution.Absent -> return null
                null -> Unit
            }
            val resolved = try {
                val subLevelAccess = Class.forName(SUB_LEVEL_ACCESS)
                val container = Class.forName(CONTAINER)
                val subLevel = Class.forName(SUB_LEVEL)
                val worldBox = subLevelAccess.getMethod("boundingBox")
                val worldBoxType = worldBox.returnType
                // `getPlot()` is declared on the concrete `SubLevel`, not on the access interface, so
                // it has to be resolved there; its return type carries `getBoundingBox()`.
                val plot = subLevel.getMethod("getPlot")
                val plotBox = plot.returnType.getMethod("getBoundingBox")
                val plotBoxType = plotBox.returnType
                Resolution.Ready(
                    containerOf = resolveLevelMethod(
                        container, "getContainer",
                        listOf(ServerLevel::class.java, Level::class.java),
                    ),
                    allSubLevels = container.getMethod("getAllSubLevels"),
                    uniqueId = subLevelAccess.getMethod("getUniqueId"),
                    worldBox = worldBox,
                    plot = plot,
                    plotBox = plotBox,
                    worldMinX = worldBoxType.getMethod("minX"),
                    worldMinY = worldBoxType.getMethod("minY"),
                    worldMinZ = worldBoxType.getMethod("minZ"),
                    worldMaxX = worldBoxType.getMethod("maxX"),
                    worldMaxY = worldBoxType.getMethod("maxY"),
                    worldMaxZ = worldBoxType.getMethod("maxZ"),
                    plotMinX = plotBoxType.getMethod("minX"),
                    plotMinY = plotBoxType.getMethod("minY"),
                    plotMinZ = plotBoxType.getMethod("minZ"),
                    plotMaxX = plotBoxType.getMethod("maxX"),
                    plotMaxY = plotBoxType.getMethod("maxY"),
                    plotMaxZ = plotBoxType.getMethod("maxZ"),
                ).also {
                    // INFO on success: without it a shipped log cannot distinguish "Sable is not
                    // installed" from "the bridge resolved but stamped nothing", and that blind spot
                    // is what made the earlier ship reports unreadable.
                    logger.info("Sable server bridge resolved; sub-level displays will be stamped.")
                }
            } catch (_: ClassNotFoundException) {
                // The mod is simply not installed; this is the normal case on most servers.
                Resolution.Absent
            } catch (t: Throwable) {
                // Installed but a different, incompatible version: report once, then stay inert.
                logger.warn("Sable is present but its server API did not match; sub-level anchoring is disabled.", t)
                Resolution.Absent
            }
            state = resolved
            return resolved as? Resolution.Ready
        }
    }

    /** Drops the cached resolution; used by tests and after a mod reload. */
    internal fun reset() {
        synchronized(this) {
            state = null
        }
    }
}