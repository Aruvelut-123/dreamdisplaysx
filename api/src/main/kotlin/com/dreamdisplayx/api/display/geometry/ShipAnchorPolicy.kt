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
}