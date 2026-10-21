package com.dreamdisplays.platform.client.displays


/** Follows server-authoritative playback timeline with drift correction and optional seek cooldown. */
internal class TimelineFollower(private val screen: DisplayScreen) {
    private var lastSeekNanos = 0L
    private var learnedLeadMs = SEEK_LEAD_MS
    private var missStreak = 0
    private var awaitingLanding = false
    private var lastServerTimeMs = Long.MIN_VALUE
    private var nextSeq = 0L
    private var pending: Pending? = null

    private data class Pending(
        val seq: Long,
        val targetMs: Long,
        val serverTimeMs: Long,
        val paused: Boolean,
        val loop: Boolean,
        val receivedAtNanos: Long,
    )

    /** Clears follow state when a new video loads. */
    fun reset() {
        lastSeekNanos = 0L
        missStreak = 0
        awaitingLanding = false
        lastServerTimeMs = Long.MIN_VALUE
        pending = null
    }

    /** Re-applies a timeline packet that arrived before the media player existed or finished init. */
    fun onPlayerCreated() {
        pending?.let(::applyPending)
    }

    /**
     * Matches the server's [targetMs] / [paused] for the current media, wrapping by the locally known
     * duration when [loop] (Broadcast). Defers until the player is initialized, projecting the target
     * forward by the time elapsed across that defer so the comparison uses an up-to-date position.
     */
    fun apply(targetMs: Long, serverTimeMs: Long, paused: Boolean, loop: Boolean) {
        if (serverTimeMs in 1..<lastServerTimeMs) return
        if (serverTimeMs > 0L) lastServerTimeMs = serverTimeMs
        val packet = Pending(++nextSeq, targetMs, serverTimeMs, paused, loop, System.nanoTime())
        pending = packet
        applyPending(packet)
    }

    private fun applyPending(packet: Pending) {
        screen.primeTimelineStart(projectTargetMs(packet) * 1_000_000L)
        val generation = screen.mediaGeneration
        screen.waitForMFInit {
            if (generation != screen.mediaGeneration || pending?.seq != packet.seq) return@waitForMFInit

            val initialTimeline = screen.isWaitingForInitialTimeline
            if (!screen.videoStarted) screen.beginPlaybackPaused(packet.paused)

            // Pause-state matching is cheap and must never be gated by the seek cooldown
            if (packet.paused && !screen.isPaused) screen.applyServerPaused(true)
            if (!packet.paused && screen.isPaused) screen.applyServerPaused(false)

            if (screen.isLive) {
                screen.markInitialTimelineReady()
                pending = null
                return@waitForMFInit
            }

            if (!screen.canSeek()) {
                screen.markInitialTimelineReady()
                pending = null
                return@waitForMFInit
            }
            // While playing, a not-running clock means the stream is mid-restart; its position is
            // meaningless, so skip rather than read it as huge drift and seek again.
            if (!packet.paused && !screen.isClockRunning() && !initialTimeline) return@waitForMFInit

            val durationMs = screen.mediaPlayerDurationNanos / 1_000_000L
            val target = wrap(projectTargetMs(packet), packet.loop, durationMs)

            val localMs = screen.currentTimeNanos / 1_000_000L
            val driftMs = signedDrift(target - localMs, packet.loop, durationMs)
            // driftMs > 0 => player is behind the server; < 0 => player is ahead

            val sinceSeekMs = if (lastSeekNanos == 0L) Long.MAX_VALUE
            else (System.nanoTime() - lastSeekNanos) / 1_000_000L
            // Always honor the cooldown: re-seeking while a previous correction is still warming up is
            // exactly what makes a cold rejoin thrash. The first correction after a load is exempt
            // (lastSeekNanos == 0), so joining still snaps to live immediately.
            if (!initialTimeline && sinceSeekMs < SEEK_COOLDOWN_MS * (1 + missStreak)) return@waitForMFInit

            if (awaitingLanding && !packet.paused) {
                awaitingLanding = false
                learnFromLanding(driftMs)
            }

            val catchUpToleranceMs = (CATCH_UP_TOLERANCE_MS shl missStreak.coerceAtMost(MAX_BACKOFF_SHIFT))
                .coerceAtMost(MAX_BACKOFF_TOLERANCE_MS)
            val needsSeek = driftMs > catchUpToleranceMs || -driftMs > AHEAD_TOLERANCE_MS
            if (!needsSeek) {
                screen.markInitialTimelineReady()
                pending = null
                return@waitForMFInit
            }

            // When catching up forward, overshoot by the restart cost so the post-stall position lands
            // on the live point. Clamp shy of the end so we never seek past EOS (which would loop to 0).
            val catchUp = driftMs > 0 && !packet.paused
            val seekMs = if (catchUp) {
                val lead = target + learnedLeadMs
                wrap(
                    if (!packet.loop && durationMs > 0) lead.coerceAtMost(durationMs - END_MARGIN_MS) else lead,
                    packet.loop,
                    durationMs
                )
            } else target

            if (!packet.paused) screen.clearRenderedFrameForTimeline()
            screen.seekVideoTo(seekMs * 1_000_000L)
            lastSeekNanos = System.nanoTime()
            awaitingLanding = catchUp
            screen.markInitialTimelineReady()
            pending = null
        }
    }

    private fun learnFromLanding(landingDriftMs: Long) {
        when {
            landingDriftMs > CATCH_UP_TOLERANCE_MS -> {
                learnedLeadMs = (learnedLeadMs + landingDriftMs + LEAD_MARGIN_MS).coerceAtMost(MAX_LEAD_MS)
                missStreak++
            }
            landingDriftMs < -LEAD_MARGIN_MS -> {
                learnedLeadMs = (learnedLeadMs + landingDriftMs / 2).coerceAtLeast(SEEK_LEAD_MS)
                missStreak = 0
            }
            else -> missStreak = 0
        }
    }

    private fun projectTargetMs(packet: Pending): Long {
        val elapsedMs = if (packet.paused) 0L else (System.nanoTime() - packet.receivedAtNanos) / 1_000_000L
        return packet.targetMs + elapsedMs
    }

    private fun wrap(ms: Long, loop: Boolean, durationMs: Long): Long =
        if (loop && durationMs > 0) ((ms % durationMs) + durationMs) % durationMs else ms.coerceAtLeast(0)

    private fun signedDrift(rawDiff: Long, loop: Boolean, durationMs: Long): Long {
        if (!loop || durationMs <= 0) return rawDiff
        val half = durationMs / 2
        return ((rawDiff % durationMs) + durationMs + half) % durationMs - half
    }

    companion object {
        private const val CATCH_UP_TOLERANCE_MS = 1_500L
        private const val AHEAD_TOLERANCE_MS = 3_500L
        private const val SEEK_LEAD_MS = 2_500L
        private const val SEEK_COOLDOWN_MS = 7_000L
        private const val MAX_LEAD_MS = 20_000L
        private const val LEAD_MARGIN_MS = 500L
        private const val MAX_BACKOFF_SHIFT = 3
        private const val MAX_BACKOFF_TOLERANCE_MS = 10_000L
        private const val END_MARGIN_MS = 500L
    }
}
