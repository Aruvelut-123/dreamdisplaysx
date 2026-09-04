package com.dreamdisplayx.media.player.managers

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Watches whether frames are arriving. If no frame arrives within the applicable threshold, calls [onStall] and stops itself;
 * the caller decides how to recover.
 */
internal class StreamWatchdog(
    private val debugLabel: String,
    private val isSessionActive: () -> Boolean,
    private val getLastFrameNanos: () -> Long,
    private val stallThresholdMs: Long = 45_000L,
    private val startupThresholdMs: Long = 20_000L,
    private val checkIntervalMs: Long = 1_000L,
    private val onStall: () -> Unit,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/StreamWatchdog")

    @Volatile
    private var job: Job? = null

    private var deliveredAFrame = false
    private var lastSeenStamp = 0L
    private var silentSinceNanos = 0L

    /** Coroutine scope for the watchdog task. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("MediaPlayer-watchdog"))

    /** Start watchdog. */
    fun start() {
        stop()
        deliveredAFrame = false
        // Reset the baseline to NOW, not the last frame timestamp, so a restart doesn't
        // immediately inherit the previous session's silence gap and fire a false stall.
        lastSeenStamp = System.nanoTime()
        // The silence clock starts at the same baseline: until a frame lands, the session is
        // silent since start, not since the epoch (an uninitialized 0 would make the very first
        // check see an absurd silence and cut the session off before the startup budget elapses).
        silentSinceNanos = lastSeenStamp
        job = scope.launch {
            delay(checkIntervalMs.milliseconds)
            // Stops at the first stall: recovery is the caller's job, and it restarts the watchdog
            while (isActive && check()) {
                delay(checkIntervalMs.milliseconds)
            }
        }
    }

    /** Stop watchdog. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * One pass over [stallThresholdMs] / [startupThresholdMs]. Returns false once [onStall] has been called, which ends
     * the watchdog's polling loop.
     */
    private fun check(): Boolean {
        return runCatching {
            if (!isSessionActive()) {
                silentSinceNanos = System.nanoTime()
                return true
            }
            val stamp = getLastFrameNanos()
            // Only a strictly NEWER stamp counts as "a frame landed". start() resets the baseline to
            // NOW, so a stale stamp from before the session (or an unchanged one) must NOT flip the
            // watchdog into the long stall budget — otherwise a session that never delivers a first
            // frame is never cut off on the startup budget.
            if (stamp > lastSeenStamp) {
                lastSeenStamp = stamp
                deliveredAFrame = true
                silentSinceNanos = stamp
            }
            val silenceMs = (System.nanoTime() - silentSinceNanos) / 1_000_000L
            if (silenceMs < (if (deliveredAFrame) stallThresholdMs else startupThresholdMs)) return true
            val what = if (deliveredAFrame) "No frames for $silenceMs ms" else "No first frame after $silenceMs ms"
            logger.warn("$debugLabel $what. Restarting...")
            onStall()
            false
        }.getOrDefault(true)
    }
}
