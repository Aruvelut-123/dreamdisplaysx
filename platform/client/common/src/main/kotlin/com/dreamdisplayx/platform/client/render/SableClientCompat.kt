package com.dreamdisplayx.platform.client.render

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.display.geometry.StoredSpace
import com.dreamdisplayx.api.display.geometry.StructureOwner
import com.dreamdisplayx.api.display.geometry.SubLevelId
import com.dreamdisplayx.api.display.geometry.SubLevelLookupPolicy
import com.dreamdisplayx.api.display.geometry.SubLevelPose
import com.dreamdisplayx.util.log.DreamLog
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.Level
import java.lang.reflect.Method

/**
 * Reflection bridge to Sable's sub-levels on the client, the Sable counterpart of
 * [ValkyrienClientCompat].
 *
 * Sable is never a compile dependency: it exists for one Minecraft version only, it is invasive by
 * its own description, and the fork builds eleven artifacts. Every name below was read out of the
 * shipped jars rather than from memory:
 *
 * ```
 * dev.ryanhcode.sable.companion.SableCompanion          // INSTANCE is a static field on the interface
 * dev.ryanhcode.sable.api.sublevel.SubLevelContainer    // getContainer(Level) is static
 * SubLevelContainer.getAllSubLevels(): List<? extends SubLevel>
 * SubLevel.getUniqueId(): UUID
 * SubLevel.boundingBox(): BoundingBox3dc                // WORLD space
 * SubLevel.getPlot().getBoundingBox(): BoundingBox3ic   // STRUCTURE space
 * ClientSubLevelAccess.renderPose(): Pose3dc            // already interpolated with partialTick
 * Pose3dc.position() / orientation() / rotationPoint() / scale()
 * Vector3dc.x() / y() / z(),  Quaterniondc.x() / y() / z() / w()
 * ```
 *
 * Three facts shape the implementation:
 *
 * 1. **`renderPose()` takes no argument and interpolates for you.** Its bytecode calls
 *    `Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true)` and caches the result per
 *    partial tick. That is why the fork's anchoring path needs no partial-tick plumbing: the pose it
 *    asks for is already the render pose, exactly like VS's `getRenderTransform()`.
 * 2. **The two boxes live in different spaces.** `LevelPlot.getBoundingBox()` is the structure-space
 *    voxel bound and `SubLevel.boundingBox()` is the world-space one, mirroring VS's `getShipAABB()`
 *    and `getWorldAABB()`. Reading them the wrong way round makes every display on a hull look like
 *    it needs a correction, which then flings it into the void.
 * 3. **`getContaining(Level, int, int)` takes CHUNK coordinates**, not block ones. Every overload
 *    that accepts a position does the `>>4` itself; passing a block coordinate silently misses.
 *
 * Nothing here throws: a half-installed or renamed Sable must degrade to "no sub-levels here", which
 * is the only failure mode a player can act on (the picture stays where it was).
 */
@DreamDisplaysXUnstableApi
object SableClientCompat {
    private val logger = DreamLog.of("SableClientCompat")

    /** The side-agnostic sub-level view: id, pose and world-space box. */
    private const val SUB_LEVEL_ACCESS = "dev.ryanhcode.sable.companion.SubLevelAccess"

    /** The client-only view, which adds the interpolated `renderPose()`. */
    private const val CLIENT_SUB_LEVEL_ACCESS = "dev.ryanhcode.sable.companion.ClientSubLevelAccess"

    /** The pose interface: four getters, all returning JOML values. */
    private const val POSE = "dev.ryanhcode.sable.companion.math.Pose3dc"

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
     * `null` from [resolve] plus `state === Absent` is what [isMissing] reports, so the two have to be
     * distinguishable — hence a sealed hierarchy rather than a nullable `Any`.
     */
    private sealed class Resolution {
        /** Marker for "the mod is not on this client", so a failed lookup is not retried per frame. */
        object Absent : Resolution()

