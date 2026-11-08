package com.dreamdisplayx.api.display.geometry

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Supplies the ship-to-world matrix for a structure id.
 *
 * The physics mod is optional and cannot be a compile dependency of this fork, so the caller adapts
 * its own API to this one-function interface. `null` means "the structure is not in this world" —
 * an unloaded, removed or merged ship — and must be treated as a failure, never as identity, or a
 * display would be silently teleported onto the world origin.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
fun interface ShipTransformSource {
    /**
     * Returns the ship-to-world matrix for [shipId] as 16 doubles in **column-major** order
     * (`m[column * 4 + row]`, matching JOML), or `null` when the structure is not present.
     */
    fun matrixOf(shipId: Long): DoubleArray?

    companion object {
        /** A source that never knows any structure; used when the physics mod is absent. */
        val NONE: ShipTransformSource = ShipTransformSource { null }
    }
}

/**
 * Decides which transform a display is drawn with, given the structure it claims to ride.
 *
 * Every branch falls back to [AffineTransform.IDENTITY] rather than throwing or producing NaNs:
 *
 * - `shipId == 0` is a world-anchored display, so the transform is identity by definition.
 * - an unknown structure (unloaded, removed, merged) leaves the display at its structure-space
 *   coordinates. That is wrong visually, but it is recoverable and cannot corrupt the render matrix;
 *   the server is responsible for deleting such displays (see [shouldDropAnchor]).
 * - a matrix that is not 4x4, not finite, or degenerate is rejected the same way. A physics blow-up
 *   must not blank every client that renders the screen.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
object ShipAnchorPolicy {
    /** The number of doubles in a column-major 4x4 matrix. */
    const val MATRIX_SIZE = 16

    /**
     * Resolves the transform to render a display with.
     *
     * @param shipId structure id from [ShipAnchor], or `0` for a world-anchored display
     * @param source adapter over the physics mod, or [ShipTransformSource.NONE] when it is absent
     */
    fun transformFor(shipId: Long, source: ShipTransformSource): AffineTransform {
        // Normalise through ShipAnchor so a negative id (a corrupt row) behaves like "no structure".
        if (ShipAnchor.of(shipId) === ShipAnchor.world) return AffineTransform.IDENTITY
        val matrix = runCatching { source.matrixOf(shipId) }.getOrNull() ?: return AffineTransform.IDENTITY
        return matrixToTransform(matrix)
    }

    /**
     * Converts a column-major 16-double matrix into an [AffineTransform], or identity when the matrix
     * cannot be used.
     *
     * Rejects a wrong-sized array, any non-finite component, and a collapsed matrix (`isIdentity`
     * covers the "all zeros" case, which would flatten the picture to a single point).
     */
    fun matrixToTransform(matrix: DoubleArray): AffineTransform {
        if (matrix.size != MATRIX_SIZE) return AffineTransform.IDENTITY
        val transform = AffineTransform(matrix.copyOf())
        if (!transform.isFinite) return AffineTransform.IDENTITY
        return transform
    }

    /**
     * Whether a persisted [storedShipId] must be dropped because the structure no longer exists.
     *
     * Only a display that *claims* a structure can lose it: world-anchored rows (`0`) are never
     * dropped, so a server without the physics mod keeps every existing display untouched.
     */
    fun shouldDropAnchor(storedShipId: Long, shipStillExists: Boolean): Boolean =
        storedShipId > 0L && !shipStillExists

    /**
     * Whether a row's coordinates live in structure space rather than world space.
     *
     * Any check that samples the *world* at a stored coordinate must skip these rows: the blocks they
     * describe are inside a moving structure, so the same numbers point at empty air. That is what
     * `DisplayManager.validateDisplaysAndCleanup` gates on — without it, the base-material census
     * reads air at every ship display and deletes the lot on the next player join.
     */
    fun isShipAnchored(storedShipId: Long): Boolean = storedShipId > 0L

    /**
     * Composes a live structure transform with a discovery-time correction.
     *
     * A display whose stored coordinates are **world** space — built on loose blocks that were
     * assembled into a structure afterwards, which the server could not stamp — has to be drawn as
     * `structureToWorld_now * correction * storedPos`. [live] is `structureToWorld_now` and
     * [correction] is the structure's world-to-structure matrix captured at discovery, so the
     * composition is `live * correction` and the correction is applied **first**.
     *
     * Getting the order backwards rotates the display around the camera instead of around the
     * structure's origin, which looks like the picture swinging wildly as the hull turns — so the
     * order is pinned by a test rather than left to the call site.
     *
     * `null` (a row that is already structure space) returns [live] unchanged, i.e. an identity
     * correction. An identity [live] is returned as-is too: composing with a correction while the
     * structure is not resolvable would move a display that must stay where it is. A non-finite or
     * wrong-sized [correction] is ignored for the same reason — a physics blow-up must not blank or
     * teleport the picture.
     */
    fun composeCorrection(live: AffineTransform, correction: DoubleArray?): AffineTransform {
        if (live.isIdentity) return live
        if (correction == null || correction.size != MATRIX_SIZE) return live
        val composed = AffineTransform(correction.copyOf())
        if (!composed.isFinite) return live
        return live.times(composed)
    }

    /**
     * The parameter types to try, in order, when resolving a Valkyrien Skies level facade method.
     *
     * `Class.getMethod` matches **declared** parameter types exactly; it does not accept a subtype.
     * VS declares its level-taking helpers against the plain `Level` interface (`getAllShips(Level)`,
     * `getShipObjectWorld(Level)`), so a lookup keyed on the client's `ClientLevel` or the server's
     * `ServerLevel` throws `NoSuchMethodException` — and because both bridges cache a failed
     * resolution as "the mod is absent", that one mismatch silently disabled the whole feature. The
     * ship displays then kept their structure-space coordinates while the hull sailed away, which is
     * the "picture stays behind" report.
     *
     * The subtype is tried first purely as future-proofing: a VS release that narrowed a signature
     * would then still resolve, and either order leaves the plain `Level` spelling as the fallback
     * that makes the common case work.
     *
     * Returns *candidate names*, not classes, so this stays in the dependency-free `api` module and
     * can be unit-tested; both bridges map the names onto `ClientLevel`/`ServerLevel`/`Level`
     * themselves.
     */
    fun levelFacadeCandidates(): List<String> = listOf("ClientLevel", "ServerLevel", "Level")
}