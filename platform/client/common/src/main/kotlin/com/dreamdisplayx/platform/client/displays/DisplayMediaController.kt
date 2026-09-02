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

/**
 * Owns the media / player lifecycle for a single [DisplayScreen]: swapping in a fresh [MediaPlayer] on
 * URL change, generation-guarding async init callbacks against stale players, applying the screen's
 * saved state on start, and teardown.
 */
internal class DisplayMediaController(private val screen: DisplayScreen) {
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
     * Stops any current player, creates a fresh [MediaPlayer] for [videoUrl], wires the texture and
     * popout sinks, and defers [start] until the player reports initialized. When
     * [preservePausedState] is true the screen's current paused state is reapplied after start.
     */
    fun load(videoUrl: String, lang: String, preservePausedState: Boolean) {
        if (videoUrl == "") return

        DreamServices.registry.getOrNull(MediaServices.RESOLVER_REGISTRY)?.prefetch(MediaSource.from(videoUrl))

        val expected = generation.incrementAndGet()
        val oldPlayer = player
        player = null
        videoStarted = false
        screen.mediaError = null
        screen.timelineFollower.reset()
        oldPlayer?.stop()
        // Android: never stack a second native libvlc player on the same display while the previous
        // one is still tearing down. VLC-Android's jni TLS destructor (jni_detach_thread) runs when a
        // VLC worker thread exits and dereferences thread-local state; if the previous player's
        // release already freed that state, the thread exit crashes in pthread_key_clean_all
        // (SIGSEGV at libvlc.so+0xef7418, observed with 3 concurrent players on one display id).
        // Wait for the old player's stop() to finish before constructing the new one.
        if (oldPlayer != null && !oldPlayer.awaitStopped()) {
            org.slf4j.LoggerFactory.getLogger("DreamDisplaysX/DisplayMediaController")
                .warn("Old player did not stop within timeout; creating replacement anyway.")
        }

        screen.onVideoSwapped(videoUrl, lang)
        // The video changed: drop the long-lived scrub extractor (and its cached thumbnails) for the
        // previous URL so the native libvlc player is destroyed; the next hover lazily recreates one.
        screen.previousVideoUrl?.let { com.dreamdisplayx.platform.client.render.ScrubPreview.release(it) }
        DisplayRegistry.recordScreen(screen)
        val shouldBePaused = preservePausedState && screen.paused
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
        return current
    }
}