        class Ready(
            /** `SubLevelContainer.getContainer(Level)`, a static returning the level's container. */
            val containerOf: Method,
            /** `SubLevelContainer.getAllSubLevels()`. */
            val allSubLevels: Method,
            /** `SubLevelAccess.getUniqueId()`. */
            val uniqueId: Method,
            /** `SubLevelAccess.boundingBox()` (world space). */
            val worldBox: Method,
            /** `SubLevel.getPlot()`. */
            val plot: Method,
            /** `LevelPlot.getBoundingBox()` (structure space, `BoundingBox3ic`). */
            val plotBox: Method,
            /** `ClientSubLevelAccess.renderPose()`, already interpolated. */
            val renderPose: Method,
            /** `SubLevelAccess.logicalPose()`, the un-interpolated pose. */
            val logicalPose: Method,
            /** `Pose3dc.position()` / `orientation()` / `rotationPoint()` / `scale()`. */
            val posePosition: Method,
            val poseOrientation: Method,
            val poseRotationPoint: Method,
            val poseScale: Method,
            /** `Vector3dc.x()` / `y()` / `z()`. */
            val vecX: Method,
            val vecY: Method,
            val vecZ: Method,
            /** `Quaterniondc.x()` / `y()` / `z()` / `w()`. */
            val quatX: Method,
            val quatY: Method,
            val quatZ: Method,
            val quatW: Method,
            /** `BoundingBox3dc.minX()` … `maxZ()`, the world-space box. */
            val worldMinX: Method,
            val worldMinY: Method,
            val worldMinZ: Method,
            val worldMaxX: Method,
            val worldMaxY: Method,
            val worldMaxZ: Method,
            /** `BoundingBox3ic.minX()` … `maxZ()`, the structure-space box (ints). */
            val plotMinX: Method,
            val plotMinY: Method,
            val plotMinZ: Method,
            val plotMaxX: Method,
            val plotMaxY: Method,
            val plotMaxZ: Method,
        ) : Resolution()
    }

    /**
     * What a discovery probe found.
     *
     * [worldToStructure] is `null` when the row's coordinates are already structure space — the
     * stamped case. It is the structure's **world-to-structure** matrix when they are world space,
     * which is the build-then-assemble case (`/display create` sampled the block before Sable knew
     * about a sub-level).
     */
    data class Discovery(val subLevelId: Long, val worldToStructure: DoubleArray?) {
        /** True when the stored coordinates need converting before they can be rendered. */
        val hasCorrection: Boolean get() = worldToStructure != null

        // Not derived from the array: `DoubleArray` compares by reference, so a synthesized `equals`
        // would report two identical corrections as different.
        override fun toString(): String = "Discovery(subLevelId=$subLevelId, correction=$hasCorrection)"

        companion object {
            /** Nothing found: the display stays where it is. */
            val NONE: Discovery = Discovery(SubLevelId.NONE, null)
        }
    }

    /** True once a successful lookup proved the mod is present and usable. */
    val isAvailable: Boolean get() = resolve() != null

    /** True when the mod's entry point could not be resolved on this client. */
    val isMissing: Boolean get() = resolve() == null && state === Resolution.Absent

    /** Which mod owns [structureId], for logging and for the cleanup census. */
    fun ownerOf(structureId: Long): StructureOwner = when {
        structureId <= SubLevelId.NONE -> StructureOwner.NONE
        SubLevelId.isSubLevel(structureId) -> StructureOwner.SABLE
        else -> StructureOwner.VALKYRIEN_SKIES
    }

    /**
     * The sub-level-to-world matrix of [structureId] as 16 column-major doubles, or `null` when the
     * sub-level is not loaded on this client.
     *
     * Uses `renderPose()` — the interpolated pose — so the picture tracks the hull between physics
     * ticks instead of juddering against it. A miss is normal (an unloaded or sailed-away structure)
     * and must be treated as a failure by the caller, never as identity.
     */
    fun subLevelToWorldMatrix(level: ClientLevel?, structureId: Long): DoubleArray? {
        if (structureId == SubLevelId.NONE || level == null) return null
        val ready = resolve() ?: return null
        return runCatching {
            val subLevel = findById(ready, level, structureId) ?: return null
            val pose = invokeOrNull(ready.renderPose, subLevel) ?: return null
            matrixOfPose(ready, pose)
        }.getOrElse { null }
    }

