package com.dreamdisplayx.api.display.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composing a live ship-to-world matrix with a discovery-time world-to-ship correction.
 *
 * The correction exists because a display built on loose blocks and assembled into a hull afterwards
 * keeps the **world** position the player clicked, while the renderer multiplies stored coordinates by
 * the live ship-to-world matrix. The product `shipToWorld_now * worldToShip_then` is what maps such a
 * row correctly, and its defining property is that it is the identity at the moment of discovery: the
 * picture must not jump when it is first pinned to the hull.
 */
class ShipAnchorCorrectionTest {

    @Test
    fun `correction composed with its own inverse is the identity`() {
        val toWorld = AffineTransform.ofYawAndTranslation(37.0, 128.0, 64.0, -256.0)
        val toShip = inverseOf(toWorld)

        val combined = toWorld.times(toShip)

        assertTrue(combined.isIdentity, "shipToWorld * worldToShip must be the identity, was $combined")
    }

    @Test
    fun `a moved hull moves the corrected point by exactly the hull displacement`() {
        // Discovery happened at the origin-aligned pose, the hull then sailed 10 blocks along X.
        val worldToShipAtDiscovery = AffineTransform.ofTranslation(0.0, 0.0, 0.0)
        val shipToWorldNow = AffineTransform.ofTranslation(10.0, 0.0, 0.0)

        val combined = shipToWorldNow.times(worldToShipAtDiscovery)
        val out = combined.transformPoint(5.0, 6.0, 7.0)

        assertEquals(15.0, out[0], 1.0e-9)
        assertEquals(6.0, out[1], 1.0e-9)
        assertEquals(7.0, out[2], 1.0e-9)
    }

    @Test
    fun `the correction is applied before the live matrix not after`() {
        // A rotation then a translation must not commute; the order is what keeps the pivot on the hull.
        val correction = AffineTransform.ofTranslation(0.0, 0.0, 0.0)
        val live = AffineTransform.ofYawAndTranslation(90.0, 4.0, 0.0, 0.0)
        val combined = live.times(correction)

        val out = combined.transformPoint(1.0, 0.0, 0.0)

        // 90 deg yaw maps +X onto -Z, then the translation adds 4 to X.
        assertEquals(4.0, out[0], 1.0e-9)
        assertEquals(0.0, out[1], 1.0e-9)
        assertEquals(-1.0, out[2], 1.0e-9)
    }

    /** Builds the inverse of a rigid transform: invert the basis, then negate the translated offset. */
    private fun inverseOf(t: AffineTransform): AffineTransform {
        // Only used for a yaw+translation matrix, so the 3x3 inverse is its transpose.
        val out = DoubleArray(16)
        for (column in 0..2) for (row in 0..2) out[column * 4 + row] = t.at(row, column)
        val px = t.at(3, 0)
        val py = t.at(3, 1)
        val pz = t.at(3, 2)
        out[12] = -(out[0] * px + out[4] * py + out[8] * pz)
        out[13] = -(out[1] * px + out[5] * py + out[9] * pz)
        out[14] = -(out[2] * px + out[6] * py + out[10] * pz)
        out[15] = 1.0
        return AffineTransform(out)
    }
}