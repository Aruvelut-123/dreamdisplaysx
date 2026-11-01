package com.dreamdisplays.core.bridge

import com.dreamdisplays.api.Display
import com.dreamdisplays.api.Displays
import com.dreamdisplayx.api.display.event.DisplayEvent as XDisplayEvent
import com.dreamdisplayx.api.display.model.Display as XDisplay
import com.dreamdisplayx.api.display.service.DisplayService
import com.dreamdisplayx.api.playback.service.PlaybackService
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * A lazy [Displays] view over the fork's display registry.
 *
 * Like the upstream contract, the view is re-evaluated on every iteration, so displays that appear after the view
 * was created are included automatically. [forkFilter] narrows at the snapshot level (cheap, applied before
 * adaptation); predicates an addon passes to [filter] are applied to adapted [Display]s by [CompatFilteredDisplays].
 *
 * @since 1.10.x
 */
internal open class CompatDisplays(
    private val service: DisplayService,
    private val playback: PlaybackService?,
    private val forkFilter: (XDisplay) -> Boolean = { true },
) : Displays {

    /** Snapshot of the displays matching [forkFilter] right now. */
    internal fun snapshots(): List<XDisplay> = service.listDisplays().filter(forkFilter)

    /** Adapts one fork snapshot to the compatible surface. */
    internal fun adapt(snapshot: XDisplay): Display =
        CompatDisplay(service, playback, snapshot.id.toCompat())

    private fun matches(): List<Display> = snapshots().map(::adapt)

    override val size: Int get() = snapshots().size

    override fun iterator(): Iterator<Display> = matches().iterator()

    override fun near(x: Double, y: Double, z: Double, radius: Double): Displays {
        require(radius >= 0.0) { "Radius must not be negative, got $radius." }
        val squared = radius * radius
        return CompatDisplays(service, playback) { snapshot ->
            forkFilter(snapshot) && within(snapshot, x, y, z, squared)
        }
    }

    override fun filter(predicate: Predicate<Display>): Displays = CompatFilteredDisplays(this, predicate)

    override fun each(action: Consumer<Display>): AutoCloseable {
        // Replay what exists now, then follow `Created` events for anything that appears later.
        snapshots().forEach { runCatching { action.accept(adapt(it)) } }
        return service.on { event ->
            if (event !is XDisplayEvent.Created) return@on
            val snapshot = event.display
            if (!forkFilter(snapshot)) return@on
            runCatching { action.accept(adapt(snapshot)) }
        }
    }

    override fun play() = matches().forEach { it.play() }

    override fun pause() = matches().forEach { it.pause() }

    override fun stop() = matches().forEach { it.stop() }

    private fun within(bounds: XDisplay, x: Double, y: Double, z: Double, squaredRadius: Double): Boolean {
        val dx = bounds.bounds.x - x
        val dy = bounds.bounds.y - y
        val dz = bounds.bounds.z - z
        return dx * dx + dy * dy + dz * dz <= squaredRadius
    }
}

/**
 * A view with an addon-supplied predicate layered on top of [inner].
 *
 * The predicate receives adapted displays, so it is evaluated after adaptation; [near] and further [filter] calls
 * keep composing instead of replacing.
 *
 * @since 1.10.x
 */
internal class CompatFilteredDisplays(
    private val inner: CompatDisplays,
    private val predicate: Predicate<Display>,
) : Displays {

    private fun matches(): List<Display> = inner.snapshots().map(inner::adapt).filter(predicate::test)

    override val size: Int get() = matches().size

    override fun iterator(): Iterator<Display> = matches().iterator()

    override fun near(x: Double, y: Double, z: Double, radius: Double): Displays {
        require(radius >= 0.0) { "Radius must not be negative, got $radius." }
        val narrowed = inner.near(x, y, z, radius)
        // `inner.near` always builds a plain CompatDisplays, so the cast is exact.
        return CompatFilteredDisplays(narrowed as CompatDisplays, predicate)
    }

    override fun filter(predicate: Predicate<Display>): Displays =
        CompatFilteredDisplays(inner, this.predicate.and(predicate))

    override fun each(action: Consumer<Display>): AutoCloseable {
        matches().forEach { runCatching { action.accept(it) } }
        return inner.each { display -> if (predicate.test(display)) runCatching { action.accept(display) } }
    }

    override fun play() = matches().forEach { it.play() }

    override fun pause() = matches().forEach { it.pause() }

    override fun stop() = matches().forEach { it.stop() }
}