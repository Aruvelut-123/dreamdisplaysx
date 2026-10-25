package com.dreamdisplays.core.api

import com.dreamdisplays.api.Display
import com.dreamdisplays.api.Displays
import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.DisplayInfo
import com.dreamdisplays.core.services.DisplayService
import com.dreamdisplays.core.services.PlaybackService
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import java.util.function.Predicate

/** Lazy [Displays] view. */
internal class DefaultDisplays(
    private val displayService: DisplayService,
    private val playbackService: PlaybackService,
    private val owner: DefaultSession?,
    private val filters: List<(DisplayInfo) -> Boolean> = [],
) : Displays {
    override val size: Int get() = infos().size

    override fun iterator(): Iterator<Display> = infos().map(::wrap).iterator()

    override fun near(x: Double, y: Double, z: Double, radius: Double): Displays {
        val limit = radius * radius
        return narrowed { info ->
            val dx = info.bounds.x - x
            val dy = info.bounds.y - y
            val dz = info.bounds.z - z
            dx * dx + dy * dy + dz * dz <= limit
        }
    }

    override fun filter(predicate: Predicate<Display>): Displays = narrowed { predicate.test(wrap(it)) }

    override fun each(action: Consumer<Display>): AutoCloseable {
        val seen = ConcurrentHashMap.newKeySet<Any>()
        fun visit(info: DisplayInfo) {
            if (filters.all { it(info) } && seen.add(info.id)) action.accept(wrap(info))
        }
        val upstream = displayService.on { event ->
            when (event) {
                is DisplayEvent.Created -> visit(event.display)
                is DisplayEvent.Removed -> seen.remove(event.displayId)
                else -> Unit
            }
        }
        displayService.listDisplays().forEach(::visit)
        val subscription = Subscription { upstream.close() }
        owner?.own(subscription)
        return subscription
    }

    override fun play() = forEach { it.play() }
    override fun pause() = forEach { it.pause() }
    override fun stop() = forEach { it.stop() }

    private fun infos(): List<DisplayInfo> = displayService.listDisplays().filter { info -> filters.all { it(info) } }

    private fun narrowed(predicate: (DisplayInfo) -> Boolean): Displays =
        DefaultDisplays(displayService, playbackService, owner, filters + predicate)

    private fun wrap(info: DisplayInfo): Display = LiveDisplay(info.id, displayService, playbackService, owner)
}
