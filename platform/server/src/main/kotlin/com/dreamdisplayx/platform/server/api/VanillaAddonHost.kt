package com.dreamdisplayx.platform.server.api

import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplayx.api.display.model.property.DisplayRotation
import com.dreamdisplayx.api.security.policy.MediaUrlPolicy
import com.dreamdisplayx.core.addon.ServerAddonDisplay
import com.dreamdisplayx.core.addon.ServerAddonEvents
import com.dreamdisplayx.core.addon.ServerAddonHooks
import com.dreamdisplayx.core.addon.ServerAddonHost
import com.dreamdisplayx.platform.server.ModLoaderOnly
import com.dreamdisplayx.platform.server.VanillaServerState
import com.dreamdisplayx.platform.server.compat.stampShipAnchor
import com.dreamdisplayx.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplayx.platform.server.managers.DisplayManager
import com.dreamdisplayx.platform.server.meta.ServerCoroutines
import com.dreamdisplayx.platform.server.playback.TimelineManager
import com.dreamdisplayx.platform.server.utils.RegionUtil
import com.dreamdisplayx.platform.server.utils.net.CustomMediaGate
import com.dreamdisplayx.platform.server.utils.net.VanillaPacketUtil
import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.server.ServerDisplayEvent
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.util.UUID
import java.util.function.Consumer
import kotlin.math.abs

/**
 * The `Fabric` / `NeoForge` implementation of [ServerAddonHost].
 *
 * The mirror of `PaperAddonHost` for the shared vanilla path: it is the only place that knows both a
 * `VanillaDisplayData` and the addon API, so a `BlockPos` never leaks into `:core`.
 *
 * Every mutation reuses the gates a player's request goes through — [ServerVetoes.askCreate], [ServerVetoes.askDelete],
 * [ServerVetoes.askSetUrl] with a `null` actor, plus `MediaUrlPolicy` and `CustomMediaGate` on the link — so an addon
 * cannot do more than a player could.
 *
 * @since 1.10.x
 */
@ModLoaderOnly
class VanillaAddonHost : ServerAddonHost {

    override fun snapshot(): List<ServerAddonDisplay> =
        DisplayManager.getDisplays().filterIsInstance<VanillaDisplayData>().map { it.toAddonDisplay() }

    /** Creates a display after the same create veto `/display create` goes through. */
    override fun create(
        owner: UUID,
        world: String,
        x1: Int, y1: Int, z1: Int,
        x2: Int, y2: Int, z2: Int,
        facing: DisplayFacing,
    ): ServerAddonDisplay? {
        val server = VanillaServerState.server ?: return null
        // Resolving the level first rejects an unknown dimension exactly like the command does, instead of storing a
        // row that no player will ever be shown.
        val level = RegionUtil.getLevelByKey(server, world) ?: return null
        val region = RegionUtil.calculateRegion(BlockPos(x1, y1, z1), BlockPos(x2, y2, z2))
        val dir = facing.toDirection()
        val (width, height, _) = region.screenExtents(abs(dir.stepX), abs(dir.stepY), abs(dir.stepZ))
        val data = VanillaDisplayData(
            id = UUID.randomUUID(),
            ownerId = owner,
            worldKey = world,
            pos1 = region.getMinBlockPos(),
            pos2 = region.getMaxBlockPos(),
            width = width,
            height = height,
            facing = dir,
            rotation = DisplayRotation.NONE,
        )
        ServerVetoes.askCreate(data, null)?.let { return null }
        // Same anchor the `/display create` path records, so an addon-built panel on a ship rides it too.
        stampShipAnchor(data, level, x1, y1, z1)
        // `DisplayManager.register` does not persist vanilla rows and publishes the `Created` event itself, so the save
        // stays here and the event is emitted exactly once.
        DisplayManager.register(data)
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(data) }
        val receivers = DisplayManager.getReceivers(data, server)
        VanillaPacketUtil.sendDisplayInfo(receivers, data)
        return data.toAddonDisplay()
    }

    override fun delete(id: UUID): CommandResult {
        val data = DisplayManager.getDisplayData(id) as? VanillaDisplayData ?: return CommandResult.STALE
        if (ServerVetoes.askDelete(data, null) != null) return CommandResult.REFUSED
        DisplayManager.delete(data)
        return CommandResult.OK
    }

    /** Changes the link, restarts the timeline and pushes the row to the players in range. */
    override fun setUrl(id: UUID, url: String, lang: String): CommandResult {
        val data = DisplayManager.getDisplayData(id) as? VanillaDisplayData ?: return CommandResult.STALE
        val server = VanillaServerState.server ?: return CommandResult.REFUSED
        if (!MediaUrlPolicy.isAllowed(url)) return CommandResult.REFUSED
        val sanitizedLang = MediaUrlPolicy.sanitizeLang(lang)
        CustomMediaGate.refusalKey(
            url,
            VanillaServerState.config.settings.customMediaPolicy,
            hasPermission = true,
            playerId = null,
        )?.let { return CommandResult.REFUSED }
        if (ServerVetoes.askSetUrl(data, url, sanitizedLang, null) != null) return CommandResult.REFUSED
        data.url = url
        data.lang = sanitizedLang
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(data) }
        TimelineManager.resetVideo(data)
        VanillaPacketUtil.sendDisplayInfo(DisplayManager.getReceivers(data, server), data)
        TimelineManager.broadcastCurrent(data)
        ServerAddonHooks.urlChanged(data.id, url)
        return CommandResult.OK
    }

    override fun setAccess(id: UUID, access: DisplayAccess): CommandResult {
        val data = DisplayManager.getDisplayData(id) as? VanillaDisplayData ?: return CommandResult.STALE
        val server = VanillaServerState.server ?: return CommandResult.REFUSED
        data.access = access.toForkAccess()
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(data) }
        VanillaPacketUtil.sendDisplayInfo(DisplayManager.getReceivers(data, server), data)
        return CommandResult.OK
    }

    /**
     * Not supported: this fork has no move operation.
     *
     * Relocating a display means replacing its row, while timeline, playlist, watch-party, fullscreen and pin state are
     * keyed by display id rather than by box, so a swap would leave all of them pointing at a stale rectangle. Answering
     * `REFUSED` is honest; accepting would let an addon believe the display moved.
     */
    override fun moveTo(
        id: UUID,
        world: String,
        x1: Int, y1: Int, z1: Int,
        x2: Int, y2: Int, z2: Int,
        facing: DisplayFacing,
    ): CommandResult = if (DisplayManager.getDisplayData(id) == null) CommandResult.STALE else CommandResult.REFUSED

    override fun listen(listener: Consumer<ServerDisplayEvent>): AutoCloseable = ServerAddonEvents.on(listener)

    override fun vetoes() = ServerVetoes.registry
}

