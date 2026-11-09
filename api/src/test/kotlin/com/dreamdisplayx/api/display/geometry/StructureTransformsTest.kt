package com.dreamdisplayx.api.display.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the routing between the two physics-mod id spaces.
 *
 * The only thing this layer has to get right is that an id never reaches the wrong bridge: a
 * Valkyrien Skies id handed to the Sable bridge (or the reverse) answers `null`, which the renderer
 * reads as "structure not loaded" and leaves the display at its stored coordinates. That is
 * recoverable but wrong, and it is also exactly what a future refactor would break silently, so the
 * boundaries are asserted here.
 */
class StructureTransformsTest {

    private class RecordingSource(private val matrix: DoubleArray? = null) : ShipTransformSource {
        val asked = mutableListOf<Long>()
        override fun matrixOf(shipId: Long): DoubleArray? {
            asked += shipId
            return matrix
        }
    }

    private fun sampleMatrix(x: Double) = AffineTransform.ofTranslation(x, 0.0, 0.0).m

    /** A Sable id, i.e. one carrying bit 62. */
    private fun sableId(payload: Long = 1234L): Long = payload or SubLevelId.SABLE_FLAG

    @Test
    fun `a valkyrien skies id only reaches the ship backend`() {
        val ships = RecordingSource(sampleMatrix(1.0))
        val subLevels = RecordingSource(sampleMatrix(2.0))
        val dispatcher = StructureTransforms(ships, subLevels)

        val matrix = dispatcher.matrixOf(42L)

        assertEquals(listOf(42L), ships.asked)
        assertTrue(subLevels.asked.isEmpty(), "a VS id must never reach the Sable bridge")
        assertEquals(1.0, matrix?.get(12))
    }

    @Test
    fun `a sable id only reaches the sub level backend`() {
        val ships = RecordingSource(sampleMatrix(1.0))
        val subLevels = RecordingSource(sampleMatrix(2.0))
        val dispatcher = StructureTransforms(ships, subLevels)

        val id = sableId()
        val matrix = dispatcher.matrixOf(id)

        assertTrue(ships.asked.isEmpty(), "a Sable id must never reach the VS bridge")
        assertEquals(listOf(id), subLevels.asked)
        assertEquals(2.0, matrix?.get(12))
    }

    @Test
    fun `a world anchored display asks neither backend`() {
        val ships = RecordingSource(sampleMatrix(1.0))
        val subLevels = RecordingSource(sampleMatrix(2.0))
        val dispatcher = StructureTransforms(ships, subLevels)

        assertNull(dispatcher.matrixOf(ShipAnchor.NONE))
        assertNull(dispatcher.matrixOf(0L))
        assertTrue(ships.asked.isEmpty())
        assertTrue(subLevels.asked.isEmpty())
    }

    @Test
    fun `a negative id is treated as no structure`() {
        val ships = RecordingSource(sampleMatrix(1.0))
        val dispatcher = StructureTransforms(ships, ShipTransformSource.NONE)

        assertNull(dispatcher.matrixOf(-5L), "a corrupt row must not reach a backend")
        assertTrue(ships.asked.isEmpty())
    }

    @Test
    fun `a missing backend answers null rather than identity`() {
        // The distinction matters: identity would draw the display at the world origin, which looks
        // like a bug in the physics mod rather than like "this server has Sable but the client does
        // not". `transformFor` turns the null into "keep the stored coordinates".
        val dispatcher = StructureTransforms(ShipTransformSource.NONE, ShipTransformSource.NONE)
        assertNull(dispatcher.matrixOf(7L))
        assertNull(dispatcher.matrixOf(sableId()))
        assertFalse(dispatcher.hasAnyBackend)
        assertNull(dispatcher.sourceFor(7L))
        assertNull(dispatcher.sourceFor(sableId()))
    }

    @Test
    fun `a throwing backend degrades to null instead of propagating`() {
        val throwing = ShipTransformSource { error("physics thread died") }
        val dispatcher = StructureTransforms(throwing, throwing)

        assertNull(dispatcher.matrixOf(3L))
        assertNull(dispatcher.matrixOf(sableId()), "a Sable lookup must fail closed the same way")
    }

    @Test
    fun `ownerOf classifies the three cases`() {
        val dispatcher = StructureTransforms(RecordingSource(), RecordingSource())

        assertEquals(StructureOwner.NONE, dispatcher.ownerOf(0L))
        assertEquals(StructureOwner.NONE, dispatcher.ownerOf(-1L))
        assertEquals(StructureOwner.VALKYRIEN_SKIES, dispatcher.ownerOf(99L))
        assertEquals(StructureOwner.SABLE, dispatcher.ownerOf(sableId()))
    }

