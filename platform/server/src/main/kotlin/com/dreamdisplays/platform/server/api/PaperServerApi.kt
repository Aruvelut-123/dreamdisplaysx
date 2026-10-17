package com.dreamdisplays.platform.server.api

import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.addon.AddonSide
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.display.model.property.DisplayRotation
import com.dreamdisplays.api.internal.ServerBackend
import com.dreamdisplays.api.internal.ServerHolder
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.server.ServerDisplay
import com.dreamdisplays.api.server.ServerDisplayEvent
import com.dreamdisplays.api.server.ServerDisplays
import com.dreamdisplays.core.addon.AddonManager
import com.dreamdisplays.core.addon.ServerAddonBinder
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.net.CustomMediaGate
import com.dreamdisplays.shared.policy.MediaUrlPolicy
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit
import org.bukkit.block.BlockFace
import org.bukkit.util.BoundingBox
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import java.util.function.Predicate
import kotlin.math.abs

/** `Paper` implementation of the public server API. */
@PaperOnly
object PaperServerApi : ServerBackend {
    private val listeners = CopyOnWriteArrayList<Consumer<ServerDisplayEvent>>()

    private val addons = AddonManager(AddonSide.SERVER, listOf(ServerAddonBinder { listener -> on(listener) }))

    override val displays: ServerDisplays get() = PaperServerDisplays()

    override fun on(listener: Consumer<ServerDisplayEvent>): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun registerAddon(addon: Any) = addons.register(addon)

    /**
     * Makes the API available and enables registered addons.
     *
     * Called once the plugin has finished loading.
     */
    fun start() {
        ServerHolder.attach(this)
        addons.start()
    }

    /**
     * Disables addons and withdraws the API.
     *
     * Called when the plugin shuts down.
     */
    fun stop() {
        addons.stop()
        ServerHolder.detach()
    }

    /** Tells subscribers that [data] was created. */
    fun created(data: PaperDisplayData) = publish { ServerDisplayEvent.Created(PaperServerDisplay(data)) }

    /** Tells subscribers that [data] was removed. */
    fun removed(data: PaperDisplayData) = publish { ServerDisplayEvent.Removed(PaperServerDisplay(data)) }

    /** Tells subscribers that the video link of [data] changed. */
    fun urlChanged(data: PaperDisplayData) = publish { ServerDisplayEvent.UrlChanged(PaperServerDisplay(data), data.url) }

    private inline fun publish(event: () -> ServerDisplayEvent) {
        if (listeners.isEmpty()) return
        val built = event()
        listeners.forEach { listener ->
            runCatching { listener.accept(built) }
                .onFailure { PaperServer.logger.error("A display event subscriber threw.", it) }
        }
    }
}

/** [ServerDisplay] over `Paper` display data. Resolves the display on every call. */
@PaperOnly
internal class PaperServerDisplay(private val data: PaperDisplayData) : ServerDisplay {
    override val id: UUID get() = data.id
    override val ownerId: UUID get() = data.ownerId
    override val isAlive: Boolean get() = DisplayManager.getDisplayData(data.id) != null
    override val world: String get() = data.pos1.world?.name.orEmpty()
    override val x: Double get() = data.box.centerX
    override val y: Double get() = data.box.centerY
    override val z: Double get() = data.box.centerZ
    override val width: Int get() = data.width
    override val height: Int get() = data.height
    override val name: String? get() = data.name
    override val url: String get() = data.url
    override val lang: String get() = data.lang
    override val mode: PlaybackMode get() = data.mode
    override val access: DisplayAccess get() = data.access

    override fun setUrl(url: String, lang: String): CommandResult {
        if (!isAlive) return CommandResult.STALE
        if (!MediaUrlPolicy.isAllowed(url)) return CommandResult.REFUSED
        val refused = CustomMediaGate.refusalKey(url, PaperServer.config.settings.customMediaPolicy, hasPermission = true)
        if (refused != null) return CommandResult.REFUSED

        data.url = url
        data.lang = MediaUrlPolicy.sanitizeLang(lang)
        save()
        DisplayManager.broadcastUpdate(data)
        TimelineManager.onVideoChanged(data)
        PaperServerApi.urlChanged(data)
        return CommandResult.OK
    }

