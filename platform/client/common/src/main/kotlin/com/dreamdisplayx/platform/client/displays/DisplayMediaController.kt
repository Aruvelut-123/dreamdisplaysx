package com.dreamdisplayx.platform.client.displays

import com.dreamdisplayx.api.media.service.keys.MediaServices
import com.dreamdisplayx.api.media.audio.service.keys.AudioAcousticsServices
import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.core.protocol.common.packets.ReportDuration
import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.player.platform.DisplayPlaybackHost
import com.dreamdisplayx.platform.client.player.platform.DreamPlaybackEnvironment
import com.dreamdisplayx.platform.client.storage.WatchedVideoStore
import kotlinx.atomicfu.atomic
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

/**
 * Owns the media / player lifecycle for a single [DisplayScreen]: swapping in a fresh [MediaPlayer] on
 * URL change, generation-guarding async init callbacks against stale players, applying the screen's
 * saved state on start, and teardown.
 */
internal class DisplayMediaController(private val screen: DisplayScreen) {
    private companion object {
        val LOG = LoggerFactory.getLogger("DreamDisplaysX/DisplayMediaController")
    }
    /** Generation counter for async callbacks. */
    private val generation = atomic(0L)

    /** The active media player, or null between videos and after [shutdown]. */
    @Volatile
    var player: MediaPlayer? = null; private set

    /** True once [start] has applied the screen's initial state to the current player. */
    var videoStarted: Boolean = false; private set

    /** Current player generation; bumped on every swap so stale async callbacks can be detected. */
    val generationNow: Long get() = generation.value

