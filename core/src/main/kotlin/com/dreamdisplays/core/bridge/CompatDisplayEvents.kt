package com.dreamdisplays.core.bridge

import com.dreamdisplays.api.DisplayLifecycle
import com.dreamdisplays.api.display.event.DisplayEvent as CompatDisplayEvent
import com.dreamdisplays.api.display.event.DisplayEvents
import com.dreamdisplays.api.display.model.property.DisplayId as CompatDisplayId
import com.dreamdisplays.api.display.model.property.DisplayState as CompatDisplayState
import com.dreamdisplays.api.media.model.DreamMediaException as CompatMediaException
import com.dreamdisplayx.api.display.event.DisplayEvent as XDisplayEvent
import com.dreamdisplayx.api.display.service.DisplayService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * [DisplayEvents] for one display, translated from the fork's single `DisplayService.on` stream.
 *
 * One underlying fork subscription is shared by every listener this adapter hands out; each listener keeps its own
 * type filter. The underlying subscription is dropped as soon as the last listener unsubscribes, so an idle adapter
 * costs nothing.
 *
 * The fork has no `CustomDataChanged` event, so that upstream handler is accepted and never fires: an addon written
 * against upstream still compiles and runs, it simply receives no data-change notifications.
 *
 * @since 1.10.x
 */
internal class CompatDisplayEvents(
    private val service: DisplayService,
    private val id: CompatDisplayId,
) : DisplayEvents {

    private val lock = Any()

    /** Live listeners keyed by the event class they want, or `null` for "every event of this display". */
    private val listeners = CopyOnWriteArrayList<Subscription>()

    /** The single fork subscription backing every listener above, or `null` when nobody listens. */
    private var backing: AutoCloseable? = null

    override fun <T : CompatDisplayEvent> on(type: Class<T>, listener: Consumer<T>): AutoCloseable =
        subscribe(type) { event -> if (type.isInstance(event)) listener.accept(type.cast(event)) }

    /** Every event of this display, the shape the deprecated `Display.on` uses. */
    fun onAny(listener: Consumer<CompatDisplayEvent>): AutoCloseable =
        subscribe(null) { event -> listener.accept(event) }

    override fun lifecycle(configure: Consumer<DisplayLifecycle>): AutoCloseable {
        val recording = RecordingLifecycle()
        configure.accept(recording)
        val handles = ArrayList<AutoCloseable>(recording.handlers.size)
        recording.handlers.forEach { (predicate, action) ->
            handles += subscribe(null) { event -> if (predicate(event)) action(event) }
        }
        return AutoCloseable { handles.forEach { runCatching { it.close() } } }
    }

    private fun subscribe(
        type: Class<out CompatDisplayEvent>?,
        action: (CompatDisplayEvent) -> Unit,
    ): AutoCloseable {
        val subscription = Subscription(type, action)
        listeners += subscription
        ensureBacking()
        return AutoCloseable {
            listeners -= subscription
            dropBackingIfIdle()
        }
    }

    private fun ensureBacking() {
        synchronized(lock) {
            if (backing != null) return
            backing = service.on(::dispatch)
        }
    }

    private fun dropBackingIfIdle() {
        synchronized(lock) {
            if (listeners.isNotEmpty()) return
            backing?.let { runCatching { it.close() } }
            backing = null
        }
    }

    /** Translates one fork event for this display and fans it out, isolating listener failures. */
    private fun dispatch(event: XDisplayEvent) {
        val translated = translate(event) ?: return
        listeners.forEach { subscription ->
            if (subscription.type != null && !subscription.type.isInstance(translated)) return@forEach
            runCatching { subscription.action(translated) }
        }
    }

    /** Maps a fork event of this display to the compatible event shape, or `null` for other displays' events. */
    private fun translate(event: XDisplayEvent): CompatDisplayEvent? {
        if (event.displayId.toCompat() != id) return null
        return when (event) {
            is XDisplayEvent.Created -> CompatDisplayEvent.Created(id, event.display.toCompatInfo())
            is XDisplayEvent.Removed -> CompatDisplayEvent.Removed(id)
            is XDisplayEvent.SettingsChanged ->
                CompatDisplayEvent.SettingsChanged(id, event.previous.toCompat(), event.current.toCompat())
            is XDisplayEvent.StateChanged ->
                CompatDisplayEvent.StateChanged(id, event.previous.toCompat(), event.current.toCompat())
            is XDisplayEvent.UrlChanged -> CompatDisplayEvent.UrlChanged(id, event.url)
            is XDisplayEvent.MediaError -> CompatDisplayEvent.MediaError(id, event.cause.toCompat())
        }
    }

    private class Subscription(
        val type: Class<out CompatDisplayEvent>?,
        val action: (CompatDisplayEvent) -> Unit,
    )

    /** Collects the handlers an addon declared in one `lifecycle { }` block. */
    private class RecordingLifecycle : DisplayLifecycle {
        val handlers = CopyOnWriteArrayList<Pair<(CompatDisplayEvent) -> Boolean, (CompatDisplayEvent) -> Unit>>()

        private fun add(
            predicate: (CompatDisplayEvent) -> Boolean,
            action: (CompatDisplayEvent) -> Unit,
        ) {
            handlers += predicate to action
        }

        override fun onPlaying(action: Consumer<CompatDisplayState.Playing>) = add(
            { it is CompatDisplayEvent.StateChanged && it.current is CompatDisplayState.Playing },
        ) { event ->
            val changed = event as CompatDisplayEvent.StateChanged
            @Suppress("UNCHECKED_CAST")
            action.accept(changed.current as CompatDisplayState.Playing)
        }

        override fun onPaused(action: Consumer<CompatDisplayState.Paused>) = add(
            { it is CompatDisplayEvent.StateChanged && it.current is CompatDisplayState.Paused },
        ) { event ->
            val changed = event as CompatDisplayEvent.StateChanged
            @Suppress("UNCHECKED_CAST")
            action.accept(changed.current as CompatDisplayState.Paused)
        }

        override fun onBuffering(action: Consumer<CompatDisplayState.Buffering>) = add(
            { it is CompatDisplayEvent.StateChanged && it.current is CompatDisplayState.Buffering },
        ) { event ->
            val changed = event as CompatDisplayEvent.StateChanged
            @Suppress("UNCHECKED_CAST")
            action.accept(changed.current as CompatDisplayState.Buffering)
        }

        override fun onStopped(action: Runnable) = add(
            { it is CompatDisplayEvent.StateChanged && it.current == CompatDisplayState.Stopped },
        ) { action.run() }

        override fun onFailed(action: Consumer<CompatMediaException>) = add(
            { it is CompatDisplayEvent.MediaError || (it is CompatDisplayEvent.StateChanged && it.current is CompatDisplayState.Failed) },
        ) { event ->
            when (event) {
                is CompatDisplayEvent.MediaError -> action.accept(event.cause)
                is CompatDisplayEvent.StateChanged -> {
                    val failed = event.current as CompatDisplayState.Failed
                    action.accept(failed.cause)
                }

                else -> Unit
            }
        }

        override fun onUrlChanged(action: Consumer<String?>) = add(
            { it is CompatDisplayEvent.UrlChanged },
        ) { event -> action.accept((event as CompatDisplayEvent.UrlChanged).url) }

        /** The fork publishes no custom-data changes, so this handler is recorded but never invoked. */
        override fun onDataChanged(action: Consumer<Set<String>>) = Unit

        override fun onRemoved(action: Runnable) = add(
            { it is CompatDisplayEvent.Removed },
        ) { action.run() }
    }
}