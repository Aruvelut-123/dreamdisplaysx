package com.dreamdisplayx.api.display.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [SubLevelPose], the only part of the Sable integration a CI job can actually execute.
 *
 * Sable's pose is `T(position) * R(quaternion) * S(scale) * T(-rotationPoint)`; the bridge reimplements
 * that composition so the arithmetic stays in the dependency-free `api` module. Everything below is a
 * property a live JOML `Matrix4d` would also satisfy, so a regression shows up here instead of as a
 * display floating in the wrong place on someone's ship.
 */
class SubLevelPoseTest {

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1.0e-9, message: String = "") {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$message expected=$expected actual=$actual (tolerance $tolerance)",
        )
    }

    private fun assertIdentityPose(matrix: DoubleArray) {
        val identity = AffineTransform.IDENTITY.m
        for (i in identity.indices) assertClose(identity[i], matrix[i], message = "component $i")
    }

    private fun assertMatrixEquals(expected: DoubleArray, actual: DoubleArray) {
        assertEquals(expected.size, actual.size, "matrix size")
        for (i in expected.indices) assertClose(expected[i], actual[i], message = "component $i")
    }

    /** A unit quaternion for a rotation of [degrees] around the Y axis. */
    private fun yawQuaternion(degrees: Double): DoubleArray {
        val half = Math.toRadians(degrees) / 2.0
        return doubleArrayOf(0.0, sin(half), 0.0, cos(half))
    }

    @Test
    fun `an identity pose produces the identity matrix`() {
        val matrix = SubLevelPose.matrixOf(
            position = doubleArrayOf(0.0, 0.0, 0.0),
            orientation = doubleArrayOf(0.0, 0.0, 0.0, 1.0),
            scale = doubleArrayOf(1.0, 1.0, 1.0),
            rotationPoint = doubleArrayOf(0.0, 0.0, 0.0),
        )
        assertIdentityPose(matrix)
    }

    @Test
    fun `a pure translation lands in the translation column`() {
        val matrix = SubLevelPose.matrixOf(
            position = doubleArrayOf(10.0, -3.5, 7.25),
            orientation = doubleArrayOf(0.0, 0.0, 0.0, 1.0),
            scale = doubleArrayOf(1.0, 1.0, 1.0),
            rotationPoint = doubleArrayOf(0.0, 0.0, 0.0),
        )
        // Column-major: the translation is the fourth column, indices 12..14.
        assertClose(10.0, matrix[12])
        assertClose(-3.5, matrix[13])
        assertClose(7.25, matrix[14])
        assertClose(1.0, matrix[15])
    }

    @Test
    fun `a yaw rotation matches a hand written rotation matrix`() {
        val matrix = SubLevelPose.matrixOf(
            position = doubleArrayOf(0.0, 0.0, 0.0),
            orientation = yawQuaternion(90.0),
            scale = doubleArrayOf(1.0, 1.0, 1.0),
            rotationPoint = doubleArrayOf(0.0, 0.0, 0.0),
        )
        // A 90 degree yaw maps +X onto -Z and +Z onto +X.
        val transform = AffineTransform(matrix)
        val xAxis = transform.transformVector(1.0, 0.0, 0.0)
        assertClose(0.0, xAxis[0], message = "x -> x")
        assertClose(0.0, xAxis[1], message = "x -> y")
        assertClose(-1.0, xAxis[2], message = "x -> z")

        val zAxis = transform.transformVector(0.0, 0.0, 1.0)
        assertClose(1.0, zAxis[0], message = "z -> x")
        assertClose(0.0, zAxis[1], message = "z -> y")
        assertClose(0.0, zAxis[2], message = "z -> z")
    }

    @Test
    fun `scale multiplies each axis without touching the others`() {
        val matrix = SubLevelPose.matrixOf(
            position = doubleArrayOf(0.0, 0.0, 0.0),
            orientation = doubleArrayOf(0.0, 0.0, 0.0, 1.0),
            scale = doubleArrayOf(2.0, 3.0, 4.0),
            rotationPoint = doubleArrayOf(0.0, 0.0, 0.0),
        )
        val transform = AffineTransform(matrix)
        assertClose(2.0, transform.transformVector(1.0, 0.0, 0.0)[0])
        assertClose(3.0, transform.transformVector(0.0, 1.0, 0.0)[1])
        assertClose(4.0, transform.transformVector(0.0, 0.0, 1.0)[2])
    }

    @Test
    fun `the rotation point is subtracted before rotating and lands on the position`() {
        // Sable's composition is `T(position) * R * S * T(-rotationPoint)`, so `rotationPoint` is a
        // pivot expressed in *structure* space: it is subtracted first, the rotation and scale are
        // applied about the origin, and `position` is added last. The pivot therefore maps exactly
        // onto `position` — it is not a fixed point in world space, which is the intuitive but wrong
        // reading of the name.
        val position = doubleArrayOf(100.0, 64.0, -30.0)
        val pivot = doubleArrayOf(5.0, 0.0, 0.0)
        val matrix = SubLevelPose.matrixOf(
            position = position,
            orientation = yawQuaternion(90.0),
            scale = doubleArrayOf(1.0, 1.0, 1.0),
            rotationPoint = pivot,
        )
        val transform = AffineTransform(matrix)

        val pivotMapped = transform.transformPoint(pivot[0], pivot[1], pivot[2])
        assertClose(position[0], pivotMapped[0], message = "pivot x lands on position")
        assertClose(position[1], pivotMapped[1], message = "pivot y lands on position")
        assertClose(position[2], pivotMapped[2], message = "pivot z lands on position")

        // A point one block along +X from the pivot is a pure offset in the rotation's own space, so
        // a 90 degree yaw carries it to one block along -Z relative to `position`.
        val offsetMapped = transform.transformPoint(pivot[0] + 1.0, pivot[1], pivot[2])
        assertClose(position[0], offsetMapped[0], message = "offset x")
        assertClose(position[1], offsetMapped[1], message = "offset y")
        assertClose(position[2] - 1.0, offsetMapped[2], message = "offset z")
    }

    @Test
    fun `a non unit quaternion is normalised rather than scaling the picture`() {
        val unit = yawQuaternion(37.0)
        val scaled = DoubleArray(4) { unit[it] * 3.0 }
        val fromUnit = SubLevelPose.matrixOf(
            doubleArrayOf(1.0, 2.0, 3.0), unit, doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0),
        )
        val fromScaled = SubLevelPose.matrixOf(
            doubleArrayOf(1.0, 2.0, 3.0), scaled, doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0),
        )
        assertMatrixEquals(fromUnit, fromScaled)
    }

    @Test
    fun `a degenerate quaternion falls back to no rotation instead of NaN`() {
        val matrix = SubLevelPose.matrixOf(
            position = doubleArrayOf(1.0, 1.0, 1.0),
            orientation = doubleArrayOf(0.0, 0.0, 0.0, 0.0),
            scale = doubleArrayOf(1.0, 1.0, 1.0),
            rotationPoint = doubleArrayOf(0.0, 0.0, 0.0),
        )
        assertTrue(matrix.all { it.isFinite() }, "a blank orientation must not produce NaN")
        assertClose(1.0, matrix[0], message = "identity basis x")
        assertClose(1.0, matrix[5], message = "identity basis y")
        assertClose(1.0, matrix[10], message = "identity basis z")
    }

    @Test
    fun `the inverse maps a world point back to where it came from`() {
        val position = doubleArrayOf(30.0, 5.0, -12.0)
        val orientation = yawQuaternion(63.0)
        val scale = doubleArrayOf(1.0, 2.0, 3.0)
        val pivot = doubleArrayOf(4.0, 4.0, 4.0)

        val forward = SubLevelPose.matrixOf(position, orientation, scale, pivot)
        val inverse = SubLevelPose.inverseMatrixOf(position, orientation, scale, pivot)
        assertNotNull(inverse)
        val inverseMatrix = inverse ?: error("inverse must exist for this pose")

        val structurePoint = doubleArrayOf(7.5, 2.0, -3.25)
        val world = AffineTransform(forward).transformPoint(structurePoint[0], structurePoint[1], structurePoint[2])
        val roundTrip = AffineTransform(inverseMatrix).transformPoint(world[0], world[1], world[2])

        assertClose(structurePoint[0], roundTrip[0], tolerance = 1.0e-8, message = "x round trip")
        assertClose(structurePoint[1], roundTrip[1], tolerance = 1.0e-8, message = "y round trip")
        assertClose(structurePoint[2], roundTrip[2], tolerance = 1.0e-8, message = "z round trip")
    }

    @Test
    fun `an inverse round trip survives a non uniform scale and a pivot`() {
        // Same property as above with a nastier pose: the case where a naive `transpose` inverse (which
        // ignores the scale) drifts by whole blocks instead of rounding error.
        val position = doubleArrayOf(-100.0, 64.0, 200.0)
        val orientation = doubleArrayOf(0.2, -0.4, 0.1, 0.88)
        val scale = doubleArrayOf(0.5, 2.5, 1.75)
        val pivot = doubleArrayOf(-8.0, 3.0, 11.0)

        val forward = SubLevelPose.matrixOf(position, orientation, scale, pivot)
        val inverse = SubLevelPose.inverseMatrixOf(position, orientation, scale, pivot)
        assertNotNull(inverse)
        val inverseMatrix = inverse ?: error("inverse must exist for this pose")

        val structurePoint = doubleArrayOf(1.0, -2.0, 3.0)
        val world = AffineTransform(forward).transformPoint(structurePoint[0], structurePoint[1], structurePoint[2])
        val roundTrip = AffineTransform(inverseMatrix).transformPoint(world[0], world[1], world[2])
        for (i in 0..2) {
            assertClose(structurePoint[i], roundTrip[i], tolerance = 1.0e-8, message = "axis $i round trip")
        }
    }

    @Test
    fun `a collapsed scale has no inverse`() {
        assertNull(
            SubLevelPose.inverseMatrixOf(
                doubleArrayOf(0.0, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 1.0),
                doubleArrayOf(1.0, 0.0, 1.0),
                doubleArrayOf(0.0, 0.0, 0.0),
            ),
            "a zero scale flattens the structure, so it must not report an inverse",
        )
    }

    @Test
    fun `a non finite scale has no inverse`() {
        assertNull(
            SubLevelPose.inverseMatrixOf(
                doubleArrayOf(0.0, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 1.0),
                doubleArrayOf(1.0, Double.NaN, 1.0),
                doubleArrayOf(0.0, 0.0, 0.0),
            ),
        )
    }

    @Test
    fun `the composed matrix is accepted by the affine policy`() {
        // The bridge hands `SubLevelPose`'s output straight to `ShipAnchorPolicy.matrixToTransform`, so
        // the two have to agree on size, finiteness and the column-major layout.
        val matrix = SubLevelPose.matrixOf(
            doubleArrayOf(12.0, 70.0, -5.0), yawQuaternion(180.0), doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0),
        )
        assertEquals(SubLevelPose.MATRIX_SIZE, matrix.size)
        val transform = ShipAnchorPolicy.matrixToTransform(matrix)
        assertTrue(!transform.isIdentity, "a translated pose must not collapse to identity")
        val mapped = transform.transformPoint(0.0, 0.0, 0.0)
        assertClose(12.0, mapped[0])
        assertClose(70.0, mapped[1])
        assertClose(-5.0, mapped[2])
    }
}