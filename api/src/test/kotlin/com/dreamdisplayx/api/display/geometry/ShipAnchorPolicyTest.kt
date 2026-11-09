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
    fun levelFacadeCandidatesKeepThePlainLevelFallbackLast() {
        val candidates = ShipAnchorPolicy.levelFacadeCandidates()
        // The plain `Level` spelling is the one VS actually declares, so it must be reachable; the
        // subtype spellings ahead of it are future-proofing only. Losing it is what silently turned
        // the whole ship bridge inert (`getMethod` matches declared parameter types exactly).
        assertEquals("Level", candidates.last(), "the Level fallback must be tried last, but present")
        assertTrue(candidates.contains("ServerLevel"), "the server bridge needs the ServerLevel spelling")
        assertTrue(candidates.contains("ClientLevel"), "the client bridge needs the ClientLevel spelling")
        assertEquals(candidates.size, candidates.distinct().size, "candidates must not repeat")
    }

    @Test
    fun levelFacadeCandidatesAreNeverEmpty() {
        // An empty list would make both bridges throw NoSuchMethodException unconditionally, which is
        // indistinguishable from "the mod is absent" — the exact failure mode this policy prevents.
        assertFalse(ShipAnchorPolicy.levelFacadeCandidates().isEmpty())
    }

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

    // ---- composeCorrection: the discovery-time correction, order included -------------------------

    private fun translationMatrix(x: Double, y: Double, z: Double) = AffineTransform.ofTranslation(x, y, z)

    @Test
    fun theCorrectionIsAppliedBeforeTheLiveTransform() {
        // `live * correction` means the correction runs first, which is what the renderer needs because
        // it multiplies `structureToWorld * storedPos` and the stored position is world space. Swapping
        // the order sends the picture around the camera instead of around the structure's origin, so
        // the composition is pinned here rather than left to the call site.
        val live = translationMatrix(100.0, 0.0, 0.0)
        val correction = translationMatrix(-10.0, 0.0, 0.0).m

        val composed = ShipAnchorPolicy.composeCorrection(live, correction)
        val point = composed.transformPoint(10.0, 0.0, 0.0)

        // correction first: 10 + (-10) = 0, then the live transform: 0 + 100 = 100 (the structure).
        assertEquals(100.0, point[0], 1e-9)
        // If the order had been reversed the point would land at 10 + 100 + (-10) = 100 as well for a
        // pure translation, so a rotation is what actually distinguishes the two. Assert the identity
        // composition of a matched pair instead, which is the "the picture must not jump" property.
        val liveAgain = translationMatrix(10.0, 0.0, 0.0)
        val asDiscovery = translationMatrix(-10.0, 0.0, 0.0).m
        val atDiscovery = ShipAnchorPolicy.composeCorrection(liveAgain, asDiscovery)
        val unchanged = atDiscovery.transformPoint(3.0, 4.0, 5.0)
        assertEquals(3.0, unchanged[0], 1e-9)
        assertEquals(4.0, unchanged[1], 1e-9)
        assertEquals(5.0, unchanged[2], 1e-9)
    }

    @Test
    fun aRotationCorrectionRotatesAroundTheStructureOriginNotTheCamera() {
        // The order-sensitive case: a yaw of 90 degrees. Applied first, the point is rotated and then
        // pushed by the live translation; the wrong order would rotate the already-translated point.
        val live = translationMatrix(0.0, 0.0, 50.0)
        val yaw = AffineTransform.ofYawAndTranslation(90.0, 0.0, 0.0, 0.0).m

        val composed = ShipAnchorPolicy.composeCorrection(live, yaw)
        val point = composed.transformPoint(1.0, 0.0, 0.0)

        // 90 degrees around Y maps +X onto -Z, then the live transform adds +50 on Z.
        assertEquals(0.0, point[0], 1e-9)
        assertEquals(0.0, point[1], 1e-9)
        assertEquals(49.0, point[2], 1e-9)
        // The mirror case: with the order swapped the translation is applied first, so the yaw moves the
        // already-offset point instead of rotating around the structure origin. The two results
        // ((0, 0, 49) above versus (50, 0, -1) here) are what makes this test order-sensitive.
        val swapped = ShipAnchorPolicy.composeCorrection(AffineTransform(yaw), live.m)
            .transformPoint(1.0, 0.0, 0.0)
        assertEquals(50.0, swapped[0], 1e-9)
        assertEquals(-1.0, swapped[2], 1e-9)
    }

    @Test
    fun anAbsentCorrectionLeavesTheLiveTransformUntouched() {
        val live = translationMatrix(7.0, 8.0, 9.0)
        assertEquals(live, ShipAnchorPolicy.composeCorrection(live, null))
    }

    @Test
    fun aCorrectionCannotMoveADisplayWhoseStructureIsNotResolvable() {
        // `live` identity means the structure is not loaded; composing a correction there would shift a
        // display that must stay exactly where it is.
        val correction = translationMatrix(1000.0, 0.0, 0.0).m
        assertEquals(AffineTransform.IDENTITY, ShipAnchorPolicy.composeCorrection(AffineTransform.IDENTITY, correction))
    }

    @Test
    fun aBrokenOrWrongSizedCorrectionIsIgnored() {
        val live = translationMatrix(1.0, 2.0, 3.0)
        assertEquals(live, ShipAnchorPolicy.composeCorrection(live, DoubleArray(3)))
        val nan = AffineTransform.IDENTITY.m.copyOf().also { it[0] = Double.NaN }
        assertEquals(live, ShipAnchorPolicy.composeCorrection(live, nan))
    }
}