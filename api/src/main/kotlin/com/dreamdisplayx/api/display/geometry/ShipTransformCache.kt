package com.dreamdisplayx.api.display.geometry

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Short-lived cache for structure transforms, so one frame does not pay for the physics lookup
 * several times over.
 *
 * Reading a structure transform is not cheap: the client bridge iterates every loaded structure
 * looking for the right id and crosses a reflection boundary three times per hit. A display that
 * rides a hull asks for its transform from several unrelated places inside one frame — the renderer
 * once per screen, the distance census, the acoustic plane and the crosshair ray test — and each of
 * those used to pay the whole iteration again. On a moving ship that burst of reflective lookups on
 * the render thread is what makes the picture judder against the hull it is bolted to.
 *
 * The sample lifetime has to cover a whole frame, not a couple of milliseconds: a value shorter than
 * the gap between the callers would look like a cache while still missing several times per frame.
 * [DEFAULT_SAMPLE_NANOS] is one frame at 120 fps, so everything a 60 fps client does in one frame
 * shares a single read. It is deliberately not longer than that — VS's render transform is the pose
 * *interpolated* between physics ticks, and reusing it across frames would make the picture lag the
 * hull. A miss simply re-reads the source, so this type can never be more wrong than "it was not
 * cached".
 *
 * `shipId == 0` (a world-anchored display) never reaches the source here, matching
 * [ShipAnchorPolicy.transformFor].
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
class ShipTransformCache(
    private val source: ShipTransformSource,
    private val sampleNanos: Long = DEFAULT_SAMPLE_NANOS,
    private val clock: () -> Long = System::nanoTime,
) {
    /** One sampled structure. [matrix] is `null` when the structure was not loaded at sample time. */
    private class Entry(
        val matrix: DoubleArray?,
        val transform: AffineTransform,
        val sampledAt: Long,
    )

    /**
     * The last sample per structure id.
     *
     * Concurrent because the renderer, the tick loop and the interaction service all read it, and a
     * display can be dropped from another thread while a frame is in flight. Every entry is immutable,
     * so a plain `get`/`put` is enough — there is no read-modify-write to interleave.
     */
    private val entries = java.util.concurrent.ConcurrentHashMap<Long, Entry>()

    /** Transforms resolved from [source] since the last [invalidate]. Diagnostics only. */
    @Volatile
    var resolutions: Long = 0L
        private set

    /** Reads served from a sample still inside [sampleNanos] since the last [invalidate]. */
    @Volatile
    var hits: Long = 0L
        private set

    /**
     * The transform to draw [shipId] with, or [AffineTransform.IDENTITY] when it cannot be resolved.
     *
     * The same instance is returned for every call inside one sample window, so callers must treat it
     * as immutable. They do: it is only read, compared with `isIdentity`, and multiplied.
     */
    fun transformFor(shipId: Long): AffineTransform {
        if (ShipAnchor.of(shipId) === ShipAnchor.world) return AffineTransform.IDENTITY
        return entryFor(shipId).transform
    }

    /** The structure-to-world matrix for [shipId] as 16 column-major doubles, or `null`. */
    fun matrixFor(shipId: Long): DoubleArray? {
        if (ShipAnchor.of(shipId) === ShipAnchor.world) return null
        return entryFor(shipId).matrix?.copyOf()
    }

    /** Drops every sample; call when the client leaves or switches a level. */
    fun invalidate() {
        entries.clear()
        resolutions = 0L
        hits = 0L
    }

    private fun entryFor(shipId: Long): Entry {
        val now = clock()
        val cached = entries[shipId]
        // `0 until sampleNanos` also rejects a sample from the future. That cannot happen with a
        // monotonic clock, but a non-monotonic one must not be able to pin an entry forever.
        if (cached != null && now - cached.sampledAt in 0 until sampleNanos) {
            hits++
            return cached
        }
        val fresh = resolve(shipId, now)
        // Only replace the slot that was read, so two threads racing on a cold id both resolve but the
        // newer sample wins instead of being overwritten by the older one.
        if (cached == null) {
            entries.putIfAbsent(shipId, fresh)
        } else {
            entries.replace(shipId, cached, fresh)
        }
        // A shipyard is a handful of hulls; anything past this is ids of deleted structures that never
        // come back, not a working set. Dropping everything only costs one re-read.
        if (entries.size > MAX_ENTRIES) entries.clear()
        return fresh
    }

    private fun resolve(shipId: Long, now: Long): Entry {
        resolutions++
        // A throwing source must degrade to "not loaded", never to an exception on the render thread.
        val matrix = runCatching { source.matrixOf(shipId) }.getOrNull()
            ?: return Entry(null, AffineTransform.IDENTITY, now)
        return Entry(matrix, ShipAnchorPolicy.matrixToTransform(matrix), now)
    }

    companion object {
        /**
         * Default sample lifetime: one frame at 120 fps.
         *
         * Long enough that every caller inside one frame on a 60 fps client shares a single physics
         * read, short enough that the picture cannot visibly lag a moving hull.
         */
        const val DEFAULT_SAMPLE_NANOS: Long = 8_000_000L

        /** Soft ceiling on cached structures before the map is dropped wholesale. */
        const val MAX_ENTRIES: Int = 256
    }
}