    /**
     * Finds the sub-level that owns the block at ([x], [y], [z]) and reports how to render it.
     *
     * This is the Sable half of the anchor discovery described in [ValkyrienClientCompat.discoverAt]:
     * the server stamps a sub-level id once, inside `/display create`, and a display built on loose
     * blocks that were assembled into a sub-level afterwards arrives world-anchored. The client
     * therefore probes for itself.
     *
     * Both boxes are tested because they answer different questions, and which one hit decides the
     * stored space (see [SubLevelLookupPolicy.storedSpaceOf]):
     *
     * 1. `SubLevel.boundingBox()` — **world** space. A hit proves the row's numbers address the hull
     *    where it is now, i.e. they were recorded before Sable moved the blocks, so the row needs the
     *    world-to-structure correction.
     * 2. `getPlot().getBoundingBox()` — **structure** space. A hit proves the numbers are already
     *    plot-local, which is the state a server-side stamp produces. Projecting those again would
     *    apply the hull's motion twice.
     *
     * The world box is tested first on purpose: on the frame a structure is assembled both boxes
     * contain the stored coordinate, and the world-space reading is the one consistent with how the
     * row was stamped.
     */
    fun discoverAt(level: ClientLevel?, x: Int, y: Int, z: Int): Discovery {
        if (level == null) return Discovery.NONE
        val ready = resolve() ?: return Discovery.NONE
        return runCatching {
            val candidates = subLevelsOf(ready, level) ?: return Discovery.NONE
            for (subLevel in candidates) {
                val id = idOf(ready, subLevel) ?: continue
                val worldBox = boxOf(ready, subLevel, world = true)
                val structureBox = boxOf(ready, subLevel, world = false)
                val centreX = x + 0.5
                val centreY = y + 0.5
                val centreZ = z + 0.5
                val worldHit = SubLevelLookupPolicy.contains(worldBox, centreX, centreY, centreZ)
                // The structure box is voxel-aligned, so the block's own coordinate (not its centre)
                // is the value that can sit exactly on a face.
                val structureHit = SubLevelLookupPolicy.contains(
                    structureBox, x.toDouble(), y.toDouble(), z.toDouble(),
                )
                val space = SubLevelLookupPolicy.storedSpaceOf(worldHit, structureHit)
                if (space === StoredSpace.UNKNOWN) continue
                return Discovery(id, if (space.needsCorrection) worldToStructureOf(ready, subLevel) else null)
            }
            Discovery.NONE
        }.getOrElse {
            logger.debug("Sable sub-level discovery failed at ({}, {}, {}): {}", x, y, z, it.toString())
            Discovery.NONE
        }
    }

    /**
     * The sub-level-to-world matrix for the sub-level that owns the block at ([x], [y], [z]), or
     * `null` when none does.
     *
     * Used by the server-side stamping path, where the only question is "is this block inside a
     * structure, and which one" — the value stored is [SubLevelId.of] of its UUID.
     */
    fun subLevelIdAt(level: ClientLevel?, x: Int, y: Int, z: Int): Long =
        discoverAt(level, x, y, z).subLevelId

    /** Adapts this bridge to the api-level transform source. */
    fun source(): com.dreamdisplayx.api.display.geometry.ShipTransformSource =
        com.dreamdisplayx.api.display.geometry.ShipTransformSource { structureId ->
            subLevelToWorldMatrix(clientLevel(), structureId)
        }

