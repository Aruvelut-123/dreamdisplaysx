package com.dreamdisplayx.core.addon

import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.display.data.MutableDisplayCustomData
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.server.ServerDisplay
import com.dreamdisplays.api.server.ServerDisplayEvent
import com.dreamdisplays.api.server.ServerDisplays
import com.dreamdisplays.api.server.ServerEvents
import java.util.UUID
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * The server half of the addon API, expressed without a single Minecraft or loader type.
 *
 * The server module is the only place that knows about `DisplayManager`, `PaperDisplayData` and the vanilla
 * equivalents, so it supplies a [ServerAddonHost] and this package turns it into upstream's [ServerDisplay] /
 * [ServerDisplays] surface. That keeps the adapters compilable in `:core` — which has no loader dependency at all —
 * while the loader-specific half stays where it must.
 *
 * A host that cannot honour an operation returns [CommandResult.REFUSED] rather than pretending it worked; an addon
 * that gets `OK` back may rely on the change having happened.
 *
 * @since 1.10.x
 */
interface ServerAddonHost {
    /** Every display on the server right now, newest snapshot. */
    fun snapshot(): List<ServerAddonDisplay>

    /** Publishes a display, persists it and notifies nearby players. */
    fun create(owner: UUID, world: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, facing: DisplayFacing): ServerAddonDisplay?

    /** Removes the display. */
    fun delete(id: UUID): CommandResult

    /** Changes the video link after the server's own gates. */
    fun setUrl(id: UUID, url: String, lang: String): CommandResult

    /** Changes the access level. */
    fun setAccess(id: UUID, access: DisplayAccess): CommandResult

    /** Moves the display to a new box. */
    fun moveTo(id: UUID, world: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, facing: DisplayFacing): CommandResult

    /** Subscribes to display events; called once per addon host. */
    fun listen(listener: Consumer<ServerDisplayEvent>): AutoCloseable

    /**
     * The server-wide veto registry, or `null` when this side has none.
     *
     * An addon's veto lands in the **same** registry the built-in create / delete / set-video gates consult, so an
     * addon refusal and a plugin refusal are indistinguishable to the call site — which is the point.
     */
    fun vetoes(): com.dreamdisplayx.api.display.event.DisplayVetoes?
}

/**
 * The immutable snapshot of one display a [ServerAddonHost] hands out.
 *
 * Mirrors the fields upstream's `ServerDisplay` exposes. Positions are absolute block corners so a host does not have
 * to leak its own `Location` / `BlockPos` types through this interface.
 */
class ServerAddonDisplay(
    /** Persistent identifier. */
    val id: UUID,
    /** The player who created it. */
    val ownerId: UUID,
    /** World key the display stands in. */
    val world: String,
    /** Lowest corner, inclusive. */
    val x1: Int,
    /** Lowest corner, inclusive. */
    val y1: Int,
    /** Lowest corner, inclusive. */
    val z1: Int,
    /** Highest corner, inclusive. */
    val x2: Int,
    /** Highest corner, inclusive. */
    val y2: Int,
    /** Highest corner, inclusive. */
    val z2: Int,
    /** Facing of the screen. */
    val facing: DisplayFacing,
    /** Width in blocks. */
    val width: Int,
    /** Height in blocks. */
    val height: Int,
    /** Optional short alias. */
    val name: String?,
    /** Current video link, empty when none. */
    val url: String,
    /** Preferred audio language. */
    val lang: String,
    /** Persistent base playback mode. */
    val mode: PlaybackMode,
    /** Who may change it. */
    val access: DisplayAccess,
)

/** The place [this] display stands, in the flattening form a veto carries. */
fun ServerAddonDisplay.toVetoPosition(): com.dreamdisplayx.api.display.event.DisplayVeto.Position =
    com.dreamdisplayx.api.display.event.DisplayVeto.Position(
        world = world,
        x1 = x1, y1 = y1, z1 = z1,
        x2 = x2, y2 = y2, z2 = z2,
        facing = com.dreamdisplayx.api.display.model.property.DisplayFacing.fromByte(facing.byte),
    )

