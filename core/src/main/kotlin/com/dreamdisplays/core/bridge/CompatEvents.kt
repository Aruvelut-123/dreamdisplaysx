package com.dreamdisplays.core.bridge

import com.dreamdisplays.api.Display
import com.dreamdisplays.api.Displays
import com.dreamdisplays.api.Session
import com.dreamdisplays.api.display.event.Events
import com.dreamdisplays.api.display.model.property.DisplayId as CompatDisplayId
import com.dreamdisplayx.api.display.event.DisplayEvent as XDisplayEvent
import com.dreamdisplayx.api.display.service.DisplayService
import com.dreamdisplayx.api.playback.service.PlaybackService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * Translates one fork display event into its compatible counterpart.
 *
 * `@since 1.10.x`
 */
internal fun XDisplayEvent.toCompatEvent(): com.dreamdisplays.api.display.event.DisplayEvent = when (this) {
    is XDisplayEvent.Created ->
        com.dreamdisplays.api.display.event.DisplayEvent.Created(displayId.toCompat(), display.toCompatInfo())

    is XDisplayEvent.Removed ->
        com.dreamdisplays.api.display.event.DisplayEvent.Removed(displayId.toCompat())

    is XDisplayEvent.SettingsChanged ->
        com.dreamdisplays.api.display.event.DisplayEvent.SettingsChanged(
            displayId.toCompat(),
            previous.toCompat(),
            current.toCompat(),
        )

    is XDisplayEvent.StateChanged ->
        com.dreamdisplays.api.display.event.DisplayEvent.StateChanged(
            displayId.toCompat(),
            previous.toCompat(),
            current.toCompat(),
        )

    is XDisplayEvent.UrlChanged ->
        com.dreamdisplays.api.display.event.DisplayEvent.UrlChanged(displayId.toCompat(), url)

    is XDisplayEvent.MediaError ->
        com.dreamdisplays.api.display.event.DisplayEvent.MediaError(displayId.toCompat(), cause.toCompat())
}

/**
 * Global [Events]: every display's events, filtered per listener.
 *
 * One fork subscription is shared by all listeners and dropped when the last one unsubscribes.
 *
 * @since 1.10.x
 */
internal class CompatEvents(
    private val service: DisplayService,
    private val playback: PlaybackService?,
) : Events {

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<Pair<Class<*>, Consumer<Any>>>()
    private var backing: AutoCloseable? = null

    override fun <T : com.dreamdisplays.api.display.event.DisplayEvent> on(
        type: Class<T>,
        listener: Consumer<T>,
    ): AutoCloseable {
        @Suppress("UNCHECKED_CAST")
        val entry = type as Class<*> to (Consumer<Any> { listener.accept(type.cast(it)) })
        listeners += entry
        ensureBacking()
        return AutoCloseable {
            listeners -= entry
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
            runCatching { backing?.close() }
            backing = null
        }
    }

    private fun dispatch(event: XDisplayEvent) {
        val translated = event.toCompatEvent()
        listeners.forEach { (type, listener) ->
            if (!type.isInstance(translated)) return@forEach
            runCatching { listener.accept(translated) }
        }
    }
}

/**
 * A [Session] over the fork registry: everything subscribed through it is closed together, newest first.
 *
 * @since 1.10.x
 */
internal class CompatSession(
    override val name: String,
    private val backend: ForkApiBackend,
) : Session {

    private val owned = CopyOnWriteArrayList<AutoCloseable>()

    @Volatile
    override var isClosed: Boolean = false
        private set

    override fun display(id: CompatDisplayId): Display? = backend.display(id)

    override val displays: Displays get() = backend.displays

    override val events: Events
        get() = object : Events {
            private val scoped = CompatEvents(backend.displayService, backend.playbackService)
            override fun <T : com.dreamdisplays.api.display.event.DisplayEvent> on(
                type: Class<T>,
                listener: Consumer<T>,
            ): AutoCloseable = own(scoped.on(type, listener))
        }

    override fun <T : AutoCloseable> own(resource: T): T {
        if (!isClosed) owned += resource
        return resource
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        owned.toList().asReversed().forEach { runCatching { it.close() } }
        owned.clear()
    }

    override fun toString(): String = "Session($name, closed=$isClosed)"
}