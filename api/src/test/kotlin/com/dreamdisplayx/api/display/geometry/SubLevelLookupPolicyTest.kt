package com.dreamdisplayx.api.display.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the pure half of Sable sub-level resolution: the hashed-id scan, the box helpers and the
 * world-versus-structure decision.
 *
 * These are the rules that a shipped log cannot distinguish, so they have to be asserted here: a
 * scan that stops at the first unreadable candidate looks exactly like "the structure is not loaded",
 * and a box reader that accepts an inverted box claims every coordinate in the world.
 */
class SubLevelLookupPolicyTest {

    private class Fake(var id: Long?, var bounds: DoubleArray? = null, val name: String = "")

    @Test
    fun `a hashed id resolves to the candidate that carries it`() {
        val wanted = SubLevelId.of(1L, 2L)
        val other = SubLevelId.of(3L, 4L)
        val candidates = listOf(Fake(other, name = "other"), Fake(wanted, name = "wanted"))

        val found = SubLevelLookupPolicy.findByHashedId(candidates, wanted) { it.id }

        assertEquals("wanted", found?.name)
    }

    @Test
    fun `an unreadable candidate is skipped instead of ending the scan`() {
        // A sub-level whose UUID getter throws must cost that one candidate. Returning null here would
        // look identical to "no structure is loaded", which is how a single broken structure would
        // silently un-anchor every display in the world.
        val wanted = SubLevelId.of(5L, 6L)
        val throwing = Fake(null, name = "throwing")
        val candidates = listOf(throwing, Fake(wanted, name = "wanted"))

        val found = SubLevelLookupPolicy.findByHashedId(candidates, wanted) { it.id }

        assertEquals("wanted", found?.name)
    }

    @Test
    fun `a world anchored id resolves to nothing`() {
        var inspected = 0
        val found = SubLevelLookupPolicy.findByHashedId(listOf(Fake(7L)), SubLevelId.NONE) {
            inspected++
            it.id
        }
        assertNull(found, "a world-anchored display must not walk the sub-level list at all")
        assertEquals(0, inspected, "the early return is the point: no backend is asked")
    }

    @Test
    fun `an index keeps one winner per id and drops unreadable candidates`() {
        val id = SubLevelId.of(9L, 10L)
        val first = Fake(id, name = "first")
        val second = Fake(id, name = "second")
        val broken = Fake(null, name = "broken")

        val index = SubLevelLookupPolicy.indexById(listOf(first, second, broken)) { it.id }

        assertEquals(1, index.size, "the broken candidate must not become an entry")
        assertSame(first, index[id], "on a collision the first candidate stays, so the picture is stable")
    }

    @Test
    fun `an index never stores the world anchored sentinel`() {
        val index = SubLevelLookupPolicy.indexById(listOf(Fake(SubLevelId.NONE))) { it.id }
        assertTrue(index.isEmpty(), "id 0 means world-anchored, so it cannot key a structure lookup")
    }

    @Test
    fun `a well formed box is read back in component order`() {
        val bounds = SubLevelLookupPolicy.boundsOf(1.0, 2.0, 3.0, 11.0, 12.0, 13.0)
        assertEquals(listOf(1.0, 2.0, 3.0, 11.0, 12.0, 13.0), bounds?.toList())
    }

    @Test
    fun `an empty plot box is rejected rather than claiming the world`() {
        // `LevelPlot.getBoundingBox()` answers `BoundingBox3i.EMPTY` (all zeros except a -1 maximum
        // on some releases) for a plot that has not been filled yet. Accepting that as a real box is
        // the difference between "no structure" and "every structure".
        assertNull(SubLevelLookupPolicy.boundsOf(0.0, 0.0, 0.0, 0.0, -1.0, 0.0))
    }

    @Test
    fun `a non finite box is rejected`() {
        assertNull(SubLevelLookupPolicy.boundsOf(0.0, 0.0, 0.0, Double.NaN, 1.0, 1.0))
        assertNull(SubLevelLookupPolicy.boundsOf(Double.NEGATIVE_INFINITY, 0.0, 0.0, 1.0, 1.0, 1.0))
    }

