package com.dreamdisplays.core.api

import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.Display
import com.dreamdisplays.api.DisplayLifecycle
import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.DisplayInfo
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.model.property.DisplayState
import com.dreamdisplays.core.services.DisplayService
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.core.services.PlaybackService
import java.util.function.Consumer

/**
 * Light handle over a display ID. It holds no video or memory and resolves the display on every call, so a
 * removed display turns every command into [CommandResult.STALE] instead of an error.
 *
 * @since 1.10.x
 */
internal class LiveDisplay(
    override val id: DisplayId,
    private val displayService: DisplayService,
    private val playbackService: PlaybackService,
    private val owner: DefaultSession? = null,
) : Display {
    override val info: DisplayInfo? get() = displayService.getDisplay(id)
    override val isAlive: Boolean get() = info != null
    override val state: DisplayState get() = info?.state ?: DisplayState.Idle
    override val isPlaying: Boolean get() = state is DisplayState.Playing

    override var url: String?
        get() = info?.url
        set(value) { command { displayService.setUrl(id, value, null) } }

    override var volume: Float
        get() = info?.settings?.volume ?: 1f
        set(value) { command { playbackService.setVolume(id, value) } }

    override var brightness: Float
        get() = info?.settings?.brightness ?: 1f
        set(value) { command { playbackService.setBrightness(id, value) } }

    override var muted: Boolean
        get() = info?.settings?.muted ?: false
        set(value) { command { playbackService.mute(id, value) } }

    override var quality: VideoQuality
        get() = info?.settings?.quality ?: VideoQuality.Auto
        set(value) { command { playbackService.setQuality(id, value) } }

    override var mode: PlaybackMode
        get() = info?.mode ?: PlaybackMode.LOCAL
        set(value) { command { playbackService.setMode(id, value) } }

    override fun play() = command { playbackService.play(id) }
    override fun pause() = command { playbackService.pause(id) }
    override fun stop() = command { playbackService.stop(id) }
    override fun restart() = command { playbackService.restart(id) }
    override fun retry() = command { playbackService.retry(id) }
    override fun seek(positionMs: Long) = command { playbackService.seek(id, positionMs) }
    override fun seekBy(deltaMs: Long) = command { playbackService.seekRelative(id, deltaMs) }
    override fun setUrl(url: String?, lang: String?) = command { displayService.setUrl(id, url, lang) }
    override fun setAudioTrack(trackUrl: String) = command { playbackService.setAudioTrack(id, trackUrl) }
    override fun setSubtitles(lang: String?) = command { playbackService.setSubtitleTrack(id, lang) }
    override fun delete() = command { displayService.delete(id) }
    override fun report() = command { displayService.report(id) }

    override fun on(listener: Consumer<DisplayEvent>): AutoCloseable {
        lateinit var subscription: Subscription
        val upstream = displayService.on { event ->
            if (event.displayId != id || subscription.isClosed) return@on
            listener.accept(event)
            if (event is DisplayEvent.Removed) subscription.close()
        }
        subscription = Subscription { upstream.close() }
        owner?.own(subscription)
        return subscription
    }

    override fun watch(configure: Consumer<DisplayLifecycle>): AutoCloseable {
        val handlers = LifecycleHandlers().also { configure.accept(it) }
        return on { handlers.dispatch(it) }
    }

    private inline fun command(action: () -> Unit): CommandResult {
        if (!isAlive) return CommandResult.STALE
        action()
        return CommandResult.OK
    }

    override fun equals(other: Any?): Boolean = other is LiveDisplay && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "Display($id)"
}

private class LifecycleHandlers : DisplayLifecycle {
    private val playing = ArrayList<Consumer<DisplayState.Playing>>()
    private val paused = ArrayList<Consumer<DisplayState.Paused>>()
    private val buffering = ArrayList<Consumer<DisplayState.Buffering>>()
    private val stopped = ArrayList<Runnable>()
    private val failed = ArrayList<Consumer<com.dreamdisplays.api.media.model.DreamMediaException>>()
    private val urlChanged = ArrayList<Consumer<String?>>()
    private val removed = ArrayList<Runnable>()

    override fun onPlaying(action: Consumer<DisplayState.Playing>) { playing += action }
    override fun onPaused(action: Consumer<DisplayState.Paused>) { paused += action }
    override fun onBuffering(action: Consumer<DisplayState.Buffering>) { buffering += action }
    override fun onStopped(action: Runnable) { stopped += action }
    override fun onFailed(action: Consumer<com.dreamdisplays.api.media.model.DreamMediaException>) { failed += action }
    override fun onUrlChanged(action: Consumer<String?>) { urlChanged += action }
    override fun onRemoved(action: Runnable) { removed += action }

    /** Routes [event] to handlers; state handlers fire only when the kind of state changes, not on every tick. */
    fun dispatch(event: DisplayEvent) {
        when (event) {
            is DisplayEvent.StateChanged -> {
                if (event.previous::class == event.current::class) return
                when (val state = event.current) {
                    is DisplayState.Playing -> playing.forEach { it.accept(state) }
                    is DisplayState.Paused -> paused.forEach { it.accept(state) }
                    is DisplayState.Buffering -> buffering.forEach { it.accept(state) }
                    is DisplayState.Stopped -> stopped.forEach { it.run() }
                    is DisplayState.Failed -> failed.forEach { it.accept(state.cause) }
                    else -> Unit
                }
            }
            is DisplayEvent.MediaError -> failed.forEach { it.accept(event.cause) }
            is DisplayEvent.UrlChanged -> urlChanged.forEach { it.accept(event.url) }
            is DisplayEvent.Removed -> removed.forEach { it.run() }
            else -> Unit
        }
    }
}