    /**
     * The ids of every sub-level currently loaded on this client, or an empty set when Sable is
     * absent.
     *
     * The Sable counterpart of [ValkyrienClientCompat.loadedShipIds], and it exists for the same
     * reason: "is the structure this row rides still there" is a different question from "can I read
     * its pose right now". A row whose sub-level is gone should be reported as orphaned; a row whose
     * sub-level merely cannot be read this frame should not.
     *
     * Ids come from [SubLevelId.of], i.e. the same hash the discovery path stores, so the two sets are
     * directly comparable by the caller.
     *
     * Never throws: a container torn down during a world switch is ordinary, and an exception
     * escaping into the cleanup census would flag every sub-level display as dropped.
     */
    fun loadedSubLevelIds(level: ClientLevel?): Set<Long> {
        if (level == null) return emptySet()
        val ready = resolve() ?: return emptySet()
        return runCatching {
            val candidates = subLevelsOf(ready, level) ?: return emptySet()
            val out = HashSet<Long>()
            for (subLevel in candidates) {
                val id = idOf(ready, subLevel) ?: continue
                if (id != SubLevelId.NONE) out.add(id)
            }
            out as Set<Long>
        }.getOrElse { emptySet() }
    }

    /** Reads the current client level, tolerating a client that is still starting up. */
    private fun clientLevel(): ClientLevel? =
        runCatching { net.minecraft.client.Minecraft.getInstance().level }.getOrNull()

    /** Every sub-level in [level], or `null` when the container cannot be read. */
    private fun subLevelsOf(ready: Resolution.Ready, level: Level): List<Any>? {
        val container = runCatching { ready.containerOf.invoke(null, level) }.getOrNull() ?: return null
        val all = runCatching { ready.allSubLevels.invoke(container) }.getOrNull() as? Iterable<*> ?: return null
        // The container's list is typed `List<? extends SubLevel>`, so an element can only be null on
        // a badly torn-down level; filtering here keeps every later step on a non-null receiver.
        return all.filterNotNull()
    }

    /** Resolves [structureId] against the loaded sub-levels by hashing each UUID. */
    private fun findById(ready: Resolution.Ready, level: Level, structureId: Long): Any? {
        val candidates = subLevelsOf(ready, level) ?: return null
        return SubLevelLookupPolicy.findByHashedId(candidates, structureId) { idOf(ready, it) }
    }

    /** The persisted id of [subLevel], or `null` when its UUID cannot be read. */
    private fun idOf(ready: Resolution.Ready, subLevel: Any): Long? {
        val uuid = runCatching { ready.uniqueId.invoke(subLevel) }.getOrNull() as? java.util.UUID ?: return null
        return SubLevelId.of(uuid)
    }

    /** Reads one of the sub-level's two boxes as six doubles. */
    private fun boxOf(ready: Resolution.Ready, subLevel: Any, world: Boolean): DoubleArray? {
        if (world) {
            val box = runCatching { ready.worldBox.invoke(subLevel) }.getOrNull() ?: return null
            // Each component goes through `runCatching` on its own so a `BoundingBox3dc` revision that
            // renames one getter costs that component only. An uncaught throw here would abort the
            // whole candidate list and look like "no structure is loaded".
            val minX = readDouble(ready.worldMinX, box) ?: return null
            val minY = readDouble(ready.worldMinY, box) ?: return null
            val minZ = readDouble(ready.worldMinZ, box) ?: return null
            val maxX = readDouble(ready.worldMaxX, box) ?: return null
            val maxY = readDouble(ready.worldMaxY, box) ?: return null
            val maxZ = readDouble(ready.worldMaxZ, box) ?: return null
            return SubLevelLookupPolicy.boundsOf(minX, minY, minZ, maxX, maxY, maxZ)
        }
        val plot = runCatching { ready.plot.invoke(subLevel) }.getOrNull() ?: return null
        val box = runCatching { ready.plotBox.invoke(plot) }.getOrNull() ?: return null
        // `BoundingBox3ic` answers ints; widening is exact for a voxel bound.
        val minX = readInt(ready.plotMinX, box) ?: return null
        val minY = readInt(ready.plotMinY, box) ?: return null
        val minZ = readInt(ready.plotMinZ, box) ?: return null
        val maxX = readInt(ready.plotMaxX, box) ?: return null
        val maxY = readInt(ready.plotMaxY, box) ?: return null
        val maxZ = readInt(ready.plotMaxZ, box) ?: return null
        return SubLevelLookupPolicy.boundsOf(
            minX.toDouble(), minY.toDouble(), minZ.toDouble(),
            maxX.toDouble(), maxY.toDouble(), maxZ.toDouble(),
        )
    }