    @Test
    fun `sourceFor returns the same instance the dispatcher holds`() {
        val ships = RecordingSource()
        val subLevels = RecordingSource()
        val dispatcher = StructureTransforms(ships, subLevels)

        assertSame(ships, dispatcher.sourceFor(5L))
        assertSame(subLevels, dispatcher.sourceFor(sableId()))
        assertTrue(dispatcher.hasAnyBackend)
    }

    @Test
    fun `one installed mod does not claim the other id space`() {
        // A client with only Sable installed must still answer for Sable and stay inert for VS, and
        // the reverse. Getting this wrong is how a "Sable only" client would try to resolve its ids
        // through a VS bridge that is not there.
        val sableOnly = StructureTransforms(ShipTransformSource.NONE, RecordingSource(sampleMatrix(9.0)))
        assertEquals(9.0, sableOnly.matrixOf(sableId())?.get(12))
        assertNull(sableOnly.matrixOf(11L))

        val vsOnly = StructureTransforms(RecordingSource(sampleMatrix(4.0)), ShipTransformSource.NONE)
        assertEquals(4.0, vsOnly.matrixOf(11L)?.get(12))
        assertNull(vsOnly.matrixOf(sableId()))
    }
}

/**
 * Pins [SubLevelId], the `UUID` → `Long` fold that lets Sable reuse the whole existing anchor model.
 *
 * The properties that matter are structural, not cryptographic: the value must never collide with
 * the world sentinel, must never look like a Valkyrien Skies id, and must be a pure function of the
 * UUID so a persisted row keeps pointing at the same substructure across a restart.
 */
class SubLevelIdTest {

    @Test
    fun `a hashed id is always flagged and never zero`() {
        val zero = SubLevelId.of(0L, 0L)
        assertTrue(SubLevelId.isSubLevel(zero), "every Sable id carries the flag")
        assertTrue(zero != SubLevelId.NONE, "an id of 0 would mean world-anchored")
        assertTrue(zero > 0L, "the sign bit must stay clear so ShipAnchor keeps the id")
    }

    @Test
    fun `hashing is deterministic`() {
        val uuid = java.util.UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        assertEquals(SubLevelId.of(uuid), SubLevelId.of(uuid))
        assertEquals(
            SubLevelId.of(uuid.mostSignificantBits, uuid.leastSignificantBits),
            SubLevelId.of(uuid),
        )
    }

    @Test
    fun `different uuids hash to different ids`() {
        // Not a proof of collision resistance — the contract only needs "distinct in practice" for the
        // handful of sub-levels loaded at once — but it does catch a truncation bug (e.g. hashing only
        // the low half, which would make every v4 UUID's high bits irrelevant).
        val ids = HashSet<Long>()
        for (i in 0 until 2000) {
            val uuid = java.util.UUID.randomUUID()
            assertTrue(ids.add(SubLevelId.of(uuid)), "collision on iteration $i")
        }
    }

    @Test
    fun `the high half of a uuid participates in the hash`() {
        val low = java.util.UUID.randomUUID().leastSignificantBits
        val a = SubLevelId.of(0x0123456789abcdefL, low)
        val b = SubLevelId.of(-0x0123456789abcdf0L, low)
        assertTrue(a != b, "hashing only the low half would make these identical")
    }

    @Test
    fun `a sable id never looks like a valkyrien skies ship id`() {
        // VS hands out small increasing longs; bit 62 is the discriminator the dispatcher uses, so a
        // realistic ship id must classify as VS and a hashed UUID must classify as Sable.
        val shipId = 1234L
        assertFalse(SubLevelId.isSubLevel(shipId))
        for (i in 0 until 500) {
            assertTrue(SubLevelId.isSubLevel(SubLevelId.of(java.util.UUID.randomUUID())))
        }
    }

    @Test
    fun `the flag survives the uuids that would set it themselves`() {
        // A hash whose payload happens to set bit 62 must not be double-flagged into a different
        // value: `of` masks before it sets, so the result is stable and still classified as Sable.
        val candidates = listOf(
            0x4000000000000000L to 0L,
            0x7fffffffffffffffL to 0x7fffffffffffffffL,
            -1L to -1L,
        )
        for ((hi, lo) in candidates) {
            val id = SubLevelId.of(hi, lo)
            assertTrue(SubLevelId.isSubLevel(id), "flags must be set for $hi/$lo")
            assertTrue(id > 0L, "the sign bit must stay clear for $hi/$lo")
            assertEquals(id, SubLevelId.of(hi, lo), "hashing must be deterministic")
        }
    }
}