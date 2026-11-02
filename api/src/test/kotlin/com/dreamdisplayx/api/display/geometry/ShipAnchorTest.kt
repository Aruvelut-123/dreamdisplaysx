package com.dreamdisplayx.api.display.geometry

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the arithmetic that carries a display onto a moving structure.
 *
 * Everything here is pure JVM: no platform matrix type, no physics runtime. That is the whole point of
 * [AffineTransform] existing separately from the ship API — this layer is the only part of the ship
 * integration a CI job can actually prove correct.
 */
class ShipAnchorTest {

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1.0e-9, message: String = "") {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$message expected=$expected actual=$actual tolerance=$tolerance",
        )
    }

    private fun assertPoint(expected: DoubleArray, actual: DoubleArray, tolerance: Double = 1.0e-9) {
        assertEquals(3, actual.size, "a transformed point is three components")
        for (i in 0..2) assertClose(expected[i], actual[i], tolerance, "component $i")
    }

    @Test
    fun worldAnchorIsTheDefaultAndSurvivesAnAbsentShipId() {
        assertFalse(ShipAnchor.world.isOnShip)
        assertEquals(ShipAnchor.NONE, ShipAnchor.world.shipId)
        assertEquals(ShipAnchor.world, ShipAnchor.ofNullable(null))
        assertEquals(ShipAnchor.world, ShipAnchor.of(ShipAnchor.NONE))
        assertFalse(ShipAnchor.of(-5L).isOnShip, "a negative id is not a structure")
        assertEquals(42L, ShipAnchor.of(42L).shipId)
        assertTrue(ShipAnchor.of(42L).isOnShip)
    }

    @Test
    fun identityTransformLeavesAPointAndADirectionAlone() {
        assertPoint(doubleArrayOf(3.0, -7.0, 11.5), AffineTransform.IDENTITY.transformPoint(3.0, -7.0, 11.5))
        assertPoint(doubleArrayOf(0.0, 1.0, 0.0), AffineTransform.IDENTITY.transformVector(0.0, 1.0, 0.0))
        assertTrue(AffineTransform.IDENTITY.isIdentity)
        assertTrue(AffineTransform.IDENTITY.isFinite)
    }

    @Test
    fun translationMovesPointsButNotDirections() {
        val t = AffineTransform.ofTranslation(100.0, -20.0, 3.0)
        assertPoint(doubleArrayOf(101.0, -18.0, 4.0), t.transformPoint(1.0, 2.0, 1.0))
        // A direction must ignore the translation entirely, otherwise a moving ship would drag every
        // normal along with it and the picture would face the wrong way.
        assertPoint(doubleArrayOf(0.0, 0.0, 1.0), t.transformVector(0.0, 0.0, 1.0))
        assertFalse(t.isIdentity)
    }

    @Test
    fun yawRotationFollowsTheRightHandRuleAndKeepsY() {
        // +90 degrees about Y sends +X to -Z and -Z to -X (right-handed, matching Minecraft's yaw).
        val t = AffineTransform.ofYawAndTranslation(90.0, 0.0, 0.0, 0.0)
        assertPoint(doubleArrayOf(0.0, 0.0, -1.0), t.transformPoint(1.0, 0.0, 0.0), 1.0e-12)
        assertPoint(doubleArrayOf(0.0, 5.0, 0.0), t.transformPoint(0.0, 5.0, 0.0), 1.0e-12)
        // 360 degrees is the identity again.
        assertTrue(AffineTransform.ofYawAndTranslation(360.0, 0.0, 0.0, 0.0).isIdentity)
    }

    @Test
    fun rotationAndTranslationComposeInTheDocumentedOrder() {
        // Rotate the point first, then move it: ofYawAndTranslation's translation is applied last.
        val t = AffineTransform.ofYawAndTranslation(90.0, 10.0, 0.0, 0.0)
        assertPoint(doubleArrayOf(10.0, 0.0, -1.0), t.transformPoint(1.0, 0.0, 0.0), 1.0e-12)
    }

    @Test
    fun transformDirectionNormalisesAndDegradesSafely() {
        val scaled = AffineTransform(
            doubleArrayOf(
                3.0, 0.0, 0.0, 0.0,
                0.0, 3.0, 0.0, 0.0,
                0.0, 0.0, 3.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )
        val unit = scaled.transformDirection(0.0, 1.0, 0.0)
        assertClose(1.0, unit[1], 1.0e-12, "a direction stays unit length under scale")
        // The unnormalised variant keeps the scale factor, which the renderer needs for quad sizing.
        assertClose(3.0, scaled.transformVector(0.0, 1.0, 0.0)[1], 1.0e-12, "vector keeps scale")
    }

    @Test
    fun aCollapsedTransformCannotProduceNaN() {
        val collapsed = AffineTransform(
            doubleArrayOf(
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )
        val direction = collapsed.transformDirection(0.0, 1.0, 0.0)
        assertPoint(doubleArrayOf(0.0, 1.0, 0.0), direction, 0.0)
        assertTrue(direction.all { it.isFinite() }, "a zero-length result must fall back, never NaN")
    }

    @Test
    fun nonFiniteTransformsAreRejected() {
        val broken = AffineTransform(
            doubleArrayOf(
                Double.NaN, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )
        assertFalse(broken.isFinite)
        // A physics blow-up must not move the display at all.
        val pose = DisplayPose(1.0, 2.0, 3.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        assertEquals(pose, ShipPoseMath.toWorld(pose, broken))
        assertPoint(doubleArrayOf(4.0, 5.0, 6.0), ShipPoseMath.pointToWorld(4.0, 5.0, 6.0, broken))
    }

    @Test
    fun matrixProductAppliesTheRightHandSideFirst() {
        val translate = AffineTransform.ofTranslation(10.0, 0.0, 0.0)
        val rotate = AffineTransform.ofYawAndTranslation(90.0, 0.0, 0.0, 0.0)
        // (translate * rotate)(p) == translate(rotate(p))
        val composed = translate.times(rotate)
        assertPoint(rotate.transformPoint(1.0, 0.0, 0.0).let { translate.transformPoint(it[0], it[1], it[2]) },
            composed.transformPoint(1.0, 0.0, 0.0), 1.0e-12)
        assertPoint(doubleArrayOf(10.0, 0.0, -1.0), composed.transformPoint(1.0, 0.0, 0.0), 1.0e-12)
    }

    @Test
    fun equalityUsesToleranceAndRejectsNaNAndForeignTypes() {
        val a = AffineTransform.ofTranslation(1.0, 2.0, 3.0)
        val b = AffineTransform.ofTranslation(1.0 + 1.0e-12, 2.0, 3.0)
        assertEquals(a, b, "drift below epsilon is equal")
        assertEquals(a.hashCode(), a.copy().hashCode(), "same components hash the same")
        assertFalse(a.equals(AffineTransform.ofTranslation(1.001, 2.0, 3.0)))
        assertFalse(a.equals("not a transform"))
        val nan = AffineTransform(
            doubleArrayOf(
                Double.NaN, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )
        // Reflexivity is part of the equals contract, so a NaN instance still equals itself...
        assertEquals(nan, nan, "a value equals itself even when it carries a NaN")
        // ...but two distinct NaN-carrying instances must not: a broken transform comparing equal to
        // another broken transform would let one corrupt a set or a cache key.
        assertFalse(nan == nan.copy(), "two distinct NaN transforms are not equal")
    }

    @Test
    fun aTransformMustHaveSixteenComponents() {
        val failure = runCatching { AffineTransform(DoubleArray(9)) }
        assertTrue(failure.isFailure, "a short matrix must be rejected at construction")
    }

    @Test
    fun poseCarriesCentreAndBasisIntoWorldSpace() {
        val local = DisplayPose(
            centerX = 1.0, centerY = 2.0, centerZ = 0.5,
            normalX = 0.0, normalY = 0.0, normalZ = -1.0,
            uAxisX = 1.0, uAxisY = 0.0, uAxisZ = 0.0,
            vAxisX = 0.0, vAxisY = 1.0, vAxisZ = 0.0,
        )
        // 90 degrees yaw + a lift: verification of the arithmetic, done by hand.
        // A +90 degree rotation about Y maps (x, y, z) -> (z, y, -x), so the local centre
        // (1, 2, 0.5) becomes (0.5, 2, -1); adding the translation (100, 10, -3) gives
        // (100.5, 12, -4). The -Z normal (0, 0, -1) maps to (-1, 0, 0).
        val world = ShipPoseMath.toWorld(local, AffineTransform.ofYawAndTranslation(90.0, 100.0, 10.0, -3.0))
        assertClose(100.5, world.centerX, 1.0e-9)
        assertClose(12.0, world.centerY, 1.0e-9)
        assertClose(-4.0, world.centerZ, 1.0e-9)
        assertClose(-1.0, world.normalX, 1.0e-9, "the -Z normal rotates onto -X")
        assertClose(0.0, world.normalZ, 1.0e-9)
        assertClose(1.0, world.vAxisY, 1.0e-9, "the up axis is unchanged by a yaw")
    }

    @Test
    fun anIdentityTransformLeavesTheLocalPoseUntouched() {
        val local = DisplayPose(1.0, 2.0, 3.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        assertEquals(local, ShipPoseMath.toWorld(local, AffineTransform.IDENTITY))
    }

    @Test
    fun radiusCheckUsesTheWorldPoseNotTheStructureGrid() {
        val pose = DisplayPose(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        assertTrue(ShipPoseMath.isWithinRadius(pose, 3.0, 0.0, 0.0, 5.0))
        assertFalse(ShipPoseMath.isWithinRadius(pose, 6.0, 0.0, 0.0, 5.0))
        assertFalse(ShipPoseMath.isWithinRadius(pose, 0.0, 0.0, 0.0, 0.0), "a zero radius hears nothing")
        assertFalse(ShipPoseMath.isWithinRadius(pose, 0.0, 0.0, 0.0, Double.NaN), "a NaN radius hears nothing")
    }

    @Test
    fun halfExtentsGrowWithStructureScale() {
        val scaled = AffineTransform(
            doubleArrayOf(
                2.0, 0.0, 0.0, 0.0,
                0.0, 2.0, 0.0, 0.0,
                0.0, 0.0, 2.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )
        val extents = ShipPoseMath.halfExtents(
            scaled,
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            0.5, 0.25,
        )
        assertEquals(6, extents.size, "width in 0..2, height in 3..5")
        assertClose(1.0, extents[0], 1.0e-12, "half-width 0.5 doubled")
        assertEquals(0.0, extents[1])
        assertClose(0.5, extents[4], 1.0e-12, "half-height 0.25 doubled")
    }

    @Test
    fun normalisedAxesStayUnitLengthAfterATransform() {
        val world = ShipPoseMath.toWorld(
            DisplayPose(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            AffineTransform.ofYawAndTranslation(37.0, 5.0, 0.0, 5.0),
        )
        val length = sqrt(world.normalX * world.normalX + world.normalY * world.normalY + world.normalZ * world.normalZ)
        assertClose(1.0, length, 1.0e-12, "the world normal must stay unit length")
    }
}