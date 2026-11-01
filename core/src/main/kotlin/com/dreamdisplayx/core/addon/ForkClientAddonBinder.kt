package com.dreamdisplayx.core.addon

import com.dreamdisplayx.api.addon.ForkAddonContext
import com.dreamdisplayx.api.addon.OnBrightnessChanged
import com.dreamdisplayx.api.addon.OnEnded
import com.dreamdisplayx.api.addon.OnFirstFrame
import com.dreamdisplayx.api.addon.OnPlaybackPosition
import com.dreamdisplayx.api.addon.OnQualityChanged
import com.dreamdisplayx.api.addon.OnStateChanged
import com.dreamdisplayx.api.addon.OnVolumeChanged
import com.dreamdisplayx.api.display.event.DisplayEvent
import com.dreamdisplayx.api.display.model.Display
import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.display.model.property.DisplayState
import com.dreamdisplayx.api.display.service.DisplayService
import com.dreamdisplayx.api.media.model.VideoQuality
import com.dreamdisplays.core.addon.AddonMethod
import com.dreamdisplays.core.addon.findAnnotated
import org.slf4j.LoggerFactory

/**
 * Binds the fork-only handlers ([OnQualityChanged], [OnVolumeChanged], [OnBrightnessChanged], [OnPlaybackPosition],
 * [OnStateChanged], [OnFirstFrame], [OnEnded]) on the client.
 *
 * All of them read from one shared subscription on the [DisplayService] event stream: a display's settings live in
 * `DisplaySettings`, and its playback state in `DisplayState`, so a single `SettingsChanged` / `StateChanged` event
 * carries everything the seven handlers need. Adding a handler therefore costs no extra listener.
 *
 * Handlers receive the *live* [Display] re-read from the service at dispatch time, so a handler that changes a
 * setting sees the new value on its next invocation instead of a stale snapshot.
 *
 * @since 1.10.x
 */
class ForkClientAddonBinder(private val displays: () -> DisplayService) : ForkAddonBinder {

    private val logger = LoggerFactory.getLogger(ForkClientAddonBinder::class.java)

    override fun bind(addon: Any, context: ForkAddonContext) {
        val quality = findAnnotated(addon, OnQualityChanged::class.java)
        val volume = findAnnotated(addon, OnVolumeChanged::class.java)
        val brightness = findAnnotated(addon, OnBrightnessChanged::class.java)
        val position = findAnnotated(addon, OnPlaybackPosition::class.java)
        val state = findAnnotated(addon, OnStateChanged::class.java)
        val firstFrame = findAnnotated(addon, OnFirstFrame::class.java)
        val ended = findAnnotated(addon, OnEnded::class.java)

        val anyHandler = quality.isEmpty() && volume.isEmpty() && brightness.isEmpty() &&
            position.isEmpty() && state.isEmpty() && firstFrame.isEmpty() && ended.isEmpty()
        if (anyHandler) return

        quality.forEach { it.requireParameters("OnQualityChanged", Display::class.java, VideoQuality::class.java) }
        volume.forEach { it.requireParameters("OnVolumeChanged", Display::class.java, Float::class.java) }
        brightness.forEach { it.requireParameters("OnBrightnessChanged", Display::class.java, Float::class.java) }
        position.forEach { it.requireParameters("OnPlaybackPosition", Display::class.java, Long::class.java) }
        state.forEach { it.requireParameters("OnStateChanged", Display::class.java, DisplayState::class.java) }
        firstFrame.forEach { it.requireParameters("OnFirstFrame", Display::class.java) }
        ended.forEach { it.requireParameters("OnEnded", Display::class.java) }

        // Displays seen in `Playing` already, so `@OnFirstFrame` fires once per display rather than on every seek.
        val seenFirstFrame = HashSet<DisplayId>()

        context.own(displays().on { event ->
            when (event) {
                is DisplayEvent.SettingsChanged -> dispatchSettings(event, quality, volume, brightness)
                is DisplayEvent.StateChanged -> dispatchState(
                    event, position, state, firstFrame, ended, seenFirstFrame,
                )
                is DisplayEvent.Removed -> seenFirstFrame -= event.displayId
                else -> Unit
            }
        })
    }

    /** Fans a settings change out to the quality / volume / brightness handlers that were registered. */
    private fun dispatchSettings(
        event: DisplayEvent.SettingsChanged,
        quality: List<AddonMethod>,
        volume: List<AddonMethod>,
        brightness: List<AddonMethod>,
    ) {
        val display = current(event.displayId) ?: return
        dispatch(quality) { it.call(display, event.current.quality, event.previous.quality) }
        dispatch(volume) { it.call(display, event.current.volume, event.previous.volume) }
        dispatch(brightness) { it.call(display, event.current.brightness, event.previous.brightness) }
    }
    /** Fans a state change out to the position / state / first-frame / ended handlers that were registered. */
    private fun dispatchState(
        event: DisplayEvent.StateChanged,
        position: List<AddonMethod>,
        state: List<AddonMethod>,
        firstFrame: List<AddonMethod>,
        ended: List<AddonMethod>,
        seenFirstFrame: MutableSet<DisplayId>,
    ) {
        val display = current(event.displayId) ?: return
        val now = event.current
        val before = event.previous

        dispatch(state) { it.call(display, now) }

        if (now is DisplayState.Playing && seenFirstFrame.add(event.displayId)) {
            dispatch(firstFrame) { it.call(display) }
        }
        if (now is DisplayState.Playing && before is DisplayState.Playing && now.positionMs != before.positionMs) {
            dispatch(position) { it.call(display, now.positionMs, before.positionMs) }
        }
        // "Ended" is a pause/stop that follows actual playback; a display that never played has not ended.
        if (now !is DisplayState.Playing && before is DisplayState.Playing) {
            dispatch(ended) { it.call(display) }
        }
    }

    /** Re-reads the display so handlers see the live snapshot rather than the one carried by the event. */
    private fun current(id: DisplayId): Display? = runCatching { displays().getDisplay(id) }.getOrNull()

    /** Runs every handler of one annotation, isolating a throwing handler from the rest. */
    private inline fun dispatch(handlers: List<AddonMethod>, block: (AddonMethod) -> Unit) {
        for (method in handlers) {
            try {
                block(method)
            } catch (t: Throwable) {
                logger.error("Addon handler {} threw.", method.description, t)
            }
        }
    }
}