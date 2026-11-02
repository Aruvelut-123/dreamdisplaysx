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
 * Pins [AffineDecomposition], the pure-math half of ship rendering.
 *
 * The renderer applies a ship matrix as `translate` + quaternion rotation + `scale`, because the
 * pose-stack API differs between the eleven versions this fork builds. That decomposition is the
 * only part of ship rendering with no Minecraft dependency, so it is the only part CI can verify —
 * everything downstream of it needs a live GL context and a physics mod.
 */
class AffineDecompositionTest {

    private fun assertClose(expected: Double, actual: Double, message: String) {
        assertTrue(abs(expected - actual) <= 1.0e-9, "$message expected=$expected actual=$actual")
    }

    /** Rebuilds `T * R * S` from [AffineDecomposition.Parts] and checks it reproduces [original]. */
    private fun assertRoundTrip(original: AffineTransform) {
        val parts = assertNotNull(AffineDecomposition.of(original), "expected a decomposable matrix")
        val (x, y, z, w) = listOf(
            parts.quaternionX, parts.quaternionY, parts.quaternionZ, parts.quaternionW,
        )
        // Rotation matrix of a unit quaternion, row-major.
        val r00 = 1.0 - 2.0 * (y * y + z * z)
        val r01 = 2.0 * (x * y - z * w)
        val r02 = 2.0 * (x * z + y * w)
        val r10 = 2.0 * (x * y + z * w)
        val r11 = 1.0 - 2.0 * (x * x + z * z)
        val r12 = 2.0 * (y * z - x * w)
        val r20 = 2.0 * (x * z - y * w)
        val r21 = 2.0 * (y * z + x * w)
        val r22 = 1.0 - 2.0 * (x * x + y * y)
        // Columns scale by the extracted scale factors, then the translation lands in column 3.
        val rebuilt = AffineTransform(
            doubleArrayOf(
                r00 * parts.scaleX, r10 * parts.scaleX, r20 * parts.scaleX, 0.0,
                r01 * parts.scaleY, r11 * parts.scaleY, r21 * parts.scaleY, 0.0,
                r02 * parts.scaleZ, r12 * parts.scaleZ, r22 * parts.scaleZ, 0.0,
                parts.translationX, parts.translationY, parts.translationZ, 1.0,
            ),
        )
        for (i in 0 until 16) {
            assertClose(original.m[i], rebuilt.m[i], "component $i")
        }
    }

    @Test
    fun `identity decomposes to no rotation and unit scale`() {
        val parts = assertNotNull(AffineDecomposition.of(AffineTransform.IDENTITY))
        assertClose(0.0, parts.translationX, "tx")
        assertClose(0.0, parts.translationY, "ty")
        assertClose(0.0, parts.translationZ, "tz")
        assertClose(1.0, parts.scaleX, "sx")
        assertClose(1.0, parts.scaleY, "sy")
        assertClose(1.0, parts.scaleZ, "sz")
        // w = +-1 is a valid identity quaternion; the extraction picks the positive one.
        assertClose(1.0, parts.quaternionW, "qw")
    }

    @Test
    fun `a pure translation keeps the rotation identity`() {
        val parts = assertNotNull(AffineDecomposition.of(AffineTransform.ofTranslation(12.0, 64.0, -30.0)))
        assertClose(12.0, parts.translationX, "tx")
        assertClose(64.0, parts.translationY, "ty")
        assertClose(-30.0, parts.translationZ, "tz")
        assertClose(0.0, parts.quaternionX, "qx")
        assertClose(0.0, parts.quaternionZ, "qz")
        assertRoundTrip(AffineTransform.ofTranslation(12.0, 64.0, -30.0))
    }

    @Test
    fun `a yaw rotation decomposes to the same yaw`() {
        val yaw = 37.0
        val transform = AffineTransform.ofYawAndTranslation(yaw, 1.5, 2.5, -3.5)
        val parts = assertNotNull(AffineDecomposition.of(transform))
        // A yaw of theta is the quaternion (0, sin(theta/2), 0, cos(theta/2)).
        val radians = Math.toRadians(yaw)
        assertClose(0.0, parts.quaternionX, "qx")
        assertClose(sin(radians / 2.0), parts.quaternionY, "qy")
        assertClose(0.0, parts.quaternionZ, "qz")
        assertClose(cos(radians / 2.0), parts.quaternionW, "qw")
        assertRoundTrip(transform)
    }

    @Test
    fun `a 180 degree turn does not collapse the quaternion`() {
        // The naive trace formula divides by a vanishing denominator here and yields (0, 0, 0, 0),
        // which would silently drop the rotation and leave the display mirrored on the hull.
        val transform = AffineTransform.ofYawAndTranslation(180.0, 0.0, 0.0, 0.0)
        val parts = assertNotNull(AffineDecomposition.of(transform))
        val norm = parts.quaternionX * parts.quaternionX + parts.quaternionY * parts.quaternionY +
            parts.quaternionZ * parts.quaternionZ + parts.quaternionW * parts.quaternionW
        assertClose(1.0, norm, "quaternion norm")
        assertRoundTrip(transform)
    }

    @Test
    fun `a uniform scale is extracted and reapplied`() {
        val base = AffineTransform.ofYawAndTranslation(25.0, 4.0, 5.0, 6.0)
        val scaled = AffineTransform(
            doubleArrayOf(
                base.m[0] * 2.0, base.m[1] * 2.0, base.m[2] * 2.0, 0.0,
                base.m[4] * 2.0, base.m[5] * 2.0, base.m[6] * 2.0, 0.0,
                base.m[8] * 2.0, base.m[9] * 2.0, base.m[10] * 2.0, 0.0,
                base.m[12], base.m[13], base.m[14], 1.0,
            ),
        )
        val parts = assertNotNull(AffineDecomposition.of(scaled))
        assertClose(2.0, parts.scaleX, "sx")
        assertClose(2.0, parts.scaleY, "sy")
        assertClose(2.0, parts.scaleZ, "sz")
        assertRoundTrip(scaled)
    }

    @Test
    fun `a collapsed basis is rejected instead of producing a mirrored quad`() {
        val collapsed = AffineTransform(
            doubleArrayOf(
                0.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )
        assertNull(AffineDecomposition.of(collapsed), "a zero-length axis must not reach the pose stack")
    }

    @Test
    fun `a non finite matrix is rejected`() {
        val broken = AffineTransform(
            doubleArrayOf(
                Double.NaN, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )
        assertNull(AffineDecomposition.of(broken), "a NaN matrix must never blank every display")
    }

    @Test
    fun `the identity of a missing ship is not applied`() {
        // `ShipAnchorPolicy.transformFor` returns identity for an unresolvable ship; the renderer
        // must be able to tell that apart cheaply and skip the extra pose work entirely.
        val transform = ShipAnchorPolicy.transformFor(7L, ShipTransformSource.NONE)
        assertTrue(transform.isIdentity, "an unknown ship must fall back to identity")
        val parts = assertNotNull(AffineDecomposition.of(transform))
        assertClose(1.0, parts.scaleX, "sx")
        assertEquals(0.0, parts.translationX, "tx")
    }
}