    /** Invokes a `double`-returning getter, or `null` when it cannot be read. */
    private fun readDouble(method: Method, receiver: Any): Double? =
        runCatching { method.invoke(receiver) as? Double }.getOrNull()

    /** Invokes an `int`-returning getter, or `null` when it cannot be read. */
    private fun readInt(method: Method, receiver: Any): Int? =
        runCatching { method.invoke(receiver) as? Int }.getOrNull()

    /**
     * The world-to-structure matrix captured at discovery time, or `null` when the pose has no
     * inverse.
     *
     * `SubLevelPose.inverseMatrixOf` is the exact inverse of the same composition
     * `Pose3dc.bakeIntoMatrix` performs, so `pose_now * inverse(pose_then)` starts as the identity and
     * only diverges once the hull moves — which is why pinning a display to a structure never makes
     * the picture jump.
     *
     * The **logical** pose is used, not the render pose: the correction is a snapshot, and taking it
     * from an interpolated value would bake a fraction of a tick's motion into a matrix that is
     * supposed to be static.
     */
    private fun worldToStructureOf(ready: Resolution.Ready, subLevel: Any): DoubleArray? {
        val pose = runCatching { ready.logicalPose.invoke(subLevel) }.getOrNull() ?: return null
        val parts = poseParts(ready, pose) ?: return null
        return SubLevelPose.inverseMatrixOf(parts.position, parts.orientation, parts.scale, parts.rotationPoint)
    }

    /** Composes the sub-level-to-world matrix from a live pose. */
    private fun matrixOfPose(ready: Resolution.Ready, pose: Any): DoubleArray? {
        val parts = poseParts(ready, pose) ?: return null
        return SubLevelPose.matrixOf(parts.position, parts.orientation, parts.scale, parts.rotationPoint)
    }

    /** The four plain-number components of a `Pose3dc`. */
    private class PoseParts(
        val position: DoubleArray,
        val orientation: DoubleArray,
        val scale: DoubleArray,
        val rotationPoint: DoubleArray,
    )

    /**
     * Reads a `Pose3dc`'s four components as plain doubles.
     *
     * Everything crosses the reflection boundary as numbers on purpose: no JOML type is ever named in
     * this fork's source, so the module keeps compiling against every Minecraft version even though
     * JOML is only on the classpath where Sable ships it.
     */
    private fun poseParts(ready: Resolution.Ready, pose: Any): PoseParts? {
        val position = vectorOf(ready, invokeOrNull(ready.posePosition, pose) ?: return null) ?: return null
        val orientation = quaternionOf(ready, invokeOrNull(ready.poseOrientation, pose) ?: return null) ?: return null
        val rotationPoint = vectorOf(ready, invokeOrNull(ready.poseRotationPoint, pose) ?: return null) ?: return null
        val scale = vectorOf(ready, invokeOrNull(ready.poseScale, pose) ?: return null) ?: return null
        return PoseParts(position, orientation, scale, rotationPoint)
    }

    /** Reads a `Vector3dc` as three doubles. */
    private fun vectorOf(ready: Resolution.Ready, vector: Any?): DoubleArray? {
        if (vector == null) return null
        return runCatching {
            doubleArrayOf(
                ready.vecX.invoke(vector) as Double,
                ready.vecY.invoke(vector) as Double,
                ready.vecZ.invoke(vector) as Double,
            )
        }.getOrNull()
    }

    /** Reads a `Quaterniondc` as `(x, y, z, w)`. */
    private fun quaternionOf(ready: Resolution.Ready, quaternion: Any?): DoubleArray? {
        if (quaternion == null) return null
        return runCatching {
            doubleArrayOf(
                ready.quatX.invoke(quaternion) as Double,
                ready.quatY.invoke(quaternion) as Double,
                ready.quatZ.invoke(quaternion) as Double,
                ready.quatW.invoke(quaternion) as Double,
            )
        }.getOrNull()
    }