/**
 * One [ServerDisplay] view over a snapshot id.
 *
 * Everything is read through the host on each access, so a live display reflects a later change instead of the state it
 * had when the addon first saw it. A display that disappeared answers `isAlive == false` and refuses every mutation
 * with [CommandResult.STALE], which is the contract upstream documents.
 *
 * [fallback] is the snapshot taken immediately before the display was removed. Dispatch is synchronous, so during the
 * `Removed` event the row is still in the registry and reads come from it; the moment the caller drops the row, the same
 * view answers from [fallback] instead of collapsing to zeroes. An addon that keeps the view and re-reads it later still
 * sees the box it was told about. [isAlive] deliberately ignores [fallback] and asks the live registry, so a retained
 * view never claims a removed display exists.
 */
internal class ServerAddonDisplayView(
    private val host: ServerAddonHost,
    private val displayId: UUID,
    private val fallback: ServerAddonDisplay? = null,
) : ServerDisplay {
    private fun live(): ServerAddonDisplay? = host.snapshot().firstOrNull { it.id == displayId } ?: fallback

    override val id: UUID get() = displayId
    override val ownerId: UUID get() = live()?.ownerId ?: EMPTY.ownerId
    override val isAlive: Boolean get() = host.snapshot().any { it.id == displayId }
    override val world: String get() = live()?.world ?: EMPTY.world
    override val x: Double get() = ((live() ?: EMPTY).let { (it.x1 + it.x2) / 2.0 })
    override val y: Double get() = ((live() ?: EMPTY).let { (it.y1 + it.y2) / 2.0 })
    override val z: Double get() = ((live() ?: EMPTY).let { (it.z1 + it.z2) / 2.0 })
    override val width: Int get() = live()?.width ?: EMPTY.width
    override val height: Int get() = live()?.height ?: EMPTY.height
    override val name: String? get() = live()?.name
    override val url: String get() = live()?.url ?: ""
    override val lang: String get() = live()?.lang ?: ""
    override val mode: PlaybackMode get() = live()?.mode ?: PlaybackMode.LOCAL
    override val access: DisplayAccess get() = live()?.access ?: DisplayAccess.DEFAULT

    override val events: ServerEvents
        get() = object : ServerEvents {
            override fun <T : ServerDisplayEvent> on(type: Class<T>, listener: Consumer<T>): AutoCloseable =
                host.listen { event ->
                    if (event.display.id == displayId && type.isInstance(event)) listener.accept(type.cast(event))
                }
        }

    /** The fork stores no addon payload per display, so every write is refused instead of silently dropped. */
    override val customData: MutableDisplayCustomData = NoCustomData

    override fun moveTo(world: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, facing: DisplayFacing): CommandResult =
        if (!isAlive) CommandResult.STALE else host.moveTo(displayId, world, x1, y1, z1, x2, y2, z2, facing)

    override fun moveTo(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, facing: DisplayFacing): CommandResult =
        moveTo(live()?.world ?: return CommandResult.STALE, x1, y1, z1, x2, y2, z2, facing)

    override fun setUrl(url: String, lang: String): CommandResult =
        if (!isAlive) CommandResult.STALE else host.setUrl(displayId, url, lang)

    override fun setAccess(access: DisplayAccess): CommandResult =
        if (!isAlive) CommandResult.STALE else host.setAccess(displayId, access)

    override fun delete(): CommandResult = if (!isAlive) CommandResult.STALE else host.delete(displayId)

    private companion object {
        /** Answers a read after the display is already gone; nothing here is ever observable as a real value. */
        val EMPTY = ServerAddonDisplay(
            id = UUID(0, 0), ownerId = UUID(0, 0), world = "",
            x1 = 0, y1 = 0, z1 = 0, x2 = 0, y2 = 0, z2 = 0,
            facing = DisplayFacing.NORTH, width = 0, height = 0,
            name = null, url = "", lang = "",
            mode = PlaybackMode.LOCAL, access = DisplayAccess.DEFAULT,
        )
    }
}

/**
 * Rejects every write.
 *
 * The fork has no per-display key/value store and no protocol field to carry one, so accepting a write here would
 * mean an addon believes its data is saved and shared when it is not. Refusing is the honest answer, and the
 * documentation says so.
 */
internal object NoCustomData : MutableDisplayCustomData {
    override val keys: Set<String> get() = emptySet()
    override fun get(key: String): ByteArray? = null
    override val isEmpty: Boolean get() = true
    override fun set(key: String, value: ByteArray): CommandResult = CommandResult.REFUSED
    override fun remove(key: String): CommandResult = CommandResult.REFUSED
    override fun clear(): CommandResult = CommandResult.REFUSED
}

