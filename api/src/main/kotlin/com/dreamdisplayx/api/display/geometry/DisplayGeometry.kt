package com.dreamdisplayx.api.display.geometry
import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.display.model.property.DisplayFacing
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt


/**
 * The block-space math `Dream Displays` itself uses for its displays: how far a point is from
 * a display, whether it is inside one, where a display's center and axes are.
 *
 * A display is described by its minimum corner block, its width and height in blocks, its facing
 * and its depth (blocks it extends away from the wall, `1` for a flat display).
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
object DisplayGeometry {
    private inline fun <T> withBounds(
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int,
        block: (maxX: Int, maxY: Int, maxZ: Int) -> T,
    ): T {
        var maxX = minX
        var maxZ = minZ
        var maxY = minY + height - 1
        when (facing) {
            DisplayFacing.NORTH, DisplayFacing.SOUTH -> {
                maxX += width - 1
                maxZ += depth - 1
            }
            DisplayFacing.EAST, DisplayFacing.WEST -> {
                maxZ += width - 1
                maxX += depth - 1
            }
            DisplayFacing.UP, DisplayFacing.DOWN -> {
                maxX += width - 1
                maxZ += height - 1
                maxY = minY + depth - 1
            }
        }
        return block(maxX, maxY, maxZ)
    }

    /** True when the block ([x], [y], [z]) is one of the display's blocks. */
    @JvmStatic
    @JvmOverloads
    fun isInBounds(
        x: Int, y: Int, z: Int,
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int = 1,
    ): Boolean = withBounds(minX, minY, minZ, width, height, facing, depth) { maxX, maxY, maxZ ->
        x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }

    /** True when the point ([x], [y], [z]) lies inside the display's box. */
    @JvmStatic
    @JvmOverloads
    fun isInBounds(
        x: Double, y: Double, z: Double,
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int = 1,
    ): Boolean = withBounds(minX, minY, minZ, width, height, facing, depth) { maxX, maxY, maxZ ->
        x >= minX && x <= maxX + 1 && y >= minY && y <= maxY + 1 && z >= minZ && z <= maxZ + 1
    }

    /**
     * Entry point of a ray against a display's box, for a display that rides a moving structure.
     *
     * The box is stored in *structure* space, so the ray is mapped in through the inverse transform
     * and the slab test runs there; the affine inverse preserves the ray parameter, so [BoxRayHit.distance]
     * comes back in world blocks (exact for a rigid hull, close for a scaled one).
     *
     * Needed because a world-space block clip cannot answer this question on an assembled ship: the
     * blocks now live in the shipyard, so `Level.clip` simply misses and every click on a hull-mounted
     * display is reported as a miss even though the picture is right in front of the player.
     *
     * Returns `null` when the ray misses, when the transform has no inverse, or when the ray starts
     * inside the box (a camera inside a block has no face to report).
     */
    fun rayHitOnShip(
        originX: Double, originY: Double, originZ: Double,
        dirX: Double, dirY: Double, dirZ: Double,
        transform: AffineTransform,
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int = 1,
        maxDistance: Double = Double.MAX_VALUE,
    ): BoxRayHit? {
        val origin = transform.inversePoint(originX, originY, originZ) ?: return null
        val ahead = transform.inversePoint(originX + dirX, originY + dirY, originZ + dirZ) ?: return null
        val slab = DoubleArray(3)
        val hit = withBounds(minX, minY, minZ, width, height, facing, depth) { maxX, maxY, maxZ ->
            slabEntry(
                origin[0], origin[1], origin[2],
                ahead[0] - origin[0], ahead[1] - origin[1], ahead[2] - origin[2],
                minX.toDouble(), maxX + 1.0,
                minY.toDouble(), maxY + 1.0,
                minZ.toDouble(), maxZ + 1.0,
                slab,
            )
        }
        if (!hit) return null
        val distance = slab[0]
        if (distance <= 0.0 || distance > maxDistance) return null
        val normal = when (slab[1].toInt()) {
            0 -> doubleArrayOf(slab[2], 0.0, 0.0)
            1 -> doubleArrayOf(0.0, slab[2], 0.0)
            else -> doubleArrayOf(0.0, 0.0, slab[2])
        }
        val worldNormal = transform.transformDirection(normal[0], normal[1], normal[2])
        return BoxRayHit(
            distance = distance,
            x = originX + dirX * distance,
            y = originY + dirY * distance,
            z = originZ + dirZ * distance,
            normalX = worldNormal[0],
            normalY = worldNormal[1],
            normalZ = worldNormal[2],
        )
    }

    /** Tolerance for a ray parallel to a slab plane. */
    private const val RAY_EPSILON: Double = 1.0e-12

    /**
     * Slab test of the ray ([ox], [oy], [oz]) + t * ([dx], [dy], [dz]) against an axis-aligned box.
     *
     * Writes `[tEnter, axis, sign]` into [out]: the entry distance, which axis was crossed, and the
     * sign of that axis' outward normal. Returns `false` on a miss, including a ray that starts
     * inside the box (no entry face to report).
     */
    private fun slabEntry(
        ox: Double, oy: Double, oz: Double,
        dx: Double, dy: Double, dz: Double,
        minX: Double, maxX: Double,
        minY: Double, maxY: Double,
        minZ: Double, maxZ: Double,
        out: DoubleArray,
    ): Boolean {
        var tEnter = 0.0
        var tExit = Double.MAX_VALUE
        var axis = 0
        var sign = 0.0
        var entered = false
        // One pass per axis; the axis/sign of the entry plane is what the caller turns into a normal.
        val origins = doubleArrayOf(ox, oy, oz)
        val deltas = doubleArrayOf(dx, dy, dz)
        val lo = doubleArrayOf(minX, minY, minZ)
        val hi = doubleArrayOf(maxX, maxY, maxZ)
        for (i in 0..2) {
            val o = origins[i]
            val d = deltas[i]
            if (abs(d) <= RAY_EPSILON) {
                // Parallel to this pair of planes: the ray must already lie between them.
                if (o < lo[i] || o > hi[i]) return false
                continue
            }
            var near = (lo[i] - o) / d
            var far = (hi[i] - o) / d
            var normalSign = -1.0
            if (near > far) {
                val swap = near
                near = far
                far = swap
                normalSign = 1.0
            }
            if (near > tEnter) {
                tEnter = near
                axis = i
                sign = normalSign
                entered = true
            }
            if (far < tExit) tExit = far
            if (tEnter > tExit) return false
        }
        if (!entered) return false
        out[0] = tEnter
        out[1] = axis.toDouble()
        out[2] = sign
        return true
    }

    /**
     * The block face a world-space normal points along, or `null` when it is not axis aligned.
     *
     * A hull can be pitched or rolled, so the display's own `facing` no longer describes the face the
     * player actually clicked; the normal has to be mapped into the world first and then snapped to
     * whichever axis dominates.
     */
    fun dominantDirection(normalX: Double, normalY: Double, normalZ: Double): DisplayFacing? {
        val ax = abs(normalX)
        val ay = abs(normalY)
        val az = abs(normalZ)
        if (ax < 0.5 && ay < 0.5 && az < 0.5) return null
        if (ax >= ay && ax >= az) return if (normalX > 0) DisplayFacing.EAST else DisplayFacing.WEST
        if (ay >= ax && ay >= az) return if (normalY > 0) DisplayFacing.UP else DisplayFacing.DOWN
        return if (normalZ > 0) DisplayFacing.SOUTH else DisplayFacing.NORTH
    }

    /** Distance from the block ([x], [y], [z]) to the nearest block of the display. */
    @JvmStatic
    @JvmOverloads
    fun distanceTo(
        x: Int, y: Int, z: Int,
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int = 1,
    ): Double = withBounds(minX, minY, minZ, width, height, facing, depth) { maxX, maxY, maxZ ->
        val dx = (x - min(max(x, minX), maxX)).toLong()
        val dy = (y - min(max(y, minY), maxY)).toLong()
        val dz = (z - min(max(z, minZ), maxZ)).toLong()
        sqrt((dx * dx + dy * dy + dz * dz).toDouble())
    }

    /**
     * Shortest distance from a world point to a display's box, for a display that rides a moving
     * structure.
     *
     * The display's box is expressed in *structure* space (that is what it stores), so a raw
     * block-coordinate comparison against a world position is meaningless — on a ship that has
     * travelled a few hundred blocks it reports "very far" and the client unloads a screen the player
     * is standing in front of. Mapping the observer back through the inverse transform recovers the
     * structure-space point the existing box test expects, so no second distance implementation is
     * needed.
     *
     * Falls back to [distanceTo] in structure space when the transform has no inverse (a collapsed or
     * non-finite basis): wrong by the structure's own displacement, but never `NaN` and never far
     * enough to hide a working display.
     */
    fun distanceToOnShip(
        x: Double, y: Double, z: Double,
        transform: AffineTransform,
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int,
        facing: DisplayFacing, depth: Int = 1,
    ): Double {
        val local = transform.inversePoint(x, y, z) ?: return distanceTo(x, y, z, minX, minY, minZ, width, height, facing, depth)
        return distanceTo(local[0], local[1], local[2], minX, minY, minZ, width, height, facing, depth)
    }

    /** Distance from the point ([x], [y], [z]) to the display's box; `0` inside it. */
    @JvmStatic
    @JvmOverloads
    fun distanceTo(
        x: Double, y: Double, z: Double,
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int = 1,
    ): Double = withBounds(minX, minY, minZ, width, height, facing, depth) { maxX, maxY, maxZ ->
        val dx = x - min(max(x, minX.toDouble()), maxX + 1.0)
        val dy = y - min(max(y, minY.toDouble()), maxY + 1.0)
        val dz = z - min(max(z, minZ.toDouble()), maxZ + 1.0)
        sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Writes the center (`out[0..2]`) and radius (`out[3]`) of the sphere around the display's box into [out],
     * without allocating.
     *
     * Used for per-frame culling.
     */
    @JvmStatic
    fun boundingSphere(
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int, out: DoubleArray,
    ) {
        withBounds(minX, minY, minZ, width, height, facing, depth) { maxX, maxY, maxZ ->
            val sx = (maxX + 1 - minX).toDouble()
            val sy = (maxY + 1 - minY).toDouble()
            val sz = (maxZ + 1 - minZ).toDouble()
            out[0] = minX + sx * 0.5
            out[1] = minY + sy * 0.5
            out[2] = minZ + sz * 0.5
            out[3] = 0.5 * sqrt(sx * sx + sy * sy + sz * sz)
        }
    }

    /**
     * The display's center and axes. Block-center precision with no offset from the surface: it's good for placing a
     * sound source or an effect, but, obviously, not for drawing the picture.
     */
    @JvmStatic
    @JvmOverloads
    fun pose(
        minX: Int, minY: Int, minZ: Int, width: Int, height: Int, facing: DisplayFacing, depth: Int = 1,
    ): DisplayPose = withBounds(minX, minY, minZ, width, height, facing, depth) { maxX, maxY, maxZ ->
        val cx = (minX + maxX + 1) / 2.0
        val cy = (minY + maxY + 1) / 2.0
        val cz = (minZ + maxZ + 1) / 2.0
        val (nx, ny, nz) = when (facing) {
            DisplayFacing.NORTH -> Triple(0.0, 0.0, -1.0)
            DisplayFacing.SOUTH -> Triple(0.0, 0.0, 1.0)
            DisplayFacing.EAST -> Triple(1.0, 0.0, 0.0)
            DisplayFacing.WEST -> Triple(-1.0, 0.0, 0.0)
            DisplayFacing.UP -> Triple(0.0, 1.0, 0.0)
            DisplayFacing.DOWN -> Triple(0.0, -1.0, 0.0)
        }
        val (ux, uy, uz) = when (facing) {
            DisplayFacing.NORTH -> Triple(-1.0, 0.0, 0.0)
            DisplayFacing.SOUTH -> Triple(1.0, 0.0, 0.0)
            DisplayFacing.EAST -> Triple(0.0, 0.0, -1.0)
            DisplayFacing.WEST -> Triple(0.0, 0.0, 1.0)
            DisplayFacing.UP, DisplayFacing.DOWN -> Triple(1.0, 0.0, 0.0)
        }
        val (vx, vy, vz) = when (facing) {
            DisplayFacing.UP, DisplayFacing.DOWN -> Triple(0.0, 0.0, 1.0)
            else -> Triple(0.0, 1.0, 0.0)
        }
        DisplayPose(cx, cy, cz, nx, ny, nz, ux, uy, uz, vx, vy, vz)
    }
}

/**
 * A ray's entry point into a display's box, in **world** space, with the outward face normal.
 *
 * Produced by [DisplayGeometry.rayHitOnShip]; the normal is already mapped out of structure space, so
 * a caller can compare it to a plain `Direction` without knowing whether the display is on a hull.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
data class BoxRayHit(
    /** Distance from the ray origin to the entry point, in world blocks. */
    val distance: Double,
    /** World X of the entry point. */
    val x: Double,
    /** World Y of the entry point. */
    val y: Double,
    /** World Z of the entry point. */
    val z: Double,
    /** World X of the outward face normal (unit length). */
    val normalX: Double,
    /** World Y of the outward face normal (unit length). */
    val normalY: Double,
    /** World Z of the outward face normal (unit length). */
    val normalZ: Double,
)
