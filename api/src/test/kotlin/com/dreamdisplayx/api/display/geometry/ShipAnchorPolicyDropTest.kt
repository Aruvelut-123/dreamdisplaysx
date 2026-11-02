package com.dreamdisplayx.api.display.geometry

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the two predicates that decide what happens to a ship-anchored display's row.
 *
 * Both are pure arithmetic on a stored id, which is exactly why they live in `:api`: the server code
 * that consumes them needs a Minecraft world to run, so this is the only place they can be tested.
 */
class ShipAnchorPolicyDropTest {

    @Test
    fun `a ship anchored row is recognisable by a positive id`() {
        assertTrue(ShipAnchorPolicy.isShipAnchored(1L))
        assertTrue(ShipAnchorPolicy.isShipAnchored(Long.MAX_VALUE))
    }

    @Test
    fun `a world anchored row is not ship anchored`() {
        assertFalse(ShipAnchorPolicy.isShipAnchored(0L), "0 is the world-anchor sentinel")
        assertFalse(ShipAnchorPolicy.isShipAnchored(-1L), "a corrupt negative id must not look like a ship")
    }

    @Test
    fun `a world anchored row is never dropped even when no ship exists`() {
        assertFalse(
            ShipAnchorPolicy.shouldDropAnchor(storedShipId = 0L, shipStillExists = false),
            "a server without the physics mod must keep every existing display",
        )
    }

    @Test
    fun `a ship anchored row is dropped only once the ship is gone`() {
        assertTrue(ShipAnchorPolicy.shouldDropAnchor(storedShipId = 42L, shipStillExists = false))
        assertFalse(
            ShipAnchorPolicy.shouldDropAnchor(storedShipId = 42L, shipStillExists = true),
            "a loaded ship must keep its display",
        )
    }

    @Test
    fun `dropping and anchoring agree for the same id`() {
        // An id that can be dropped is also one whose coordinates live in structure space; if these
        // ever disagree, a row could be skipped by the world census yet never be reaped either.
        for (id in listOf(-5L, 0L, 1L, 7L, Long.MAX_VALUE)) {
            val droppable = ShipAnchorPolicy.shouldDropAnchor(id, shipStillExists = false)
            val shipAnchored = ShipAnchorPolicy.isShipAnchored(id)
            assertTrue(
                droppable == shipAnchored,
                "id=$id droppable=$droppable shipAnchored=$shipAnchored",
            )
        }
    }
}
