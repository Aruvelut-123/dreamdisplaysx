package com.dreamdisplayx.api.render.texture.policy

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Liveness rules for the GPU upload path, kept free of OpenGL and Minecraft types so they can be
 * unit-tested.
 *
 * Both rules guard against the same class of failure: a display that stops advancing while the audio
 * thread keeps playing. Audio is drained by the aout thread and is therefore completely independent
 * of the render loop, so anything that makes the *video* side stall is heard as "the picture froze
 * but the sound is still going".
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
object TextureUploadPolicy {

    /**
     * Whether a frame that is still ahead of the display clock should be shown anyway.
     *
     * The frame queue holds a frame back until the media clock reaches its presentation timestamp, so
     * a frame delivered early waits for its slot instead of replacing the picture ahead of time. That
     * is correct for pacing and catastrophic as a liveness rule: if the clock never reaches a stamped
     * timestamp — a DASH fragment seek leaving `libvlc_media_player_get_time()` far ahead of the frames
     * actually being delivered, a clock that stalled, a position rebased behind the queue — then every
     * queued frame stays "in the future" forever. The queue fills to its cap and starts discarding the
     * arriving frames, and the display freezes on its last uploaded picture.
     *
     * So a frame that has waited longer than [maxHeadWaitNanos] is released regardless. When the
     * clock is merely slow this costs at most one out-of-pace frame; when the clock is wrong it bounds
     * the freeze instead of turning it into a permanent one.
     *
     * @param pts frame presentation timestamp in content nanoseconds
     * @param horizon the newest timestamp that may be shown now (display clock + tolerance)
     * @param waitedNanos wall-clock time the frame has spent queued
     * @param showNowMarker the sentinel meaning "due at once"; such a frame is never held
     * @param maxHeadWaitNanos how long a queued frame may refuse to become due
     */
    fun shouldReleaseAheadOfClock(
        pts: Long,
        horizon: Long,
        waitedNanos: Long,
        showNowMarker: Long,
        maxHeadWaitNanos: Long,
    ): Boolean {
        if (pts == showNowMarker) return true
        if (pts <= horizon) return true
        return waitedNanos >= maxHeadWaitNanos
    }
}