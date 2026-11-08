package com.dreamdisplayx.api.display.geometry

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Routes a persisted structure id to the physics mod that owns it.
 *
 * The fork supports two different moving-structure mods and they do not agree on anything except
 * that a display can ride one:
 *
 * | | Valkyrien Skies | Sable |
 * |---|---|---|
 * | id | `long` (`Ship.getId()`) | `UUID` (`SubLevel.getUniqueId()`) |
 * | world pose | `ShipTransform.getShipToWorld()` | `SubLevel.logicalPose()` / `renderPose(pt)` |
 * | structure-space boxes | `getShipAABB()` | `LevelPlot.getBoundingBox()` |
 * | world-space boxes | `getWorldAABB()` | `SubLevel.boundingBox()` |
 *
 * Everything downstream of the anchor — the renderer, the distance census, the acoustic plane, the
 * crosshair ray test, `ShipTransformCache` — only needs "give me the 16 doubles for this id". So the
 * dispatch happens exactly once, here, and no other code has to know which mod is installed.
 *
 * Dispatch is on [SubLevelId.SABLE_FLAG] rather than on "which bridge answered", because the answer
 * has to be stable across a save/reload and across a client that has only one of the two mods. A
 * Valkyrien Skies ship id is a small increasing `long` and can never reach bit 62, so the flag is an
 * unambiguous discriminator.
 *
 * A source that cannot answer returns `null`, never identity: an unloaded structure must keep the
 * display at its stored coordinates rather than teleport it to the world origin. See
 * [ShipAnchorPolicy.transformFor], which repeats that rule for the single-mod case.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
class StructureTransforms(
    /** Answers Valkyrien Skies ship ids; [ShipTransformSource.NONE] when VS is not installed. */
    private val ships: ShipTransformSource = ShipTransformSource.NONE,
    /** Answers Sable sub-level ids; [ShipTransformSource.NONE] when Sable is not installed. */
    private val subLevels: ShipTransformSource = ShipTransformSource.NONE,
) : ShipTransformSource {

    /** True when at least one physics mod bridge resolved and can answer for its own id space. */
    val hasAnyBackend: Boolean
        get() = ships !== ShipTransformSource.NONE || subLevels !== ShipTransformSource.NONE

    /**
     * The structure-to-world matrix for [shipId], from whichever mod owns that id space.
     *
     * A throwing backend degrades to `null` for that one lookup instead of propagating: this runs
     * from the render thread, and a physics mod that throws mid-frame must leave the picture where
     * the last good frame put it.
     */
    override fun matrixOf(shipId: Long): DoubleArray? {
        val source = sourceFor(shipId) ?: return null
        return runCatching { source.matrixOf(shipId) }.getOrNull()
    }

    /**
     * The backend that would answer [shipId], or `null` when none can.
     *
     * Public so a diagnostic (and the tests) can assert the routing without going through a matrix.
     */
    fun sourceFor(shipId: Long): ShipTransformSource? = when {
        // A world-anchored display asks nothing of either mod.
        !ShipAnchorPolicy.isShipAnchored(shipId) -> null
        SubLevelId.isSubLevel(shipId) -> subLevels.takeIf { it !== ShipTransformSource.NONE }
        else -> ships.takeIf { it !== ShipTransformSource.NONE }
    }

    /** Which mod [shipId] belongs to, for logging and for the census that decides whether to drop it. */
    fun ownerOf(shipId: Long): StructureOwner = when {
        !ShipAnchorPolicy.isShipAnchored(shipId) -> StructureOwner.NONE
        SubLevelId.isSubLevel(shipId) -> StructureOwner.SABLE
        else -> StructureOwner.VALKYRIEN_SKIES
    }

    companion object {
        /** A dispatcher with no backend installed; every lookup answers `null`. */
        val NONE: StructureTransforms = StructureTransforms()
    }
}

/**
 * The physics mod a structure id belongs to.
 *
 * Used where the *answer* matters rather than the matrix: the discovery probe has to ask the right
 * mod whether a block is inside a structure, and the server-side cleanup has to know whether a
 * missing structure means "drop the row" or "the mod is not installed on this server".
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
enum class StructureOwner {
    /** A display anchored directly in the world; no mod is involved. */
    NONE,

    /** A Valkyrien Skies ship, `long`-identified. */
    VALKYRIEN_SKIES,

    /** A Sable sub-level, `UUID`-identified and stored hashed. */
    SABLE,
}