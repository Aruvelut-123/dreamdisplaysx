package com.dreamdisplayx.api.display.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the sampling rules that keep a hull-mounted display from stuttering.
 *
 * The bug this covers: a display on a ship asks for its structure transform from the renderer, the
 * distance census, the acoustic plane and the crosshair ray test, all inside one frame. Each of those
 * ran the full reflective lookup — iterate every loaded structure, compare ids, copy a 16-double
 * matrix — so the render thread paid for the same physics read several times per frame and the picture
 * juddered against the hull.
 *
 * Both directions matter here and a wrong answer is only visible in game: sampling too aggressively
 * leaves the stutter (a hit that never hits), sampling for too long detaches the picture from a moving
 * hull (it lags a frame or more behind).
 */
class ShipTransformCacheTest {
    private fun identityMatrix() = AffineTransform.IDENTITY.m.copyOf()

    /** A source that counts its calls and can be told what to return. */
    private class CountingSource(private val matrix: DoubleArray?) : ShipTransformSource {
        var asked = 0
            private set

        override fun matrixOf(shipId: Long): DoubleArray? {
            asked++
            return matrix?.copyOf()
        }
    }

    @Test
    fun callersInsideOneFrameShareASingleLookup() {
        val source = CountingSource(identityMatrix())
        var now = 1_000_000_000L
        val cache = ShipTransformCache(source, sampleNanos = 8_000_000L, clock = { now })

        // One frame's worth of readers, well inside the sample window.
        cache.transformFor(7L)
        cache.transformFor(7L)
        cache.transformFor(7L)
        cache.transformFor(7L)
        cache.matrixFor(7L)

        assertEquals(1, source.asked, "every reader inside a frame must share one physics read")
        assertEquals(1L, cache.resolutions)
        assertEquals(4L, cache.hits)
    }

    @Test
    fun aSampleExpiresSoThePictureCannotLagTheHull() {
        val source = CountingSource(identityMatrix())
        var now = 0L
        val cache = ShipTransformCache(source, sampleNanos = 8_000_000L, clock = { now })

        cache.transformFor(7L)
        assertEquals(1, source.asked)

        now += 7_999_999L
        cache.transformFor(7L)
        assertEquals(1, source.asked, "one nanosecond before the window closes it is still a hit")

        now += 1L
        cache.transformFor(7L)
        assertEquals(2, source.asked, "the next frame must re-read, or the picture would lag the hull")
    }

    @Test
    fun aWorldAnchoredDisplayNeverReachesTheSource() {
        val source = CountingSource(identityMatrix())
        val cache = ShipTransformCache(source)

        assertEquals(AffineTransform.IDENTITY, cache.transformFor(0L))
        assertEquals(AffineTransform.IDENTITY, cache.transformFor(-1L))
        assertNull(cache.matrixFor(0L))
        assertEquals(0, source.asked, "shipId 0 is the common case and must not cross the bridge")
        assertEquals(0L, cache.resolutions)
    }

    @Test
    fun anUnloadedStructureIsCachedAsAbsentRatherThanReResolvedEveryCall() {
        val source = CountingSource(null)
        var now = 0L
        val cache = ShipTransformCache(source, sampleNanos = 8_000_000L, clock = { now })

        assertEquals(AffineTransform.IDENTITY, cache.transformFor(7L))
        assertEquals(AffineTransform.IDENTITY, cache.transformFor(7L))
        assertNull(cache.matrixFor(7L))

        assertEquals(1, source.asked, "a not-yet-streamed hull must not be re-probed per reader")
    }

    @Test
    fun aThrowingSourceDegradesToIdentityInsteadOfBreakingTheRenderThread() {
        var asked = 0
        var now = 0L
        val cache = ShipTransformCache(
            ShipTransformSource { asked++; throw IllegalStateException("bridge blew up") },
            sampleNanos = 8_000_000L,
            clock = { now },
        )

        assertEquals(AffineTransform.IDENTITY, cache.transformFor(7L))
        assertEquals(1, asked)
        // A transient failure must be retried on the next frame, not pinned for the session: the hull
        // may simply not have streamed in yet.
        now += 8_000_000L
        assertEquals(AffineTransform.IDENTITY, cache.transformFor(7L))
        assertEquals(2, asked)
    }

    @Test
    fun everyStructureGetsItsOwnSample() {
        val source = CountingSource(identityMatrix())
        var now = 0L
        val cache = ShipTransformCache(source, sampleNanos = 8_000_000L, clock = { now })

        cache.transformFor(1L)
        cache.transformFor(2L)
        cache.transformFor(1L)
        cache.transformFor(2L)

        assertEquals(2, source.asked, "two hulls in one frame are two reads, not four")
        assertEquals(2L, cache.hits)
    }

    @Test
    fun theCachedTransformIsTheOneBuiltFromTheMatrix() {
        val moved = AffineTransform.ofTranslation(0.0, 64.0, 0.0).m.copyOf()
        val cache = ShipTransformCache(CountingSource(moved))

        val transform = cache.transformFor(3L)
        assertEquals(64.0, transform.at(3, 1), 1.0e-9, "the live matrix must reach the caller")
        val matrix = cache.matrixFor(3L)
        assertNotNull(matrix)
        assertEquals(64.0, matrix[13], 1.0e-9)
    }

    @Test
    fun aCallerCannotCorruptTheCachedSample() {
        val cache = ShipTransformCache(CountingSource(AffineTransform.ofTranslation(0.0, 64.0, 0.0).m.copyOf()))
        val first = cache.matrixFor(3L)
        assertNotNull(first)
        first[13] = -999.0

        val second = cache.matrixFor(3L)
        assertNotNull(second)
        assertEquals(64.0, second[13], 1.0e-9, "matrixFor must hand out a copy, not the live array")
    }

    @Test
    fun invalidateForgetsEverythingForTheNextWorld() {
        val source = CountingSource(identityMatrix())
        val cache = ShipTransformCache(source)

        cache.transformFor(7L)
        cache.transformFor(7L)
        assertEquals(1, source.asked)

        cache.invalidate()
        assertEquals(0L, cache.resolutions)
        assertEquals(0L, cache.hits)

        cache.transformFor(7L)
        assertEquals(2, source.asked, "structure ids are per-world, so a level change must re-read")
    }

    @Test
    fun aClockThatGoesBackwardsCannotPinASampleForever() {
        val source = CountingSource(identityMatrix())
        var now = 1_000_000_000L
        val cache = ShipTransformCache(source, sampleNanos = 8_000_000L, clock = { now })

        cache.transformFor(7L)
        assertEquals(1, source.asked)

        // A sample stamped in the future is not a hit: otherwise a clock jump would freeze the
        // transform until wall time caught up.
        now -= 50_000_000L
        cache.transformFor(7L)
        assertEquals(2, source.asked)
    }

    @Test
    fun theDefaultWindowCoversAFrameButNotTwo() {
        // 8 ms is one frame at 120 fps and comfortably more than a 16 ms frame budget allows for a
        // single frame's callers, which is the whole point: a shorter window would miss several times
        // per frame and a longer one would visibly lag a moving hull.
        assertTrue(ShipTransformCache.DEFAULT_SAMPLE_NANOS in 4_000_000L..16_000_000L)
    }
}