    /** Invokes a no-argument getter, returning `null` instead of throwing. */
    private fun invokeOrNull(method: Method, receiver: Any): Any? =
        runCatching { method.invoke(receiver) }.getOrNull()

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
                val clientSubLevelAccess = Class.forName(CLIENT_SUB_LEVEL_ACCESS)
                val pose = Class.forName(POSE)
                val container = Class.forName(CONTAINER)
                val subLevel = Class.forName(SUB_LEVEL)
                val vector = Class.forName("org.joml.Vector3dc")
                val quaternion = Class.forName("org.joml.Quaterniondc")
                val worldBox = subLevelAccess.getMethod("boundingBox")
                val worldBoxType = worldBox.returnType
                // `getPlot()` is declared on the concrete `SubLevel`, not on either access interface,
                // so it has to be resolved there and its return type carries `getBoundingBox()`.
                val plot = subLevel.getMethod("getPlot")
                val plotBox = plot.returnType.getMethod("getBoundingBox")
                val plotBoxType = plotBox.returnType
                Resolution.Ready(
                    // `getContainer` is overloaded for Level/ServerLevel/ClientLevel; the plain
                    // `Level` spelling is the one that works on both sides and is what the companion
                    // interface itself declares.
                    containerOf = resolveLevelMethod(container, "getContainer", listOf(ClientLevel::class.java, Level::class.java)),
                    allSubLevels = container.getMethod("getAllSubLevels"),
                    uniqueId = subLevelAccess.getMethod("getUniqueId"),
                    worldBox = worldBox,
                    plot = plot,
                    plotBox = plotBox,
                    // `renderPose()` lives on the client-only interface; a server-side install cannot
                    // provide it, but this bridge only ever runs on a client.
                    renderPose = clientSubLevelAccess.getMethod("renderPose"),
                    logicalPose = subLevelAccess.getMethod("logicalPose"),
                    posePosition = pose.getMethod("position"),
                    poseOrientation = pose.getMethod("orientation"),
                    poseRotationPoint = pose.getMethod("rotationPoint"),
                    poseScale = pose.getMethod("scale"),
                    vecX = vector.getMethod("x"),
                    vecY = vector.getMethod("y"),
                    vecZ = vector.getMethod("z"),
                    quatX = quaternion.getMethod("x"),
                    quatY = quaternion.getMethod("y"),
                    quatZ = quaternion.getMethod("z"),
                    quatW = quaternion.getMethod("w"),
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
                    // INFO on success, not silence: a shipped log otherwise cannot tell "Sable is not
                    // installed" from "the bridge resolved but drew in structure space anyway", which
                    // is exactly the blind spot the 1.10.8.2 ship report had.
                    logger.info("Sable client bridge resolved; sub-level displays follow their structure.")
                }
            } catch (_: ClassNotFoundException) {
                // The mod is simply not installed; this is the normal case.
                Resolution.Absent
            } catch (t: Throwable) {
                // Installed but a different, incompatible version: report once, then stay inert.
                logger.warn("Sable is present but its client API did not match; sub-level displays stay in structure space.", t)
                Resolution.Absent
            }
            state = resolved
            return resolved as? Resolution.Ready
        }
    }

    /**
     * Resolves a static helper against each candidate level facade in turn.
     *
     * `Class.getMethod` matches **declared** parameter types exactly and does not accept a subtype, so
     * asking for `ClientLevel` when the method takes `Level` throws. Sable declares
     * `getContainer(Level)`, so the plain `Level` spelling must be in the list; the `ClientLevel`
     * spelling is tried first only because some releases add a narrower overload.
     */
    private fun resolveLevelMethod(owner: Class<*>, name: String, candidates: List<Class<*>>): Method {
        for (param in candidates.distinct()) {
            runCatching { owner.getMethod(name, param) }.getOrNull()?.let { return it }
        }
        throw NoSuchMethodException("$name: tried ${candidates.distinct().joinToString { it.simpleName }}")
    }

    /** Drops the cached resolution; used by tests and after a mod reload. */
    internal fun reset() {
        synchronized(this) {
            state = null
        }
    }
}