    override fun setAccess(access: DisplayAccess): CommandResult {
        if (!isAlive) return CommandResult.STALE
        data.access = access
        save()
        DisplayManager.broadcastUpdate(data)
        return CommandResult.OK
    }

    override fun delete(): CommandResult {
        if (!isAlive) return CommandResult.STALE
        DisplayManager.delete(data.id)
        return CommandResult.OK
    }

    private fun save() = runAsync { PaperServer.getInstance().storage.saveDisplay(data) }

    override fun equals(other: Any?): Boolean = other is PaperServerDisplay && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "ServerDisplay($id)"
}

/** Lazy view over `Paper` displays; [filters] are applied in order on every iteration. */
@PaperOnly
internal class PaperServerDisplays(
    private val filters: List<(PaperDisplayData) -> Boolean> = emptyList(),
) : ServerDisplays {
    override val size: Int get() = matching().size

    override fun iterator(): Iterator<ServerDisplay> = matching().map { PaperServerDisplay(it) as ServerDisplay }.iterator()

    override fun get(id: UUID): ServerDisplay? =
        (DisplayManager.getDisplayData(id) as? PaperDisplayData)?.takeIf { d -> filters.all { it(d) } }
            ?.let { PaperServerDisplay(it) }

    override fun byName(name: String): ServerDisplay? =
        matching().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { PaperServerDisplay(it) }

    override fun ownedBy(owner: UUID): ServerDisplays = narrowed { it.ownerId == owner }

    override fun near(world: String, x: Double, y: Double, z: Double, radius: Double): ServerDisplays {
        val limit = radius * radius
        return narrowed { d ->
            val dx = d.box.centerX - x
            val dy = d.box.centerY - y
            val dz = d.box.centerZ - z
            d.pos1.world?.name == world && dx * dx + dy * dy + dz * dz <= limit
        }
    }

    override fun filter(predicate: Predicate<ServerDisplay>): ServerDisplays =
        narrowed { predicate.test(PaperServerDisplay(it)) }

    override fun create(
        owner: UUID,
        world: String,
        x1: Int, y1: Int, z1: Int,
        x2: Int, y2: Int, z2: Int,
        facing: DisplayFacing,
    ): ServerDisplay? {
        val bukkitWorld = Bukkit.getWorld(world) ?: return null
        val face = facing.toBlockFace()
        val p1 = org.bukkit.Location(bukkitWorld, x1.toDouble(), y1.toDouble(), z1.toDouble())
        val p2 = org.bukkit.Location(bukkitWorld, x2.toDouble(), y2.toDouble(), z2.toDouble())
        val region = RegionUtil.calculateRegion(p1, p2)
        val box = BoundingBox(
            region.minX.toDouble(), region.minY.toDouble(), region.minZ.toDouble(),
            (region.maxX + 1).toDouble(), (region.maxY + 1).toDouble(), (region.maxZ + 1).toDouble(),
        )
        val overlaps = DisplayManager.getDisplays().filterIsInstance<PaperDisplayData>()
            .any { it.pos1.world == bukkitWorld && it.box.overlaps(box) }
        if (overlaps) return null

        val (screenWidth, screenHeight, _) = region.screenExtents(abs(face.modX), abs(face.modY), abs(face.modZ))
        val data = PaperDisplayData(
            UUID.randomUUID(), owner,
            region.getMinLocation(bukkitWorld), region.getMaxLocation(bukkitWorld),
            screenWidth, screenHeight, face, DisplayRotation.NONE,
        )
        DisplayManager.register(data)
        return PaperServerDisplay(data)
    }

    private fun matching(): List<PaperDisplayData> =
        DisplayManager.getDisplays().filterIsInstance<PaperDisplayData>().filter { d -> filters.all { it(d) } }

    private fun narrowed(predicate: (PaperDisplayData) -> Boolean): ServerDisplays = PaperServerDisplays(filters + predicate)
}

private fun DisplayFacing.toBlockFace(): BlockFace = when (this) {
    DisplayFacing.NORTH -> BlockFace.NORTH
    DisplayFacing.EAST -> BlockFace.EAST
    DisplayFacing.SOUTH -> BlockFace.SOUTH
    DisplayFacing.WEST -> BlockFace.WEST
    DisplayFacing.UP -> BlockFace.UP
    DisplayFacing.DOWN -> BlockFace.DOWN
}
