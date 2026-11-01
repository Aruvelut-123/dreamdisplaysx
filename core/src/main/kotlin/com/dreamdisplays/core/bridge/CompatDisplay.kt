package com.dreamdisplays.core.bridge

import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.Display
import com.dreamdisplays.api.DisplayLifecycle
import com.dreamdisplays.api.display.data.DisplayCustomData
import com.dreamdisplays.api.display.data.MapCustomData
import com.dreamdisplays.api.display.event.DisplayEvents
import com.dreamdisplays.api.display.model.DisplayInfo
import com.dreamdisplays.api.display.model.property.DisplayId as CompatDisplayId
import com.dreamdisplays.api.display.model.property.DisplayState as CompatDisplayState
import com.dreamdisplays.api.display.model.settings.DisplaySettings as CompatDisplaySettings
import com.dreamdisplays.api.media.model.VideoQuality as CompatVideoQuality
import com.dreamdisplays.api.playback.model.PlaybackMode as CompatPlaybackMode
import com.dreamdisplayx.api.display.model.Display as XDisplay
import com.dreamdisplayx.api.display.model.property.DisplayId as XDisplayId
import com.dreamdisplayx.api.display.service.DisplayService
import com.dreamdisplayx.api.playback.service.PlaybackService
import java.util.function.Consumer

/**
 * Adapts a fork display (`com.dreamdisplayx.api.display.model.Display`, a snapshot data class plus separate
 * command ports) to the upstream `com.dreamdisplays.api.Display` interface that addons were compiled against.
 *
 * The adapter holds no state of its own: every read re-queries [service], so an addon that keeps a `Display`
 * reference for a long time always sees current values and observes [isAlive] flipping to `false` after removal,
 * exactly as the upstream contract describes.
 *
 * @since 1.10.x
 */
class CompatDisplay internal constructor(
    private val service: DisplayService,
    private val playback: PlaybackService?,
    override val id: CompatDisplayId,
) : Display {

    private val forkId: XDisplayId get() = id.toFork()

    /** Latest snapshot, or `null` when the display no longer exists. */
    private fun snapshot(): XDisplay? = service.getDisplay(forkId)

    override val info: DisplayInfo?
        get() = snapshot()?.toCompatInfo()

    override val isAlive: Boolean
        get() = snapshot() != null

    override val state: CompatDisplayState
        get() = snapshot()?.state?.toCompat() ?: CompatDisplayState.Idle

    override val isPlaying: Boolean
        get() = state is CompatDisplayState.Playing

    /**
     * The fork has no cross-side custom-data channel, so addons see an empty, immutable view here rather than a
     * half-working one.
     */
    override val customData: DisplayCustomData
        get() = EMPTY_CUSTOM_DATA

    override var url: String?
        get() = snapshot()?.url
        set(value) {
            if (value == null && !isAlive) return
            service.setUrl(forkId, value)
        }

    private fun settings(): CompatDisplaySettings =
        snapshot()?.settings?.toCompat() ?: CompatDisplaySettings.DEFAULT

    /** Writes [block] over the current settings through the fork mutation port. */
    private fun update(block: CompatDisplaySettings.() -> CompatDisplaySettings) {
        val current = snapshot() ?: return
        service.updateSettings(forkId, current.settings.toCompat().block().toFork())
    }

    override var volume: Float
        get() = settings().volume
        set(value) = update { copy(volume = value.coerceIn(0f, 2f)) }

    override var brightness: Float
        get() = settings().brightness
        set(value) = update { copy(brightness = value.coerceIn(0f, 2f)) }

    override var muted: Boolean
        get() = settings().muted
        set(value) = update { copy(muted = value) }

    override var quality: CompatVideoQuality
        get() = settings().quality
        set(value) = update { copy(quality = value) }

    override var mode: CompatPlaybackMode
        get() = snapshot()?.mode?.toCompat() ?: CompatPlaybackMode.LOCAL
        set(value) {
            val port = playback ?: return
            if (!CompatPlaybackMode.isBaseMode(value)) return
            port.setMode(forkId, value.toFork())
        }

    override val events: DisplayEvents = CompatDisplayEvents(service, id)

    override fun play(): CommandResult = command { it.play(forkId) }

    override fun pause(): CommandResult = command { it.pause(forkId) }

    override fun stop(): CommandResult = command { it.stop(forkId) }

    override fun restart(): CommandResult = command { it.restart(forkId) }

    override fun retry(): CommandResult = command { it.retry(forkId) }

    override fun seek(positionMs: Long): CommandResult = command { it.seek(forkId, positionMs) }

    override fun seekBy(deltaMs: Long): CommandResult = command { it.seekRelative(forkId, deltaMs) }

    override fun setUrl(url: String?, lang: String?): CommandResult {
        if (!isAlive) return CommandResult.STALE
        service.setUrl(forkId, url, lang)
        return CommandResult.OK
    }

    override fun setAudioTrack(trackUrl: String): CommandResult = command { it.setAudioTrack(forkId, trackUrl) }

    override fun setSubtitles(lang: String?): CommandResult = command { it.setSubtitleTrack(forkId, lang) }

    override fun delete(): CommandResult {
        if (!isAlive) return CommandResult.STALE
        service.delete(forkId)
        return CommandResult.OK
    }

    override fun report(): CommandResult {
        if (!isAlive) return CommandResult.STALE
        service.report(forkId)
        return CommandResult.OK
    }

    @Deprecated(
        "Use events.on(type, listener).",
        ReplaceWith("events.on(DisplayEvent::class.java, listener)"),
    )
    override fun on(listener: Consumer<com.dreamdisplays.api.display.event.DisplayEvent>): AutoCloseable =
        (events as CompatDisplayEvents).onAny(listener)

    @Deprecated(
        "Use events.lifecycle(configure).",
        ReplaceWith("events.lifecycle(configure)"),
    )
    override fun watch(configure: Consumer<DisplayLifecycle>): AutoCloseable =
        (events as CompatDisplayEvents).lifecycle(configure)

    /** Runs a playback command, reporting [CommandResult.STALE] when the display is already gone. */
    private inline fun command(block: (PlaybackService) -> Unit): CommandResult {
        if (!isAlive) return CommandResult.STALE
        val port = playback ?: return CommandResult.REFUSED
        block(port)
        return CommandResult.OK
    }

    override fun toString(): String = "Display($id)"

    private companion object {
        /** Shared empty view so the property allocation does not show up in per-frame addon code. */
        val EMPTY_CUSTOM_DATA: DisplayCustomData = MapCustomData { emptyMap() }
    }
}