/** Flattens a vanilla row into the loader-neutral snapshot. */
fun VanillaDisplayData.toAddonDisplay(): ServerAddonDisplay = ServerAddonDisplay(
    id = id,
    ownerId = ownerId,
    world = worldKey,
    x1 = minX,
    y1 = minY,
    z1 = minZ,
    x2 = maxX,
    y2 = maxY,
    z2 = maxZ,
    facing = facing.toDisplayFacing(),
    width = width,
    height = height,
    name = name,
    url = url,
    lang = lang,
    mode = mode.toUpstreamMode(),
    access = access.toUpstreamAccess(),
)

/** Maps a Minecraft direction onto the shared facing. */
private fun Direction.toDisplayFacing(): DisplayFacing = when (this) {
    Direction.NORTH -> DisplayFacing.NORTH
    Direction.EAST -> DisplayFacing.EAST
    Direction.SOUTH -> DisplayFacing.SOUTH
    Direction.WEST -> DisplayFacing.WEST
    Direction.UP -> DisplayFacing.UP
    Direction.DOWN -> DisplayFacing.DOWN
}

/** The inverse of [toDisplayFacing]; the enum is total, so no `else` branch is needed. */
private fun DisplayFacing.toDirection(): Direction = when (this) {
    DisplayFacing.NORTH -> Direction.NORTH
    DisplayFacing.EAST -> Direction.EAST
    DisplayFacing.SOUTH -> Direction.SOUTH
    DisplayFacing.WEST -> Direction.WEST
    DisplayFacing.UP -> Direction.UP
    DisplayFacing.DOWN -> Direction.DOWN
}

/**
 * The two enum families name the same thing and must not be mixed.
 *
 * `ServerAddonHost` speaks upstream's `com.dreamdisplays.*` enums so an addon compiled against upstream sees the types it
 * knows, while a stored row uses this fork's `com.dreamdisplayx.*` ones. Both lists are identical, so the mapping is a
 * rename per constant; keeping it in the loader that owns the row means `:core` never names either family twice.
 */
private fun com.dreamdisplayx.api.playback.model.PlaybackMode.toUpstreamMode(): PlaybackMode = when (this) {
    com.dreamdisplayx.api.playback.model.PlaybackMode.LOCAL -> PlaybackMode.LOCAL
    com.dreamdisplayx.api.playback.model.PlaybackMode.SYNCED -> PlaybackMode.SYNCED
    com.dreamdisplayx.api.playback.model.PlaybackMode.WATCH_PARTY -> PlaybackMode.WATCH_PARTY
    com.dreamdisplayx.api.playback.model.PlaybackMode.BROADCAST -> PlaybackMode.BROADCAST
}

private fun com.dreamdisplayx.api.playback.model.DisplayAccess.toUpstreamAccess(): DisplayAccess = when (this) {
    com.dreamdisplayx.api.playback.model.DisplayAccess.EVERYONE -> DisplayAccess.EVERYONE
    com.dreamdisplayx.api.playback.model.DisplayAccess.REGION -> DisplayAccess.REGION
    com.dreamdisplayx.api.playback.model.DisplayAccess.LOCKED -> DisplayAccess.LOCKED
}

private fun DisplayAccess.toForkAccess(): com.dreamdisplayx.api.playback.model.DisplayAccess = when (this) {
    DisplayAccess.EVERYONE -> com.dreamdisplayx.api.playback.model.DisplayAccess.EVERYONE
    DisplayAccess.REGION -> com.dreamdisplayx.api.playback.model.DisplayAccess.REGION
    DisplayAccess.LOCKED -> com.dreamdisplayx.api.playback.model.DisplayAccess.LOCKED
}