    /**
     * Serial executor for player swaps. Every swap (old-player teardown + replacement build) runs
     * here one after another, so a second [load] landing while a teardown is in flight queues
     * behind it instead of building a replacement over a still-live native player. A single thread
     * guarantees at most one native player exists per display at any instant.
     */
    private val swapExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DisplayMediaController-swap").apply { isDaemon = true }
    }

    /**
     * Stops any current player, creates a fresh [MediaPlayer] for [videoUrl], wires the texture and
     * popout sinks, and defers [start] until the player reports initialized. When
     * [preservePausedState] is true the screen's current paused state is reapplied after start.
     */
    fun load(videoUrl: String, lang: String, preservePausedState: Boolean) {
        if (swapExecutor.isShutdown) return // Unregister is final; late network packets must not resurrect a player.
        if (videoUrl == "") return
        // DisplayInfo can be rebroadcast while the server is syncing. Do not tear down and recreate
        // a healthy player for an identical URL/track: doing so resets playback to the first frame,
        // and repeated packets can otherwise create a player storm and exhaust decoder buffers.
        if (player != null && screen.videoUrl == videoUrl && screen.lang == lang && !screen.errored) return

        DreamServices.registry.getOrNull(MediaServices.RESOLVER_REGISTRY)?.prefetch(MediaSource.from(videoUrl))

        val expected = generation.incrementAndGet()
        // Capture the old player HERE on the caller's thread so this specific swap owns its
        // teardown. Rapid consecutive loads each enqueue their own task with their own captured
        // old player; only the newest generation ever builds, but every captured old player is
        // still stopped exactly once (MediaPlayer.stop() is idempotent via its terminated flag).
        val oldPlayer = player
        player = null
        videoStarted = false
        screen.mediaError = null
        screen.timelineFollower.reset()
        // Android: never stack a second native libvlc player on the same display while the previous
        // one is still tearing down. VLC-Android's jni TLS destructor (jni_detach_thread) runs when a
        // VLC worker thread exits and dereferences thread-local state; if the previous player's
        // release already freed that state, the thread exit crashes in pthread_key_clean_all
        // (SIGSEGV at libvlc.so+0xef7418, observed with 3 concurrent players on one display id).
        // The old player must finish its stop() before the new one is constructed.
        //
        // IMPORTANT: this swap can be reached synchronously from libvlc's native event thread
        // (EOS -> host.onPlaybackEnded -> deactivateFullscreen -> loadVideo -> load). Blocking here
        // on awaitStopped() would deadlock: the old player's own teardown is queued on the same
        // event thread via fireStreamEnd -> handleStreamEnd, so it can never complete while we
        // wait. So we only enqueue the swap here; [drainSwapTask] runs the old-player stop + wait +
        // replacement build on the serial [swapExecutor], letting the native event thread return
        // immediately. The single worker thread also serializes rapid consecutive loads (e.g.
        // network thread racing an in-flight teardown), so two native players can never overlap on
        // this display.
        swapExecutor.execute {
            drainSwapTask(oldPlayer, videoUrl, lang, preservePausedState, expected)
        }
    }

    /**
     * Runs on [swapExecutor]: stops this task's captured [oldPlayer] (idempotent — a newer task may
     * already have stopped it) and waits for its teardown, then builds the replacement — but only
     * when this request is still the newest generation and no player was installed meanwhile. An
     * overtaken task therefore never resurrects an orphaned player; the executor's single thread
     * guarantees the old player is fully torn down before any replacement is constructed.
     */
    private fun drainSwapTask(
        oldPlayer: MediaPlayer?,
        videoUrl: String,
        lang: String,
        preservePausedState: Boolean,
        expected: Long,
    ) {
        oldPlayer?.let { old ->
            old.stop()
            if (!old.awaitStopped()) {
                LOG.warn("Old player did not stop within timeout; waiting more before creating replacement.")
                // Try to wait longer in a loop with a total cap to avoid stacking multiple native players.
                var waited = 0L
                val maxWaitMs = 15_000L // Total max wait time: 15 seconds
                while (!old.awaitStopped(5_000L)) { // Check every 5 seconds
                    waited += 5_000L
                    if (waited >= maxWaitMs) break // Stop waiting after total cap
                    LOG.warn("Still waiting for old player to stop (total: ${waited / 1000}s/${maxWaitMs / 1000}s)...")
                }
            }
            // The old player is fully torn down; only now build the replacement so we never
            // stack two native libvlc players on one display.
        }
        if (expected == generation.value && player == null) {
            buildReplacement(videoUrl, lang, preservePausedState, expected)
        }
    }

    /**
     * Builds the replacement [MediaPlayer] for [videoUrl] once the previous player is confirmed
     * stopped (or when there was no previous player). Guarded by [expected] so a stale swap that
     * raced a newer [load] (or [shutdown]) never resurrects an orphaned player.
     */
    private fun buildReplacement(
        videoUrl: String,
        lang: String,
        preservePausedState: Boolean,
        expected: Long,
    ) {
        if (expected != generation.value) return // A newer load/shutdown already took over.
        if (player != null) return // Another swap already installed its player.
        val screen = this.screen

        screen.onVideoSwapped(videoUrl, lang)
        // The video changed: drop the long-lived scrub extractor (and its cached thumbnails) for the
        // previous URL so the native libvlc player is destroyed; the next hover lazily recreates one.
        screen.previousVideoUrl?.let { com.dreamdisplayx.platform.client.render.ScrubPreview.release(it) }
        DisplayRegistry.recordScreen(screen)
        val shouldBePaused = preservePausedState && screen.paused
        if (screen.errored) return // The screen errored while we were waiting; do not install a player.
        val audioStage = DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.registerSource(screen.uuid)
        val newPlayer = MediaPlayer(
            videoUrl, lang, DisplayPlaybackHost(screen), DreamPlaybackEnvironment,
            screen.takeReplayBootstrap(videoUrl), audioStage,
        )
        player = newPlayer
        screen.timelineFollower.onPlayerCreated()
        // Set the effective volume (incl. distance) now, before the bridge prelude (which starts at
        // construction) becomes audible — otherwise its first moment plays at the un-attenuated level.
        screen.primeNewPlayerVolume(newPlayer)
        screen.prepareTextureDimensions()

        screen.attachPopout(newPlayer)
        newPlayer.setAmbientLightSink { buf, w, h, format ->
            val color = com.dreamdisplayx.platform.client.render.DynamicDisplayLights.sample(
                buf, w, h, format.bytesPerPixel,
            )
            screen.updateAmbientLightColor(color)
        }

        whenInitialized(expected) {
            start()
            if (shouldBePaused) {
                screen.paused = true
                player?.pause()
            }
            reportDurationIfNeeded()
        }

        Minecraft.getInstance().execute { screen.reloadTexture() }
    }

    /** Applies volume, brightness, stretch mode, and paused state to the player, then seeks to the saved position. */
    fun start() {
        val mp = player ?: return
        videoStarted = true
        (screen.videoUrl?.let(MediaSource::from) as? MediaSource.YouTube)?.let { WatchedVideoStore.markWatched(it.videoId) }
        screen.applyEffectiveVolume()
        mp.setBrightness(screen.brightness)
        mp.setStretchMode(screen.stretchMode)
        // By now the stream has resolved, so videoContentAspect is known. Re-allocate the GPU texture at
        // the video's native aspect (rather than the block aspect used during the pre-resolve sizing) so
        // the vout thread uploads native-size frames with a direct bulk copy and the GPU does the scaling
        // — otherwise every frame would be CPU-rescaled on the vout thread (the 10-20fps regression).
        Minecraft.getInstance().execute { screen.reloadTexture() }
        if (screen.paused) mp.pause() else {
            mp.play()
            screen.paused = false
        }
        // A replay-bootstrap player already resumes at the saved position; restoreSavedTime()'s
        // corrective seek would cold-restart the session and destroy the seamless replay -> live bridge.
        if (!mp.isResumingFromReplay()) screen.restoreSavedTime()
        DisplayRegistry.recordScreen(screen)
    }

    /** Reports the resolved media duration once, if this display's server clock needs it to loop. */
    private fun reportDurationIfNeeded() {
        val durationNanos = screen.mediaPlayerDurationNanos
        if (durationNanos <= 0L) return // 0 for live streams and any not-yet-resolved case.
        Initializer.sendPacket(ReportDuration(screen.uuid, durationNanos / 1_000_000L))
    }

    /** Runs [action] once the current player is initialized; guards against stale generations. */
    fun whenInitialized(action: () -> Unit) = whenInitialized(generation.value, action)

    /** Runs [action] when the player is initialized, only if [expectedGeneration] still matches (i.e. video hasn't changed). */
    private fun whenInitialized(expectedGeneration: Long, action: () -> Unit) {
        val mp = player ?: return
        mp.whenInitialized {
            if (expectedGeneration != generation.value) return@whenInitialized
            if (mp !== player) return@whenInitialized
            if (screen.errored) return@whenInitialized
            action()
        }
    }

    /** Detaches the current player and invalidates pending callbacks; returns it for final teardown. */
    fun shutdown(): MediaPlayer? {
        generation.incrementAndGet()
        videoStarted = false
        val current = player
        player = null
        // Release the serial swap thread so the controller (and its screen) can be GC'd — a live
        // executor thread is a GC root that would keep the whole display graph alive forever.
        // Tasks already queued still run: their generation guard turns the build into a no-op, and
        // stop() is idempotent, so queued old-player teardowns finish cleanly before the thread dies.
        swapExecutor.shutdown()
        return current
    }
}
