package com.dreamdisplayx.media.player.managers

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Audio track warm pool — no-op under libvlc, which handles audio track
 * switching natively. Kept as a stub so MediaPlayer's wiring doesn't break.
 */
internal class AudioTrackWarmPool(
    private val debugLabel: String,
    private val terminated: AtomicBoolean,
    private val positionNanos: () -> Long,
    private val eligible: () -> Boolean,
) {
    class Warm(
        val url: String,
        val process: Process,
        val stop: AtomicBoolean,
        val contentStartNanos: Long,
    )

    fun setTracks(tracks: List<WarmTrack>) {}
    fun take(url: String): Warm? = null
    fun invalidateAll() {}
    fun close() {}

    private companion object {
        const val MAX_WARM = 0
    }
}