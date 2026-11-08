package com.dreamdisplayx.api.display.geometry

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * The parts of Sable sub-level resolution that can be decided without the mod on the classpath.
 *
 * Sable identifies a sub-level with a `java.util.UUID`, but the whole anchoring pipeline speaks
 * `Long` (see [SubLevelId]). Resolving a stored id therefore means walking the loaded sub-levels,
 * hashing each one's UUID and comparing — the hash cannot be reversed. That walk is where the two
 * bugs that matter live, and both are pure logic:
 *
 * 1. **A hash collision must not silently pick the wrong structure.** The scan takes the first exact
 *    match of a 61-bit hash, so a real collision needs two *loaded* structures; the index built by
 *    [indexById] keeps the first and lets the caller log the rest instead of flickering between them.
 * 2. **A candidate that could not be read must not abort the scan.** One sub-level whose UUID getter
 *    threw (a half-torn-down structure, a mod version that renamed a member) must cost that one
 *    candidate, not every display in the world.
 *
 * The bounds helpers cover the other half: Sable reports **two** boxes per sub-level and they live in
 * different coordinate spaces — `LevelPlot.getBoundingBox()` is the structure-space voxel bound while
 * `SubLevel.boundingBox()` is the world-space one. Which box contains a stored coordinate is what
 * decides whether that coordinate needs the world-to-structure correction, exactly as
 * [ShipAnchorDetectPolicy] does for Valkyrien Skies.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
object SubLevelLookupPolicy {
    /** Number of doubles in a bounds array: `minX, minY, minZ, maxX, maxY, maxZ`. */
    const val BOUNDS_SIZE: Int = 6

    /**
     * Finds the loaded sub-level whose hashed id is [target], or `null` when none matches.
     *
     * [idOf] returns `null` for a candidate that could not be read (no id, a thrown getter); such a
     * candidate is skipped rather than ending the scan. A [target] of [SubLevelId.NONE] resolves to
     * `null` immediately — a world-anchored display asks nothing of any physics mod.
     *
     * Deliberately does **not** inspect [SubLevelId.SABLE_FLAG]: a caller that already owns a
     * `SubLevel` should be able to resolve any id it is handed, and the flag is a
     * `StructureTransforms` dispatch concern rather than a lookup one.
     */
    fun <T> findByHashedId(
        candidates: Iterable<T>,
        target: Long,
        idOf: (T) -> Long?,
    ): T? {
        if (target == SubLevelId.NONE) return null
        for (candidate in candidates) {
            val id = runCatching { idOf(candidate) }.getOrNull() ?: continue
            if (id == target) return candidate
        }
        return null
    }

    /**
     * Builds the `hashed id -> candidate` index for a whole loaded set.
     *
     * Used so a scene with several sub-level displays pays for **one** scan per lookup window instead
     * of one per display. On a collision the first candidate wins and later ones are dropped: picking
     * a stable winner matters more than picking a "right" one, because a display that alternates
     * between two structures every frame is far worse than one that consistently draws on the wrong
     * structure and is reported once.
     *
     * Candidates that cannot be read, or that hash to [SubLevelId.NONE], are left out entirely.
     */
    fun <T> indexById(candidates: Iterable<T>, idOf: (T) -> Long?): Map<Long, T> {
        val out = HashMap<Long, T>()
        for (candidate in candidates) {
            val id = runCatching { idOf(candidate) }.getOrNull() ?: continue
            if (id == SubLevelId.NONE) continue
            out.putIfAbsent(id, candidate)
        }
        return out
    }

