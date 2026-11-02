package com.dreamdisplayx.api.display.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the fallback behaviour of the ship-transform resolution.
 *
 * The interesting cases are all the ways a transform can be *unusable*: the renderer must never see a
 * NaN or a collapsed matrix, because `ScreenRenderer` would then push garbage into the pose stack and
 * blank the screen for every player near it.
 */
class ShipAnchorPolicyTest {
    private fun identityMatrix() = AffineTransform.IDENTITY.m.copyOf()

    @Test
    fun worldAnchoredDisplayNeverQueriesTheSource() {
        var asked = 0
        val source = ShipTransformSource { asked++; identityMatrix() }
        assertEquals(AffineTransform.IDENTITY, ShipAnchorPolicy.transformFor(0L, source))
        assertEquals(0, asked, "shipId 0 must not reach the physics mod at all")
    }

    @Test
    fun negativeShipIdIsTreatedAsWorldAnchored() {
        var asked = 0
        val source = ShipTransformSource { asked++; identityMatrix() }
        assertEquals(AffineTransform.IDENTITY, ShipAnchorPolicy.transformFor(-42L, source))
        assertEquals(0, asked)
    }

    @Test
    fun missingStructureFallsBackToIdentity() {
        assertEquals(
            AffineTransform.IDENTITY,
            ShipAnchorPolicy.transformFor(7L, ShipTransformSource.NONE),
        )
    }

    @Test
    fun liveMatrixBecomesTheTransform() {
        val matrix = AffineTransform.ofYawAndTranslation(90.0, 100.0, 10.0, -3.0).m
        val resolved = ShipAnchorPolicy.transformFor(7L, ShipTransformSource { matrix })
        // The structure-space origin maps to the structure's world position.
        val origin = resolved.transformPoint(0.0, 0.0, 0.0)
        assertEquals(100.0, origin[0], 1e-9)
        assertEquals(10.0, origin[1], 1e-9)
        assertEquals(-3.0, origin[2], 1e-9)
    }

    @Test
    fun throwingSourceFallsBackToIdentity() {
        val source = ShipTransformSource { error("physics thread died") }
        assertEquals(AffineTransform.IDENTITY, ShipAnchorPolicy.transformFor(7L, source))
    }

    @Test
    fun wrongSizedMatrixIsRejected() {
        assertEquals(AffineTransform.IDENTITY, ShipAnchorPolicy.matrixToTransform(doubleArrayOf(1.0, 0.0, 0.0)))
        assertEquals(AffineTransform.IDENTITY, ShipAnchorPolicy.matrixToTransform(DoubleArray(0)))
        assertEquals(AffineTransform.IDENTITY, ShipAnchorPolicy.matrixToTransform(DoubleArray(17)))
    }

    @Test
    fun nonFiniteMatrixIsRejected() {
        val nan = identityMatrix().also { it[12] = Double.NaN }
        assertEquals(AffineTransform.IDENTITY, ShipAnchorPolicy.matrixToTransform(nan))
        val infinite = identityMatrix().also { it[5] = Double.POSITIVE_INFINITY }
        assertEquals(AffineTransform.IDENTITY, ShipAnchorPolicy.matrixToTransform(infinite))
    }

    @Test
    fun validMatrixIsAcceptedAndCopied() {
        val input = identityMatrix().also { it[12] = 5.0 }
        val transform = ShipAnchorPolicy.matrixToTransform(input)
        assertEquals(5.0, transform.at(3, 0), 1e-9)
        // Mutating the caller-held array must not reach the transform.
        input[12] = 99.0
        assertEquals(5.0, transform.at(3, 0), 1e-9)
    }

    @Test
    fun zeroMatrixIsNotSilentlyIgnored() {
        // A collapsed matrix is finite, so it is accepted here; the renderer's own degenerate handling
        // (transformDirection returning the input) is what prevents NaNs. This case documents that the
        // policy does not pretend a zero matrix is identity, or a vanished ship would look alive.
        val zero = ShipAnchorPolicy.matrixToTransform(DoubleArray(16))
        assertFalse(zero.isIdentity)
    }

    @Test
    fun anchorIsDroppedOnlyForAStructureThatIsGone() {
        assertTrue(ShipAnchorPolicy.shouldDropAnchor(7L, shipStillExists = false))
        assertFalse(ShipAnchorPolicy.shouldDropAnchor(7L, shipStillExists = true))
        // Rows written before the feature existed must survive on a server without the physics mod.
        assertFalse(ShipAnchorPolicy.shouldDropAnchor(0L, shipStillExists = false))
        assertFalse(ShipAnchorPolicy.shouldDropAnchor(0L, shipStillExists = true))
    }

    @Test
    fun sourceNoneKnowsNothing() {
        assertEquals(null, ShipTransformSource.NONE.matrixOf(1L))
        assertEquals(null, ShipTransformSource.NONE.matrixOf(0L))
    }
}