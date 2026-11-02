package com.dreamdisplayx.api.display.geometry

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Resolves a display's screen-space pose while it rides a moving structure.
 *
 * The flat renderer places a quad by translating to the anchor block and then applying a
 * facing-dependent rotation. On a structure that rotation is no longer enough: the structure itself
 * has an arbitrary orientation, so the quad has to be built in structure space and then carried into
 * the world by [AffineTransform].
 *
 * This object keeps only the arithmetic. It takes the plain [DisplayPose] the renderer would have
 * built for a static display and pushes it through the structure transform, so the callers (renderer,
 * acoustics, hit-testing) share one implementation and the whole thing stays testable without a
 * Minecraft or physics runtime.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
object ShipPoseMath {
    /**
     * Carries [local] into world space through [transform].
     *
     * A degenerate or non-finite transform returns [local] untouched: a structure whose physics blew
     * up must leave the display at its last sane pose rather than teleporting it to NaN and blanking
     * every client.
     */
    fun toWorld(local: DisplayPose, transform: AffineTransform): DisplayPose {
        if (transform.isIdentity || !transform.isFinite) return local
        val center = transform.transformPoint(local.centerX, local.centerY, local.centerZ)
        val normal = transform.transformDirection(local.normalX, local.normalY, local.normalZ)
        val uAxis = transform.transformDirection(local.uAxisX, local.uAxisY, local.uAxisZ)
        val vAxis = transform.transformDirection(local.vAxisX, local.vAxisY, local.vAxisZ)
        if (!center.all { it.isFinite() } || !normal.all { it.isFinite() } ||
            !uAxis.all { it.isFinite() } || !vAxis.all { it.isFinite() }
        ) return local
        return DisplayPose(
            centerX = center[0], centerY = center[1], centerZ = center[2],
            normalX = normal[0], normalY = normal[1], normalZ = normal[2],
            uAxisX = uAxis[0], uAxisY = uAxis[1], uAxisZ = uAxis[2],
            vAxisX = vAxis[0], vAxisY = vAxis[1], vAxisZ = vAxis[2],
        )
    }

    /**
     * Maps a point in structure space to world space; used for audio emitter placement and for
     * converting a click hit back into the structure's own block grid.
     */
    fun pointToWorld(x: Double, y: Double, z: Double, transform: AffineTransform): DoubleArray {
        if (!transform.isFinite) return doubleArrayOf(x, y, z)
        val out = transform.transformPoint(x, y, z)
        return if (out.all { it.isFinite() }) out else doubleArrayOf(x, y, z)
    }

    /**
     * Whether a world-space point lies within [radius] blocks of the display's surface, measured
     * against the *world* pose. Static displays can compare block coordinates directly; a moving one
     * cannot, because the same structure-space block is a different world point every tick.
     */
    fun isWithinRadius(worldPose: DisplayPose, x: Double, y: Double, z: Double, radius: Double): Boolean {
        if (radius <= 0.0 || !radius.isFinite()) return false
        val dx = x - worldPose.centerX
        val dy = y - worldPose.centerY
        val dz = z - worldPose.centerZ
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }

    /**
     * Stretches a flat quad's half-extents through the structure transform, returning the world-space
     * half-vector along the width in `[0..2]` and along the height in `[3..5]` of a single [DoubleArray].
     *
     * A scaled structure makes the picture larger, so the renderer must scale the quad by the same
     * factor or the texture would be stretched wrong. A flat return array is used instead of
     * `Pair<DoubleArray, DoubleArray>` on purpose: `kotlin.Pair` in a public signature would be
     * relocated to `com.dreamdisplayx.libs.kotlin.*` in the shaded jar and stop matching what an
     * addon compiled against upstream expects.
     */
    fun halfExtents(
        transform: AffineTransform,
        uAxis: DoubleArray,
        vAxis: DoubleArray,
        halfWidth: Double,
        halfHeight: Double,
    ): DoubleArray {
        val u = if (transform.isFinite) transform.transformVector(
            uAxis[0] * halfWidth, uAxis[1] * halfWidth, uAxis[2] * halfWidth,
        ) else doubleArrayOf(uAxis[0] * halfWidth, uAxis[1] * halfWidth, uAxis[2] * halfWidth)
        val v = if (transform.isFinite) transform.transformVector(
            vAxis[0] * halfHeight, vAxis[1] * halfHeight, vAxis[2] * halfHeight,
        ) else doubleArrayOf(vAxis[0] * halfHeight, vAxis[1] * halfHeight, vAxis[2] * halfHeight)
        return doubleArrayOf(u[0], u[1], u[2], v[0], v[1], v[2])
    }
}