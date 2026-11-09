package com.dreamdisplayx.api.display.geometry

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Maps Sable's `java.util.UUID` sub-level identity onto the `Long` structure id the rest of this
 * fork already speaks.
 *
 * `ShipAnchor.shipId` is a `Long` end to end: it is persisted in the display row, sent over the wire
 * in `DisplayInfo`, and used as the key of [ShipTransformCache]. Valkyrien Skies hands out a `long`
 * and fits that shape naturally; Sable identifies a sub-level with a `UUID`, and that type cannot be
 * threaded through the anchor model without touching the database schema, the protocol and every
 * caller. So the bridge hashes the two 64-bit halves of the UUID into one positive `Long` and uses
 * that as the id.
 *
 * The hash is **not** an identity: two different sub-levels could in principle collide. That is
 * accepted because the id is never stored as the only handle — every lookup hashes the ids of the
 * currently loaded sub-levels and compares, so a collision between two *loaded* structures is the
 * only way to get the wrong matrix, and the hash space is 61 bits wide. If that ever becomes a real
 * concern the stored value can grow into a `String`/`UUID` column; until then this keeps the whole
 * existing render, distance-census, acoustics and raycast pipeline unchanged.
 *
 * A result is never [NONE]: `0` means "anchored in the world", so a genuine hash of zero is nudged
 * to `1`. The hash also drops the sign bit, so [ShipAnchor.of] never treats a Sable anchor as a
 * corrupt row and normalises it away.
 *
 * **Every Sable id carries [SABLE_FLAG] (bit 62).** Both physics mods feed the same `shipId` column,
 * and a client can in principle have both installed, so the two id spaces have to be disjoint:
 * Valkyrien Skies hands out small increasing `long`s that never approach 2^62, while a 61-bit hash
 * spread over the whole range would collide with those constantly. The flag is what
 * `StructureTransforms` dispatches on, and it survives a save/reload because it is part of the
 * persisted number rather than of a session-local map.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
object SubLevelId {
    /** The sentinel [ShipAnchor] uses for a world-anchored display. */
    const val NONE: Long = ShipAnchor.NONE

    /**
     * Marks a structure id as belonging to Sable rather than Valkyrien Skies.
     *
     * Bit 62, not bit 63: the sign bit stays clear so [ShipAnchor.of] keeps treating every Sable
     * anchor as a valid structure instead of normalising it away as a corrupt row.
     */
    const val SABLE_FLAG: Long = 1L shl 62

    /** FNV-1a 64-bit offset basis (`0xcbf29ce484222325`), written signed. */
    private const val FNV_OFFSET: Long = -0x340d631b7bdddcdbL

    /** FNV-1a 64-bit prime (`0x100000001b3`). */
    private const val FNV_PRIME: Long = 0x100000001b3L

    /**
     * Hashes a sub-level's UUID halves into a positive, non-zero structure id.
     *
     * Both halves are fed byte by byte, most significant first, so the result depends on the whole
     * UUID rather than on a truncation — Sable's ids are random v4 UUIDs, whose low bits are the only
     * random half, but feeding both costs nothing and survives a future v7/name-based id.
     *
     * A final avalanche step (`splitmix64`'s mixer) is applied because FNV alone leaves the high bits
     * poorly mixed, and the sign bit is then dropped.
     */
    fun of(mostSigBits: Long, leastSigBits: Long): Long {
        var hash = FNV_OFFSET
        for (shift in 56 downTo 0 step 8) {
            hash = (hash xor ((mostSigBits ushr shift) and 0xFFL)) * FNV_PRIME
        }
        for (shift in 56 downTo 0 step 8) {
            hash = (hash xor ((leastSigBits ushr shift) and 0xFFL)) * FNV_PRIME
        }
        // Mask off the flag bits *before* setting the flag, so the two operations cannot fight over
        // bit 62 and a hash that happens to set it still round-trips.
        val mixed = (mix(hash) and HASH_MASK) or SABLE_FLAG
        return if (mixed == NONE) SABLE_FLAG or 1L else mixed
    }

    /** Convenience overload for a `UUID`; the interface stays `Long`-only for the dependency-free api module. */
    fun of(uuid: java.util.UUID): Long = of(uuid.mostSignificantBits, uuid.leastSignificantBits)

    /**
     * True when [shipId] names a Sable sub-level rather than a Valkyrien Skies ship.
     *
     * The single dispatch predicate for the whole structure-anchoring pipeline; nothing else should
     * test the flag directly, so the bit can move if a future id space needs room.
     */
    fun isSubLevel(shipId: Long): Boolean = (shipId and SABLE_FLAG) != 0L

    /** The payload bits of a Sable id: everything a UUID hash can occupy. */
    private const val HASH_MASK: Long = 0x3FFF_FFFF_FFFF_FFFFL

    /**
     * `splitmix64`'s finaliser.
     *
     * Included inline rather than pulled from a library: `api` is a verbatim copy of upstream's tree
     * plus a few dependency-free helpers, and this is the only place that needs it.
     */
    private fun mix(value: Long): Long {
        var z = value
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
