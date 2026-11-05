package com.dreamdisplayx.api.display.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the discovery scheduling that lets a display find its own structure when the server never did.
 *
 * The bug this covers: `/display create` samples the hull once, before the blocks are assembled, so
 * the row keeps `shipId = 0`; `ShipAnchorPolicy.transformFor` then short-circuits on a world anchor
 * and the picture stays at its stored coordinates forever. A wrong answer here is invisible in a unit
 * test but very visible in game — either the display never probes (stays behind) or it probes every
 * frame (render-thread stutter).
 */
class ShipAnchorDetectPolicyTest {
    @Test
    fun aServerAnchorIsNeverSecondGuessed() {
        // Once the server stamped an id, discovery has nothing left to add and must not run at all:
        // the client cannot know better than the authoritative side, and a probe would only be able
        // to disagree with it.
        assertFalse(ShipAnchorDetectPolicy.shouldProbe(7L, 0L, ShipAnchorDetectPolicy.NEVER_PROBED, 0L))
        assertFalse(ShipAnchorDetectPolicy.shouldProbe(7L, 9L, 100L, 10_000L))
        assertEquals(7L, ShipAnchorDetectPolicy.effectiveShipId(7L, 9L))
    }

    @Test
    fun anUnprobedDisplayProbesImmediately() {
        assertTrue(ShipAnchorDetectPolicy.shouldProbe(0L, 0L, ShipAnchorDetectPolicy.NEVER_PROBED, 0L))
        assertTrue(ShipAnchorDetectPolicy.shouldProbe(0L, 0L, ShipAnchorDetectPolicy.NEVER_PROBED, 12345L))
    }

    @Test
    fun aMissIsRetriedAfterTheShortIntervalAndNotBefore() {
        val tick = 1000L
        assertFalse(ShipAnchorDetectPolicy.shouldProbe(0L, 0L, tick, tick))
        assertFalse(
            ShipAnchorDetectPolicy.shouldProbe(0L, 0L, tick, tick + ShipAnchorDetectPolicy.MISS_RETRY_TICKS - 1),
        )
        assertTrue(
            ShipAnchorDetectPolicy.shouldProbe(0L, 0L, tick, tick + ShipAnchorDetectPolicy.MISS_RETRY_TICKS),
        )
    }

    @Test
    fun aHitIsRecheckedOnTheSlowCadenceOnly() {
        val tick = 1000L
        val detected = 42L
        // The short miss interval must not apply here, or a boarding display would re-scan ten times a
        // second for the rest of the session.
        assertFalse(
            ShipAnchorDetectPolicy.shouldProbe(0L, detected, tick, tick + ShipAnchorDetectPolicy.MISS_RETRY_TICKS),
        )
        assertTrue(
            ShipAnchorDetectPolicy.shouldProbe(0L, detected, tick, tick + ShipAnchorDetectPolicy.HIT_RECHECK_TICKS),
        )
    }

    @Test
    fun theSlowCadenceIsLongerThanTheFastOne() {
        // Guards against a future edit that swaps the two constants, which would re-scan every loaded
        // hull ten times a second while a display sits on a moving ship.
        assertTrue(ShipAnchorDetectPolicy.HIT_RECHECK_TICKS > ShipAnchorDetectPolicy.MISS_RETRY_TICKS)
    }

    @Test
    fun aDetectedAnchorIsUsedWhenTheServerHasNone() {
        assertEquals(42L, ShipAnchorDetectPolicy.effectiveShipId(0L, 42L))
        assertEquals(0L, ShipAnchorDetectPolicy.effectiveShipId(0L, 0L))
    }

    @Test
    fun aCorruptDetectedIdDegradesToWorldAnchored() {
        // Matches ShipAnchor.of: a negative id must never reach the matrix source.
        assertEquals(0L, ShipAnchorDetectPolicy.effectiveShipId(0L, -5L))
        assertEquals(0L, ShipAnchorDetectPolicy.effectiveShipId(-1L, -5L))
    }

    @Test
    fun aNegativeStoredIdDoesNotSuppressDiscovery() {
        // A corrupt row (negative id) is world-anchored as far as ShipAnchorPolicy is concerned, so
        // discovery should still be allowed to find the structure it really stands on.
        assertTrue(
            ShipAnchorDetectPolicy.shouldProbe(-1L, 0L, ShipAnchorDetectPolicy.NEVER_PROBED, 0L),
        )
        assertEquals(42L, ShipAnchorDetectPolicy.effectiveShipId(-1L, 42L))
    }

    @Test
    fun discoveryAgreesWithTheRenderPolicy() {
        // The two objects must not disagree about what "anchored" means: `effectiveShipId` feeding a
        // value to `transformFor` that it treats as world-anchored would silently drop the transform.
        val effective = ShipAnchorDetectPolicy.effectiveShipId(0L, 42L)
        assertTrue(ShipAnchorPolicy.isShipAnchored(effective))
        val worldOnly = ShipAnchorDetectPolicy.effectiveShipId(0L, 0L)
        assertFalse(ShipAnchorPolicy.isShipAnchored(worldOnly))
    }
}