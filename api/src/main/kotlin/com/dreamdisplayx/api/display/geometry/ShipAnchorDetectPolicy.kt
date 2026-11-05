package com.dreamdisplayx.api.display.geometry

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Decides when the client should look up by itself which structure a display rides on.
 *
 * The server samples the ship exactly once, in `stampShipAnchor`, and only from `/display create`.
 * A display created *before* its blocks are assembled into a ship therefore keeps `shipId = 0`
 * forever: [ShipAnchorPolicy.transformFor] short-circuits on a world anchor, the renderer never asks
 * the physics mod anything, and the picture stays behind while the hull sails off — even with no
 * video playing, because the quad is placed from the stored coordinates alone.
 *
 * The render path needs a correct anchor on *every* frame, while the reflection lookup behind
 * `ValkyrienClientCompat.detectShipId` costs far more than a per-frame budget allows, so the
 * scheduling lives here: pure numbers, unit-testable, no Minecraft or JOML types.
 *
 * The bias is deliberately "probe too often": a redundant probe costs a few microseconds, whereas a
 * missed one pins the display in world space for the rest of the session.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
object ShipAnchorDetectPolicy {
    /** After a probe that found nothing, wait at least this many ticks before trying again. */
    const val MISS_RETRY_TICKS = 10L

    /**
     * After a probe that found a structure, re-check only this often.
     *
     * Boarding and leaving a ship are rare, but a single hit must not be trusted for the whole
     * session: a display dismantled off its hull would otherwise keep applying a stale matrix.
     */
    const val HIT_RECHECK_TICKS = 100L

    /** Sentinel for "this display has never been probed". */
    const val NEVER_PROBED = -1L

    /**
     * Whether a probe should run on the current tick.
     *
     * @param storedShipId the anchor the server sent; once non-zero there is nothing left to discover
     * @param detectedShipId the id a previous probe found, `0` while nothing has been found yet
     * @param lastProbeTick the tick of the previous probe, [NEVER_PROBED] when there was none
     * @param nowTick the current world game time
     */
    fun shouldProbe(
        storedShipId: Long,
        detectedShipId: Long,
        lastProbeTick: Long,
        nowTick: Long,
    ): Boolean {
        // The server already answered. Probing would only be able to disagree with it.
        if (ShipAnchorPolicy.isShipAnchored(storedShipId)) return false
        if (lastProbeTick == NEVER_PROBED) return true
        val interval = if (ShipAnchorPolicy.isShipAnchored(detectedShipId)) {
            HIT_RECHECK_TICKS
        } else {
            MISS_RETRY_TICKS
        }
        return nowTick - lastProbeTick >= interval
    }

    /**
     * The anchor to render with: the server's value when it has one, otherwise the client's own.
     *
     * A negative [detectedShipId] is normalised to `0` for the same reason [ShipAnchor.of] does it —
     * a corrupt id must behave like "no structure" rather than reach the matrix source.
     */
    fun effectiveShipId(storedShipId: Long, detectedShipId: Long): Long =
        if (ShipAnchorPolicy.isShipAnchored(storedShipId)) {
            storedShipId
        } else {
            detectedShipId.coerceAtLeast(0L)
        }
}
