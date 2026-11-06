package com.dreamdisplayx.api.display.geometry

import com.dreamdisplayx.api.display.model.property.DisplayFacing
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the ray/box test that makes a hull-mounted display clickable.
 *
 * The bug this guards against is not a wrong comparison but a *missing hit*: once blocks are assembled
 * into a Valkyrien Skies hull they live in the shipyard, so a world-space block clip finds nothing and
 * every sneak-click on the display was reported as a miss. Every case below therefore runs through a
 * display box whose geometry only makes sense once the transform is applied.
 */
class ShipRayHitTest {

    /**
     * Casts at a 4-wide, 3-tall, 1-deep display anchored at (0, 64, 0) facing NORTH.
     *
     * In structure space that box is `x ∈ [0, 4]`, `y ∈ [64, 67]`, `z ∈ [0, 1]`, so its north face
     * (the visible one) is the plane `z = 0` and a viewer south of it looks along -Z.
     */
    private fun northScreenHit(
        originX: Double, originY: Double, originZ: Double,
        dirX: Double, dirY: Double, dirZ: Double,
        transform: AffineTransform = AffineTransform.IDENTITY,
        maxDistance: Double = 64.0,
    ): BoxRayHit? = DisplayGeometry.rayHitOnShip(
        originX, originY, originZ, dirX, dirY, dirZ,
        transform, 0, 64, 0, 4, 3, DisplayFacing.NORTH, 1, maxDistance,
    )

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1.0e-6, message: String = "") {
        assertTrue(abs(expected - actual) <= tolerance, "$message expected=$expected actual=$actual")
    }

    @Test
    fun aStraightOnRayEntersThroughTheBackOfTheBox() {
        // Standing 5 blocks south of the anchor, looking north. The box is one block deep, so the ray
        // crosses its far plane (z = 1) first: 4 blocks out, not 5.
        val hit = assertNotNull(northScreenHit(2.0, 65.0, 5.0, 0.0, 0.0, -1.0))
        assertClose(4.0, hit.distance, message = "entry distance to the z=1 plane")
        assertClose(2.0, hit.x)
        assertClose(65.0, hit.y)
        assertClose(1.0, hit.z)
        // The face that stops the ray points back at the player, i.e. +Z.
        assertClose(0.0, hit.normalX)
        assertClose(0.0, hit.normalY)
        assertClose(1.0, hit.normalZ)
    }

    @Test
    fun aRayThatMissesTheBoxReturnsNull() {
        // Far above the display: the slab test must reject it rather than clamp into the box.
        assertNull(northScreenHit(2.0, 200.0, 5.0, 0.0, 0.0, -1.0))
        // Pointing away from it.
        assertNull(northScreenHit(2.0, 65.0, 5.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun theDisplayFollowsItsTransform() {
        // The same screen riding a hull translated 100 east and 20 up. A ray aimed at where the display
        // *used to be* must miss; one aimed at where it is now must hit. This is the case that used to
        // fail outright, because the world clip only ever saw the vacated space.
        val moved = AffineTransform.ofTranslation(100.0, 20.0, 0.0)
        assertNull(northScreenHit(2.0, 65.0, 5.0, 0.0, 0.0, -1.0, transform = moved))

        val hit = assertNotNull(northScreenHit(102.0, 85.0, 5.0, 0.0, 0.0, -1.0, transform = moved))
        assertClose(4.0, hit.distance)
        assertClose(102.0, hit.x)
        assertClose(85.0, hit.y)
        assertClose(1.0, hit.z)
    }

    @Test
    fun aRotatedHullReportsTheFaceInWorldSpace() {
        // A half turn about Y sends the north-facing surface to face south in the world, so the player
        // now has to stand on the -Z side and the face normal flips with it. Interaction and rendering
        // share this matrix, which is why the two can never disagree about which way the picture looks.
        val spun = AffineTransform.ofYawAndTranslation(180.0, 0.0, 0.0, 0.0)
        val hit = assertNotNull(northScreenHit(-2.0, 65.0, -6.0, 0.0, 0.0, 1.0, transform = spun))
        assertClose(5.0, hit.distance)
        assertClose(-2.0, hit.x)
        assertClose(65.0, hit.y)
        assertClose(-1.0, hit.z)
        // The entry face now looks along -Z in world space.
        assertClose(0.0, hit.normalX)
        assertClose(0.0, hit.normalY)
        assertClose(-1.0, hit.normalZ)
    }

    @Test
    fun maxDistanceIsHonoured() {
        assertNull(northScreenHit(2.0, 65.0, 5.0, 0.0, 0.0, -1.0, maxDistance = 3.0))
        assertNotNull(northScreenHit(2.0, 65.0, 5.0, 0.0, 0.0, -1.0, maxDistance = 5.0))
    }

    @Test
    fun aCollapsedTransformCannotHit() {
        // A degenerate basis has no inverse, so there is no structure space to test against; the test
        // must fail closed instead of dividing by zero and reporting a phantom hit.
        val collapsed = AffineTransform(
            doubleArrayOf(
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )
        assertNull(northScreenHit(2.0, 65.0, 5.0, 0.0, 0.0, -1.0, transform = collapsed))
    }

    @Test
    fun dominantDirectionSnapsAWorldNormalToAFace() {
        assertEquals(DisplayFacing.SOUTH, DisplayGeometry.dominantDirection(0.0, 0.0, 1.0))
        assertEquals(DisplayFacing.NORTH, DisplayGeometry.dominantDirection(0.0, 0.0, -1.0))
        assertEquals(DisplayFacing.EAST, DisplayGeometry.dominantDirection(0.9, 0.1, 0.1))
        assertEquals(DisplayFacing.UP, DisplayGeometry.dominantDirection(0.0, 1.0, 0.0))
        // A normal with no dominant axis (a 45-degree slope) cannot be snapped, so no face is claimed.
        assertNull(DisplayGeometry.dominantDirection(0.0, 0.0, 0.0))
    }
}