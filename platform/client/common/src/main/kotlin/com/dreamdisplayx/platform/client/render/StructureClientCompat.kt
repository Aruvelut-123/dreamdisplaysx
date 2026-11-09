package com.dreamdisplayx.platform.client.render

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.display.geometry.AffineTransform
import com.dreamdisplayx.api.display.geometry.ShipAnchorPolicy
import com.dreamdisplayx.api.display.geometry.ShipTransformCache
import com.dreamdisplayx.api.display.geometry.StoredSpace
import com.dreamdisplayx.api.display.geometry.StructureOwner
import com.dreamdisplayx.api.display.geometry.StructureTransforms
import com.dreamdisplayx.api.display.geometry.SubLevelId
import com.dreamdisplayx.api.display.geometry.SubLevelPose
import com.dreamdisplayx.util.log.DreamLog
import net.minecraft.client.multiplayer.ClientLevel

/**
 * The one place the client asks "where is this structure?".
 *
 * Two physics mods are supported and they disagree about almost everything:
 *
 * | | Valkyrien Skies | Sable |
 * |---|---|---|
 * | id | `long` | `UUID`, hashed by [SubLevelId] |
 * | live pose | `ClientShip.getRenderTransform()` | `ClientSubLevelAccess.renderPose()` |
 * | structure-space box | `getShipAABB()` | `LevelPlot.getBoundingBox()` |
 * | world-space box | `getWorldAABB()` | `SubLevel.boundingBox()` |
 *
 * Everything downstream — the renderer, the distance census, the acoustic plane, the crosshair ray
 * test, [ShipTransformCache] — only needs "give me the 16 doubles for this id". So the dispatch
 * happens here, once, via [StructureTransforms], and no other call site has to know which mod is
 * installed or how it spells its id.
 *
 * Both bridges are resolved lazily and fail closed: an absent, half-installed or renamed mod leaves
 * the display at its stored coordinates instead of blanking the world or teleporting it to the
 * origin.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
object StructureClientCompat {
    private val logger = DreamLog.of("StructureClientCompat")

    /**
     * The dispatcher over both bridges.
     *
     * `lazy` so a client that never sees a moving-structure display never touches either mod's
     * classes, and so the two bridges resolve on first use rather than during mod construction.
     */
    private val transforms: StructureTransforms by lazy {
        StructureTransforms(ships = ValkyrienClientCompat.source(), subLevels = SableClientCompat.source())
    }

    /** One sampled transform per structure id inside a frame; see [ShipTransformCache]. */
    private val cache: ShipTransformCache by lazy { ShipTransformCache(transforms) }

    /** True when at least one physics mod resolved and can answer for its own id space. */
    val hasAnyBackend: Boolean
        get() = ValkyrienClientCompat.isAvailable || SableClientCompat.isAvailable

    /** Which mod owns [structureId], for logging and for the cleanup census. */
    fun ownerOf(structureId: Long): StructureOwner = transforms.ownerOf(structureId)

    /**
     * The structure-to-world transform for [structureId], or [AffineTransform.IDENTITY] when it
     * cannot be resolved.
     *
     * Served from [cache] for the duration of one frame, then re-read: a structure pose is the value
     * *interpolated* between physics ticks, so reusing it across frames would make the picture lag the
     * hull it is bolted to.
     */
    fun transformFor(structureId: Long): AffineTransform = cache.transformFor(structureId)

    /**
     * The raw column-major structure-to-world matrix for [structureId], or `null` when absent.
     *
     * Unlike [transformFor] this keeps the doubles, for callers that need the matrix itself rather
     * than the composed affine transform.
     */
    fun sampledMatrix(structureId: Long): DoubleArray? = cache.matrixFor(structureId)

    /** Number of physics reads since the last [invalidateTransforms]; diagnostics only. */
    val transformResolutions: Long get() = cache.resolutions

    /** Number of reads served from a sample taken earlier in the same frame; diagnostics only. */
    val transformCacheHits: Long get() = cache.hits

    /**
     * Forgets every sampled transform.
     *
     * Called when the client leaves or switches a level: structure ids are per-world, so a sample
     * surviving a world change could place a display against a hull that does not exist in the next
     * one.
     */
    fun invalidateTransforms() {
        cache.invalidate()
    }

    /**
     * Resolves the structure that owns the block at ([x], [y], [z]).
     *
     * Order matters and is deliberate:
     *
     * 1. **Valkyrien Skies.** Its discovery path is the cheaper of the two — one chunk-claim lookup,
     *    then a bounded scan — and a server with both mods installed could have stamped either id.
     * 2. **Sable.** Walked only when VS found nothing, so a VS-only install pays nothing for it.
     *
     * Both paths return their own id space ([SubLevelId.SABLE_FLAG] marks Sable's), so the caller
     * stores whatever comes back without knowing which mod produced it.
     */
    fun discoverAt(level: ClientLevel?, x: Int, y: Int, z: Int): Discovery {
        if (level == null) return Discovery.NONE
        val ships = ValkyrienClientCompat.discoverAt(level, x, y, z)
        if (ships.shipId > 0L) {
            return Discovery(ships.shipId, ships.worldToShip, StructureOwner.VALKYRIEN_SKIES)
        }
        val sable = SableClientCompat.discoverAt(level, x, y, z)
        if (sable.subLevelId != SubLevelId.NONE) {
            return Discovery(sable.subLevelId, sable.worldToStructure, StructureOwner.SABLE)
        }
        return Discovery.NONE
    }

    /** Ids of the loaded structures of [level], for the server-side "still exists" census. */
    fun loadedStructureIds(level: ClientLevel?): Set<Long> {
        if (level == null) return emptySet()
        val out = HashSet<Long>()
        runCatching { ValkyrienClientCompat.loadedShipIds(level) }.getOrNull()?.let { out.addAll(it) }
        runCatching { SableClientCompat.loadedSubLevelIds(level) }.getOrNull()?.let { out.addAll(it) }
        return out
    }

    /**
     * What a discovery probe found.
     *
     * [worldToStructure] is `null` when the row's coordinates are already structure space — the state
     * a server-side stamp produces. It is the structure's world-to-structure matrix when the row still
     * holds world coordinates, which is the build-then-assemble case.
     */
    data class Discovery(
        val structureId: Long,
        val worldToStructure: DoubleArray?,
        val owner: StructureOwner = StructureOwner.NONE,
    ) {
        /** True when the stored coordinates need converting before they can be rendered. */
        val hasCorrection: Boolean get() = worldToStructure != null

        // Not derived from the array: `DoubleArray` compares by reference, so a synthesized `equals`
        // would report two identical corrections as different.
        override fun toString(): String =
            "Discovery(id=$structureId, owner=$owner, correction=$hasCorrection)"

        companion object {
            /** Nothing found: the display stays where it is. */
            val NONE: Discovery = Discovery(0L, null, StructureOwner.NONE)
        }
    }

    /**
     * Composes a live structure transform with the discovery-time correction, or returns the live one
     * unchanged when the row needs no correction.
     *
     * The order is `live * correction`, i.e. the correction is applied **first**, because the renderer
     * multiplies `structureToWorld * storedPos`. Swapping it would rotate the display around the
     * camera instead of around the structure's origin.
     */
    fun correctedTransform(live: AffineTransform, correction: DoubleArray?): AffineTransform =
        ShipAnchorPolicy.composeCorrection(live, correction)

    /**
     * Applies the pose of [structureId] to a structure-space point, or returns the point unchanged
     * when no transform is resolvable.
     *
     * Convenience for the acoustic plane and the ray test, which both need "map a structure-space
     * position into the world" without caring which mod supplied the matrix.
     */
    fun toWorld(structureId: Long, x: Double, y: Double, z: Double): DoubleArray {
        val transform = transformFor(structureId)
        if (transform.isIdentity) return doubleArrayOf(x, y, z)
        return transform.transformPoint(x, y, z)
    }

    /** Drops both bridges' cached resolutions and every sampled transform; tests and mod reloads. */
    internal fun reset() {
        cache.invalidate()
        ValkyrienClientCompat.reset()
        SableClientCompat.reset()
    }

    /**
     * Whether the stored coordinates of a discovered structure are world space.
     *
     * Exposed so the discovery log can state *why* a correction was applied instead of only that one
     * was; the answer comes from the same [StoredSpace] rule both bridges use.
     */
    internal fun storedSpaceOf(worldHit: Boolean, structureHit: Boolean): StoredSpace =
        com.dreamdisplayx.api.display.geometry.SubLevelLookupPolicy.storedSpaceOf(worldHit, structureHit)

    /** True when [matrix] is a usable 4x4, so a caller can reject a broken pose before composing it. */
    internal fun isUsable(matrix: DoubleArray?): Boolean {
        if (matrix == null || matrix.size != ShipAnchorPolicy.MATRIX_SIZE) return false
        return matrix.all { it.isFinite() }
    }

    /** Logs the resolved backend set once, so a shipped log can explain a display that does not move. */
    fun logResolutionOnce() {
        logger.info(
            "Moving-structure support: Valkyrien Skies={}, Sable={}",
            if (ValkyrienClientCompat.isAvailable) "ready" else "absent",
            if (SableClientCompat.isAvailable) "ready" else "absent",
        )
    }

    /** The pose composition used by Sable, re-exported so a test can reach it without the bridge. */
    internal fun subLevelMatrixOf(
        position: DoubleArray,
        orientation: DoubleArray,
        scale: DoubleArray,
        rotationPoint: DoubleArray,
    ): DoubleArray = SubLevelPose.matrixOf(position, orientation, scale, rotationPoint)
}