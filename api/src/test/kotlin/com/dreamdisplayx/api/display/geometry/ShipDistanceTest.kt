package com.dreamdisplayx.api.display.geometry

import com.dreamdisplayx.api.display.model.property.DisplayFacing
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the two helpers that let a ship-anchored display behave like a world-anchored one:
 * [AffineTransform.inversePoint] and [DisplayGeometry.distanceToOnShip].
 *
 * Both exist because a ship display stores *structure* coordinates while the client measures
 * everything against *world* positions. Without the inverse mapping the client treats a ship that has
 * sailed away as out of render distance and unloads a screen the player is standing in front of.
 */
class ShipDistanceTest {

    private fun assertClose(expected: Double, actual: Double, message: String) {
        assertTrue(abs(expected - actual) <= 1.0e-9, "$message expected=$expected actual=$actual")
    }

    @Test
    fun `inversePoint undoes a translation`() {
        val transform = AffineTransform.ofTranslation(120.0, -8.0, 340.0)
        val local = assertNotNull(transform.inversePoint(127.0, -8.0, 335.0))
        assertClose(7.0, local[0], "x")
        assertClose(0.0, local[1], "y")
        assertClose(-5.0, local[2], "z")
    }

    @Test
    fun `inversePoint undoes a rotation`() {
        val transform = AffineTransform.ofYawAndTranslation(90.0, 10.0, 0.0, 0.0)
        // A yaw of 90 degrees maps local +X onto world -Z; the inverse must send it back.
        val world = transform.transformPoint(3.0, 0.0, 0.0)
        val local = assertNotNull(transform.inversePoint(world[0], world[1], world[2]))
        assertClose(3.0, local[0], "x")
        assertClose(0.0, local[1], "y")
        assertClose(0.0, local[2], "z")
    }

    @Test
    fun `inversePoint rejects a collapsed basis instead of producing infinities`() {
        val collapsed = AffineTransform(
            doubleArrayOf(
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                1.0, 2.0, 3.0, 1.0,
            ),
        )
        assertNull(collapsed.inversePoint(5.0, 5.0, 5.0))
    }

    @Test
    fun `a ship display measures distance from where the ship actually is`() {
        // 10x10 screen at structure origin, ship parked 500 blocks away along X.
        val transform = AffineTransform.ofTranslation(500.0, 0.0, 0.0)
        val playerOnTheSurface = DisplayGeometry.distanceToOnShip(
            500.0, 2.0, 0.5, transform,
            minX = 0, minY = 0, minZ = 0, width = 10, height = 10, facing = DisplayFacing.SOUTH,
        )
        // Inside the box's X/Y range and within its one-block depth, so the distance is zero.
        assertClose(0.0, playerOnTheSurface, "a player beside the hull is next to the screen")

        // One block in front of the screen face (structure z = 1.05) must also be zero, since the
        // box's far edge is at z = 1.
        val justBehind = DisplayGeometry.distanceToOnShip(
            505.0, 5.0, 1.0, transform,
            minX = 0, minY = 0, minZ = 0, width = 10, height = 10, facing = DisplayFacing.SOUTH,
        )
        assertClose(0.0, justBehind, "the far face of the box is still distance zero")

        // The structure-space test alone would report the full 500-block displacement.
        val naive = DisplayGeometry.distanceTo(
            500.0, 2.0, 0.5,
            minX = 0, minY = 0, minZ = 0, width = 10, height = 10, facing = DisplayFacing.SOUTH,
        )
        assertTrue(naive > 400.0, "sanity: the untransformed distance is the far one, got $naive")
    }

    @Test
    fun `a rotated ship still measures a nearby player as nearby`() {
        val transform = AffineTransform.ofYawAndTranslation(37.0, -200.0, 64.0, 900.0)
        // Take a known structure-space point on the screen and carry it to where the player would be.
        val world = transform.transformPoint(5.0, 5.0, 1.0)
        val distance = DisplayGeometry.distanceToOnShip(
            world[0], world[1], world[2], transform,
            minX = 0, minY = 0, minZ = 0, width = 10, height = 10, facing = DisplayFacing.SOUTH,
        )
        assertClose(0.0, distance, "a point on the screen surface must measure 0")
    }

    @Test
    fun `world anchored displays keep the plain block distance`() {
        // transformFor returns identity for an unresolvable ship; the inverse of identity is identity,
        // so the ship-aware path must agree with the plain one exactly. Compare against the *double*
        // overload: it measures to the block's outer edge (maxX + 1), while the BlockPos overload
        // measures to the block centre, so those two legitimately differ by one.
        val plain = DisplayGeometry.distanceTo(
            20.0, 5.0, 0.0,
            minX = 0, minY = 0, minZ = 0, width = 10, height = 10, facing = DisplayFacing.SOUTH,
        )
        val viaShip = DisplayGeometry.distanceToOnShip(
            20.0, 5.0, 0.0, AffineTransform.IDENTITY,
            minX = 0, minY = 0, minZ = 0, width = 10, height = 10, facing = DisplayFacing.SOUTH,
        )
        assertEquals(plain, viaShip, "identity must not change the answer")
    }

    @Test
    fun `a non invertible transform falls back without returning NaN`() {
        val broken = AffineTransform(
            doubleArrayOf(
                Double.NaN, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )
        val distance = DisplayGeometry.distanceToOnShip(
            3.0, 3.0, 3.0, broken,
            minX = 0, minY = 0, minZ = 0, width = 4, height = 4, facing = DisplayFacing.SOUTH,
        )
        assertTrue(distance.isFinite(), "a broken matrix must never produce NaN, got $distance")
    }

    @Test
    fun `a scaled structure maps observer distances through the scale`() {
        // A structure scaled by 2 doubles every offset, so an observer two world blocks away is one
        // structure block away.
        val scaled = AffineTransform(
            doubleArrayOf(
                2.0, 0.0, 0.0, 0.0,
                0.0, 2.0, 0.0, 0.0,
                0.0, 0.0, 2.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )
        // Screen occupies structure x in 0..10; observer sits at world x = 22, i.e. structure x = 11,
        // one structure block past the screen's right edge.
        val distance = DisplayGeometry.distanceToOnShip(
            22.0, 0.0, 0.0, scaled,
            minX = 0, minY = 0, minZ = 0, width = 10, height = 1, facing = DisplayFacing.SOUTH,
        )
        assertClose(1.0, distance, "one structure block away")
    }
}