    @Test
    fun `contains is inclusive on both faces`() {
        val bounds = SubLevelLookupPolicy.boundsOf(0.0, 0.0, 0.0, 10.0, 10.0, 10.0)
        assertTrue(SubLevelLookupPolicy.contains(bounds, 0.0, 0.0, 0.0), "the minimum corner is inside")
        assertTrue(SubLevelLookupPolicy.contains(bounds, 10.0, 10.0, 10.0), "the maximum corner is inside")
        assertTrue(SubLevelLookupPolicy.contains(bounds, 5.0, 5.0, 5.0))
        assertFalse(SubLevelLookupPolicy.contains(bounds, 10.001, 5.0, 5.0), "one centimetre past the face is outside")
        assertFalse(SubLevelLookupPolicy.contains(bounds, -0.001, 5.0, 5.0))
    }

    @Test
    fun `an unreadable box contains nothing`() {
        assertFalse(SubLevelLookupPolicy.contains(null, 0.0, 0.0, 0.0))
        assertFalse(SubLevelLookupPolicy.contains(doubleArrayOf(1.0, 2.0), 0.0, 0.0, 0.0))
        assertFalse(
            SubLevelLookupPolicy.contains(SubLevelLookupPolicy.boundsOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), Double.NaN, 0.0, 0.0),
            "a NaN query must not be reported as a hit",
        )
    }

    @Test
    fun `a world space hit means the row needs the correction`() {
        assertEquals(StoredSpace.WORLD, SubLevelLookupPolicy.storedSpaceOf(worldHit = true, structureHit = false))
        assertTrue(StoredSpace.WORLD.needsCorrection)
    }

    @Test
    fun `a structure space hit means the row must not be projected again`() {
        assertEquals(StoredSpace.STRUCTURE, SubLevelLookupPolicy.storedSpaceOf(worldHit = false, structureHit = true))
        assertFalse(StoredSpace.STRUCTURE.needsCorrection)
    }

    @Test
    fun `a fresh structure satisfies both boxes and is read as world space`() {
        // Assembling a sub-level does not move the blocks that were already there, so both boxes
        // contain the stored coordinate on the frame of discovery. Reading it as world space is what
        // keeps the correction consistent with how the server stamped the row; it also cannot make
        // the picture jump, because the correction starts as the identity.
        assertEquals(StoredSpace.WORLD, SubLevelLookupPolicy.storedSpaceOf(worldHit = true, structureHit = true))
    }

    @Test
    fun `neither box containing the point is not a hit`() {
        val space = SubLevelLookupPolicy.storedSpaceOf(worldHit = false, structureHit = false)
        assertEquals(StoredSpace.UNKNOWN, space)
        assertFalse(space.needsCorrection, "an unknown row must never be given a correction")
    }

    @Test
    fun `a server stamp keeps a structure space hit`() {
        // The stamped case: Sable's own clip produced the click position, so the stored numbers are
        // already plot-local and `subLevelToWorld * pos` is the correct render.
        val id = SubLevelId.of(11L, 12L)
        assertEquals(id, SubLevelLookupPolicy.stampIdFor(StoredSpace.STRUCTURE, id))
    }

    @Test
    fun `a server stamp refuses a world space hit`() {
        // Stamping there would make every client apply the structure twice — once by Sable to the
        // world, once by the renderer to the position — and the picture would fly off into the void.
        // The row instead stays world-anchored and the client's discovery probe adopts the structure
        // together with the world-to-structure correction it needs.
        val id = SubLevelId.of(13L, 14L)
        assertEquals(SubLevelId.NONE, SubLevelLookupPolicy.stampIdFor(StoredSpace.WORLD, id))
    }

    @Test
    fun `a server stamp refuses a candidate that does not describe the coordinate`() {
        val id = SubLevelId.of(15L, 16L)
        assertEquals(SubLevelId.NONE, SubLevelLookupPolicy.stampIdFor(StoredSpace.UNKNOWN, id))
    }

    @Test
    fun `a stamped sub level id is recognisable as a structure anchor`() {
        // End to end on the numbers alone: the value a server persists has to pass the same gates the
        // client applies before it renders anything, otherwise a stamped sub-level display would be
        // treated as a world-anchored one and never follow its hull.
        val id = SubLevelLookupPolicy.stampIdFor(StoredSpace.STRUCTURE, SubLevelId.of(17L, 18L))
        assertTrue(ShipAnchorPolicy.isShipAnchored(id), "a stamped id must be a structure anchor")
        assertTrue(SubLevelId.isSubLevel(id), "and it must dispatch to Sable, not to Valkyrien Skies")
        assertFalse(ShipAnchorPolicy.shouldDropAnchor(id, shipStillExists = true))
        assertTrue(ShipAnchorPolicy.shouldDropAnchor(id, shipStillExists = false))
    }
}