/** A [ServerDisplays] view that re-reads the host on every access. */
internal class ServerAddonDisplaysView(private val host: ServerAddonHost) : ServerDisplays {
    override val size: Int get() = host.snapshot().size

    override fun get(id: UUID): ServerDisplay? = host.snapshot().firstOrNull { it.id == id }?.let { ServerAddonDisplayView(host, it.id) }

    override fun byName(name: String): ServerDisplay? =
        host.snapshot().firstOrNull { it.name != null && it.name.equals(name, ignoreCase = true) }
            ?.let { ServerAddonDisplayView(host, it.id) }

    override fun ownedBy(owner: UUID): ServerDisplays = filtered { it.ownerId == owner }

    override fun near(world: String, x: Double, y: Double, z: Double, radius: Double): ServerDisplays {
        val squared = radius * radius
        return filtered { d ->
            if (d.world != world) return@filtered false
            val dx = x - x.coerceIn(d.x1.toDouble(), (d.x2 + 1).toDouble())
            val dy = y - y.coerceIn(d.y1.toDouble(), (d.y2 + 1).toDouble())
            val dz = z - z.coerceIn(d.z1.toDouble(), (d.z2 + 1).toDouble())
            dx * dx + dy * dy + dz * dz <= squared
        }
    }

    override fun filter(predicate: Predicate<ServerDisplay>): ServerDisplays =
        filtered { predicate.test(ServerAddonDisplayView(host, it.id)) }

    private fun filtered(keep: (ServerAddonDisplay) -> Boolean): ServerDisplays =
        ServerAddonDisplaysFiltered(host) { host.snapshot().filter(keep) }

    override fun create(
        owner: UUID, world: String,
        x1: Int, y1: Int, z1: Int,
        x2: Int, y2: Int, z2: Int,
        facing: DisplayFacing,
    ): ServerDisplay? = host.create(owner, world, x1, y1, z1, x2, y2, z2, facing)
        ?.let { ServerAddonDisplayView(host, it.id) }

    override fun iterator(): Iterator<ServerDisplay> =
        host.snapshot().map { ServerAddonDisplayView(host, it.id) as ServerDisplay }.iterator()
}

/** A filtered [ServerDisplays]; the predicate ran when the view was made, the snapshot is re-read on every access. */
private class ServerAddonDisplaysFiltered(
    private val host: ServerAddonHost,
    private val rows: () -> List<ServerAddonDisplay>,
) : ServerDisplays {
    override val size: Int get() = rows().size
    override fun get(id: UUID): ServerDisplay? = rows().firstOrNull { it.id == id }?.let { ServerAddonDisplayView(host, it.id) }
    override fun byName(name: String): ServerDisplay? =
        rows().firstOrNull { it.name != null && it.name.equals(name, ignoreCase = true) }
            ?.let { ServerAddonDisplayView(host, it.id) }

    override fun ownedBy(owner: UUID): ServerDisplays = keep { it.ownerId == owner }

    override fun near(world: String, x: Double, y: Double, z: Double, radius: Double): ServerDisplays {
        val squared = radius * radius
        return keep { d ->
            if (d.world != world) return@keep false
            val dx = x - x.coerceIn(d.x1.toDouble(), (d.x2 + 1).toDouble())
            val dy = y - y.coerceIn(d.y1.toDouble(), (d.y2 + 1).toDouble())
            val dz = z - z.coerceIn(d.z1.toDouble(), (d.z2 + 1).toDouble())
            dx * dx + dy * dy + dz * dz <= squared
        }
    }

    override fun filter(predicate: Predicate<ServerDisplay>): ServerDisplays =
        keep { predicate.test(ServerAddonDisplayView(host, it.id)) }

    private fun keep(extra: (ServerAddonDisplay) -> Boolean): ServerDisplays =
        ServerAddonDisplaysFiltered(host) { rows().filter(extra) }

    override fun create(
        owner: UUID, world: String,
        x1: Int, y1: Int, z1: Int,
        x2: Int, y2: Int, z2: Int,
        facing: DisplayFacing,
    ): ServerDisplay? = host.create(owner, world, x1, y1, z1, x2, y2, z2, facing)
        ?.let { ServerAddonDisplayView(host, it.id) }

    override fun iterator(): Iterator<ServerDisplay> =
        rows().map { ServerAddonDisplayView(host, it.id) as ServerDisplay }.iterator()
}