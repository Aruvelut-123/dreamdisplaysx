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
    fun aHitStopsProbingForGood() {
        val tick = 1000L
        val detected = 42L
        // A hit is permanent, not a slow-cadence recheck. The correction captured at discovery keeps
        // `shipToWorld_now * worldToShip_then` right for every later matrix, while the world-space
        // lookups that produced the hit stop applying once VS moves the blocks into the shipyard —
        // so every recheck would log a spurious miss on a display that is being rendered correctly.
        assertFalse(ShipAnchorDetectPolicy.shouldProbe(0L, detected, tick, tick))
        assertFalse(
            ShipAnchorDetectPolicy.shouldProbe(0L, detected, tick, tick + ShipAnchorDetectPolicy.MISS_RETRY_TICKS),
        )
        assertFalse(
            ShipAnchorDetectPolicy.shouldProbe(0L, detected, tick, tick + 1_000_000L),
        )
    }

    @Test
    fun theMissRetryIsSlowEnoughNotToStutterTheFrameItRunsOn() {
        // Guards the render budget: one probe walks every loaded hull, so retrying it every tick
        // would stutter the frame that runs it.
        assertTrue(ShipAnchorDetectPolicy.MISS_RETRY_TICKS >= 5L)
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

    @Test
    fun aRepeatedBroadcastIsNotTreatedAsAMove() {
        // The server rebroadcasts every in-range display once a second. Resetting the discovered hull on
        // those packets made the client forget a ship it had just found, so it rediscovered it, lost it
        // again on the next tick, and the picture flickered between the hull and its old world position.
        // Identical coordinates and an unchanged server id must therefore read as "nothing moved".
        assertFalse(
            ShipAnchorDetectPolicy.anchorMoved(
                oldX = 0, oldY = -63, oldZ = 12, oldShipId = 0L,
                newX = 0, newY = -63, newZ = 12, newShipId = 0L,
            ),
        )
    }

    @Test
    fun aRealMoveStillInvalidatesTheDiscovery() {
        // The other half of the contract: keeping a stale hull after the anchor genuinely moved would
        // draw the picture against whatever used to be at those coordinates.
        assertTrue(
            ShipAnchorDetectPolicy.anchorMoved(
                oldX = 0, oldY = -63, oldZ = 12, oldShipId = 0L,
                newX = 1, newY = -63, newZ = 12, newShipId = 0L,
            ),
        )
        assertTrue(
            ShipAnchorDetectPolicy.anchorMoved(
                oldX = 0, oldY = -63, oldZ = 12, oldShipId = 0L,
                newX = 0, newY = -62, newZ = 12, newShipId = 0L,
            ),
        )
        assertTrue(
            ShipAnchorDetectPolicy.anchorMoved(
                oldX = 0, oldY = -63, oldZ = 12, oldShipId = 0L,
                newX = 0, newY = -63, newZ = 13, newShipId = 0L,
            ),
        )
    }

    @Test
    fun aServerRestampAlsoInvalidatesTheDiscovery() {
        // A row the server stamped later (or re-stamped to 0) describes a different anchor even when the
        // coordinates are untouched, because the authoritative id wins over the client's guess.
        assertTrue(
            ShipAnchorDetectPolicy.anchorMoved(
                oldX = 0, oldY = -63, oldZ = 12, oldShipId = 0L,
                newX = 0, newY = -63, newZ = 12, newShipId = 27L,
            ),
        )
        assertTrue(
            ShipAnchorDetectPolicy.anchorMoved(
                oldX = 0, oldY = -63, oldZ = 12, oldShipId = 27L,
                newX = 0, newY = -63, newZ = 12, newShipId = 0L,
            ),
        )
    }
}