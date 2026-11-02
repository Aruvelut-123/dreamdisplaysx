package com.dreamdisplayx.core.addon

import com.dreamdisplays.api.addon.AddonContext
import com.dreamdisplays.core.addon.AddonBinder
import com.dreamdisplays.core.addon.AddonMethod
import com.dreamdisplays.core.addon.findAnnotated
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
import org.slf4j.LoggerFactory

/**
 * Binds this fork's extra handlers onto an upstream `@Addon` class.
 *
 * The addon is registered through `com.dreamdisplays.api.DreamDisplays.register(...)` exactly like an upstream one;
 * this binder is simply one more entry in the host's binder list. An addon that carries none of the annotations below
 * subscribes to nothing and costs one reflective scan.
 *
 * All seven handlers share a single [DisplayService.on] subscription: one listener, one place to close. The alternative
 * — one subscription per handler — would multiply the dispatch work by the number of annotated methods.
 *
 * `@OnFirstFrame` fires on the first transition into [DisplayState.Playing] of a display and not again until that
 * display is removed, because "first frame" is a property of the load, not of the state. `@OnEnded` is the opposite
 * edge: leaving [DisplayState.Playing] without being stopped.
 */
class ForkHandlersBinder(private val displays: () -> DisplayService) : AddonBinder {
    private val logger = LoggerFactory.getLogger(ForkHandlersBinder::class.java)

    override fun bind(addon: Any, context: AddonContext) {
        val quality = findAnnotated(addon, OnQualityChanged::class.java)
        val volume = findAnnotated(addon, OnVolumeChanged::class.java)
        val brightness = findAnnotated(addon, OnBrightnessChanged::class.java)
        val position = findAnnotated(addon, OnPlaybackPosition::class.java)
        val state = findAnnotated(addon, OnStateChanged::class.java)
        val firstFrame = findAnnotated(addon, OnFirstFrame::class.java)
        val ended = findAnnotated(addon, OnEnded::class.java)

        quality.forEach { it.requireParameters("OnQualityChanged", Display::class.java, VideoQuality::class.java, VideoQuality::class.java) }
        volume.forEach { it.requireParameters("OnVolumeChanged", Display::class.java, Float::class.java, Float::class.java) }
        brightness.forEach { it.requireParameters("OnBrightnessChanged", Display::class.java, Float::class.java, Float::class.java) }
        position.forEach { it.requireParameters("OnPlaybackPosition", Display::class.java, Long::class.java, Long::class.java) }
        state.forEach { it.requireParameters("OnStateChanged", Display::class.java, DisplayState::class.java) }
        firstFrame.forEach { it.requireParameters("OnFirstFrame", Display::class.java) }
        ended.forEach { it.requireParameters("OnEnded", Display::class.java) }

        val wanted = quality + volume + brightness + position + state + firstFrame + ended
        if (wanted.isEmpty()) return

        val playingSince = HashMap<DisplayId, Long>()

        context.own(displays().on { event ->
            when (event) {
                is DisplayEvent.Created -> Unit

                is DisplayEvent.Removed -> playingSince.remove(event.displayId)

                is DisplayEvent.SettingsChanged -> {
                    val display = current(event.displayId) ?: return@on
                    val before = event.previous
                    val now = event.current
                    if (before.quality != now.quality) {
                        quality.forEach { safely(it) { it.call(display, now.quality, before.quality) } }
                    }
                    if (before.volume != now.volume) {
                        volume.forEach { safely(it) { it.call(display, now.volume, before.volume) } }
                    }
                    if (before.brightness != now.brightness) {
                        brightness.forEach { safely(it) { it.call(display, now.brightness, before.brightness) } }
                    }
                }

                is DisplayEvent.StateChanged -> {
                    val display = current(event.displayId) ?: return@on
                    state.forEach { safely(it) { it.call(display, event.current) } }
                    val wasPlaying = event.previous is DisplayState.Playing
                    val isPlaying = event.current is DisplayState.Playing
                    if (isPlaying && !wasPlaying) {
                        if (playingSince.put(event.displayId, System.nanoTime()) == null) {
                            firstFrame.forEach { safely(it) { it.call(display) } }
                        }
                    } else if (isPlaying) {
                        val seen = playingSince[event.displayId]
                        if (seen != null) {
                            val now = System.nanoTime()
                            val elapsedMs = (now - seen) / 1_000_000
                            position.forEach { safely(it) { it.call(display, elapsedMs, elapsedMs) } }
                        }
                    } else if (wasPlaying) {
                        playingSince.remove(event.displayId)
                        if (event.current !is DisplayState.Paused) {
                            ended.forEach { safely(it) { it.call(display) } }
                        }
                    }
                }

                is DisplayEvent.UrlChanged, is DisplayEvent.MediaError -> Unit
            }
        })
    }

    /** Re-reads the live snapshot so a handler always sees current settings, not the event's copy. */
    private fun current(id: DisplayId): Display? = runCatching { displays().getDisplay(id) }.getOrNull()

    private fun safely(method: AddonMethod, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error("Addon handler {} threw.", method.description, t)
        }
    }
}