    /**
     * Reads one sub-level's box as six doubles, or `null` when it cannot be read.
     *
     * Sable exposes the components one at a time (`minX()` … `maxZ()`) through `BoundingBox3dc`,
     * so the caller supplies them in that order. A box whose maximum is below its minimum is
     * rejected: `LevelPlot.getBoundingBox()` returns `BoundingBox3i.EMPTY` for a plot that has not
     * been filled yet, and treating that as a real box would claim every point in the world.
     */
    fun boundsOf(
        minX: Double, minY: Double, minZ: Double,
        maxX: Double, maxY: Double, maxZ: Double,
    ): DoubleArray? {
        if (!minX.isFinite() || !minY.isFinite() || !minZ.isFinite()) return null
        if (!maxX.isFinite() || !maxY.isFinite() || !maxZ.isFinite()) return null
        if (maxX < minX || maxY < minY || maxZ < minZ) return null
        return doubleArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /**
     * Whether the six-double box [bounds] contains ([x], [y], [z]).
     *
     * Inclusive on both ends, matching Sable's own `BoundingBox3dc.contains`. A `null` bounds array
     * (an unreadable box) contains nothing — fail closed, because the alternative is claiming the
     * block belongs to a structure that cannot be described.
     */
    fun contains(bounds: DoubleArray?, x: Double, y: Double, z: Double): Boolean {
        if (bounds == null || bounds.size != BOUNDS_SIZE) return false
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        return x >= bounds[0] && x <= bounds[3] &&
            y >= bounds[1] && y <= bounds[4] &&
            z >= bounds[2] && z <= bounds[5]
    }

    /**
     * Whether a row whose coordinates are inside [structureBounds] needs the world-to-structure
     * correction before it can be rendered.
     *
     * Sable reports the structure-space box (`LevelPlot.getBoundingBox()`) and the world-space box
     * (`SubLevel.boundingBox()`) separately, and a freshly assembled sub-level satisfies **both**:
     * assembling a ship does not move the blocks that were already placed. So the box test alone is
     * ambiguous, and the decision has to follow the same rule the server's stamp used —
     * [ShipAnchorDetectPolicy]'s "a world-space hit means the stored numbers are world space".
     *
     * The order therefore matters and is asserted by [SubLevelLookupPolicyTest]:
     *
     * - world-space hit (with or without a structure-space hit) -> the stored numbers are world
     *   space. The "both" case is a freshly assembled structure; taking the world-space reading
     *   there is right because that is the state a row is stamped in, and the correction captured at
     *   discovery starts as the identity, so the picture does not jump when it is first pinned.
     * - structure-space hit only -> the row is already structure space, so it must not be projected
     *   a second time.
     * - neither -> nothing describes this coordinate; the caller reports `null` and never invents a
     *   correction.
     *
     * Returning a three-state answer rather than a `Boolean` keeps "not found" distinguishable from
     * "found and already structure space": both mean "no correction", but only one of them means the
     * probe succeeded, and the discovery log needs that difference.
     */
    fun storedSpaceOf(worldHit: Boolean, structureHit: Boolean): StoredSpace = when {
        worldHit -> StoredSpace.WORLD
        structureHit -> StoredSpace.STRUCTURE
        else -> StoredSpace.UNKNOWN
    }

    /**
     * The id a **server-side** stamp should persist for a candidate found at a display's anchor.
     *
     * The client may adopt either space — it also captures the correction that converts one into the
     * other — but a stamp has no such luxury: it writes a bare id, and every client that reads the row
     * will then render `subLevelToWorld * storedPos`. That multiplication is only correct when the
     * stored numbers are already structure space.
     *
     * So a [StoredSpace.WORLD] hit returns [SubLevelId.NONE] on purpose. The row stays world-anchored,
     * and the client's own discovery probe picks the structure up together with the
     * world-to-structure correction it needs. Stamping there instead would apply the structure's
     * motion twice — once by the mod to the world, once by the renderer to the position — and fling
     * the picture off into the void.
     *
     * A [StoredSpace.UNKNOWN] candidate is not this block's structure at all, so it stamps nothing
     * either.
     */
    fun stampIdFor(space: StoredSpace, candidateId: Long): Long =
        if (space === StoredSpace.STRUCTURE) candidateId else SubLevelId.NONE
}

/**
 * Which coordinate space a persisted display row was stamped in.
 *
 * Mirrors the two spaces a sub-level has: the world-space box is where the blocks are *now*, the
 * structure-space box is where they live inside the plot after the hull moved. A row carries one of
 * the two, and only [WORLD] needs the world-to-structure correction before it can be rendered.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
enum class StoredSpace {
    /** The row stores world coordinates; it needs the discovery-time correction. */
    WORLD,

    /** The row already stores structure coordinates; projecting it again would double the move. */
    STRUCTURE,

    /** Neither box contained the coordinate, so the row cannot be attributed to this sub-level. */
    UNKNOWN,
    ;

    /** Whether this row needs `worldToStructure` applied before the live pose. */
    val needsCorrection: Boolean get() = this === WORLD
}
