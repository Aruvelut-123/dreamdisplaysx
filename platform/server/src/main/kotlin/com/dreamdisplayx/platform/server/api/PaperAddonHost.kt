package com.dreamdisplayx.platform.server.api

import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplayx.api.display.model.property.DisplayRotation
import com.dreamdisplayx.api.security.policy.MediaUrlPolicy
import com.dreamdisplayx.core.addon.ServerAddonDisplay
import com.dreamdisplayx.core.addon.ServerAddonHost
import com.dreamdisplayx.core.addon.ServerAddonHooks
import com.dreamdisplayx.core.addon.ServerAddonEvents
import com.dreamdisplayx.platform.server.PaperServer
import com.dreamdisplayx.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplayx.platform.server.managers.DisplayManager
import com.dreamdisplayx.platform.server.meta.Scheduler.runAsync
import com.dreamdisplayx.platform.server.playback.TimelineManager
import com.dreamdisplayx.platform.server.utils.RegionUtil
import com.dreamdisplayx.platform.server.utils.net.CustomMediaGate
import io.github.arnodoelinger.platformweaver.PaperOnly
import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.server.ServerDisplayEvent
import net.minecraft.core.BlockPos
import org.bukkit.Bukkit
import org.bukkit.block.BlockFace
import java.util.UUID
import java.util.function.Consumer
import kotlin.math.abs

/**
 * The Paper implementation of [ServerAddonHost].
 *
 * This is the only place that knows both a `PaperDisplayData` and the addon API, so every conversion between a Bukkit
 * `Location` and the loader-neutral [ServerAddonDisplay] happens here and nowhere else. `:core` stays loader-free and
 * Fabric never sees a Bukkit type.
 *
 * Operations go through the same gates a player's request would: [ServerVetoes.askSetUrl] / [ServerVetoes.askDelete] /
 * [ServerVetoes.askCreate] with a `null` actor (the addon is the requester), `MediaUrlPolicy` on the link, and
 * `CustomMediaGate` on custom hosts. An addon therefore cannot do anything a player could not, which is what makes the
 * API safe to hand to a third party.
 *
 * @since 1.10.x
 */
@PaperOnly
class PaperAddonHost : ServerAddonHost {

    override fun snapshot(): List<ServerAddonDisplay> =
        DisplayManager.getDisplays().filterIsInstance<PaperDisplayData>().map { it.toAddonDisplay() }

    /** Creates a display after the same create veto a player's `/display create` goes through. */
    override fun create(
        owner: UUID,
        world: String,
        x1: Int, y1: Int, z1: Int,
        x2: Int, y2: Int, z2: Int,
        facing: DisplayFacing,
    ): ServerAddonDisplay? {
        val bukkitWorld = Bukkit.getWorld(world) ?: return null
        val region = RegionUtil.calculateRegion(BlockPos(x1, y1, z1), BlockPos(x2, y2, z2))
        val face = facing.toBlockFace()
        val (width, height, _) = region.screenExtents(abs(face.modX), abs(face.modY), abs(face.modZ))
        val data = PaperDisplayData(
            id = UUID.randomUUID(),
            ownerId = owner,
            pos1 = region.getMinLocation(bukkitWorld),
            pos2 = region.getMaxLocation(bukkitWorld),
            width = width,
            height = height,
            facing = face,
            rotation = DisplayRotation.NONE,
        )
        ServerVetoes.askCreate(data, null)?.let { return null }
        // DisplayManager.register publishes the `Created` event itself, so this path emits exactly once.
        DisplayManager.register(data)
        return data.toAddonDisplay()
    }

    /** Removes the display; `DisplayManager.delete` publishes the `Removed` event before the row goes away. */
    override fun delete(id: UUID): CommandResult {
        val data = DisplayManager.getDisplayData(id) as? PaperDisplayData ?: return CommandResult.STALE
        if (ServerVetoes.askDelete(data, null) != null) return CommandResult.REFUSED
        DisplayManager.delete(data)
        return CommandResult.OK
    }

    /** Changes the link and restarts the timeline, exactly like `DisplayActions.setVideo` does for a player. */
    override fun setUrl(id: UUID, url: String, lang: String): CommandResult {
        val data = DisplayManager.getDisplayData(id) as? PaperDisplayData ?: return CommandResult.STALE
        if (!MediaUrlPolicy.isAllowed(url)) return CommandResult.REFUSED
        val sanitizedLang = MediaUrlPolicy.sanitizeLang(lang)
        // An addon has no permission node and no UUID to rate-limit against, so it goes through the policy half of the
        // gate only: a custom host is refused unless the server allows custom links, but the per-player quota is not
        // applied to a caller that is not a player.
        CustomMediaGate.refusalKey(
            url,
            PaperServer.config.settings.customMediaPolicy,
            hasPermission = true,
            playerId = null,
        )?.let { return CommandResult.REFUSED }
        if (ServerVetoes.askSetUrl(data, url, sanitizedLang, null) != null) return CommandResult.REFUSED
        data.url = url
        data.lang = sanitizedLang
        runAsync { PaperServer.getInstance().storage.saveDisplay(data) }
        TimelineManager.resetVideo(data)
        DisplayManager.broadcastUpdate(data)
        TimelineManager.broadcastCurrent(data)
        ServerAddonHooks.urlChanged(data.id, url)
        return CommandResult.OK
    }

    override fun setAccess(id: UUID, access: DisplayAccess): CommandResult {
        val data = DisplayManager.getDisplayData(id) as? PaperDisplayData ?: return CommandResult.STALE
        data.access = access.toForkAccess()
        runAsync { PaperServer.getInstance().storage.saveDisplay(data) }
        DisplayManager.broadcastUpdate(data)
        return CommandResult.OK
    }

    /**
     * Not supported: this fork has no move operation.
     *
     * Relocating a display means replacing its row, and the registry keys timeline, playlist, watch-party, fullscreen
     * and pin state by id rather than by box, so a swap would leave every one of those pointing at a stale rectangle.
     * Answering `REFUSED` is the honest result; silently accepting would let an addon believe the display moved.
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

/** Flattens a Paper row into the loader-neutral snapshot. */
@PaperOnly
fun PaperDisplayData.toAddonDisplay(): ServerAddonDisplay {
    val region = RegionUtil.calculateRegion(pos1, pos2)
    return ServerAddonDisplay(
        id = id,
        ownerId = ownerId,
        world = pos1.world?.name ?: "",
        x1 = region.minX,
        y1 = region.minY,
        z1 = region.minZ,
        x2 = region.maxX,
        y2 = region.maxY,
        z2 = region.maxZ,
        facing = facing.toDisplayFacing(),
        width = width,
        height = height,
        name = name,
        url = url,
        lang = lang,
        mode = mode.toUpstreamMode(),
        access = access.toUpstreamAccess(),
    )
}

/** Maps a Bukkit face onto the shared facing; a diagonal face is not a valid screen orientation, so it reads as north. */
private fun BlockFace.toDisplayFacing(): DisplayFacing = when (this) {
    BlockFace.NORTH -> DisplayFacing.NORTH
    BlockFace.EAST -> DisplayFacing.EAST
    BlockFace.SOUTH -> DisplayFacing.SOUTH
    BlockFace.WEST -> DisplayFacing.WEST
    BlockFace.UP -> DisplayFacing.UP
    BlockFace.DOWN -> DisplayFacing.DOWN
    else -> DisplayFacing.NORTH
}

/** The inverse of [toDisplayFacing]. */
private fun DisplayFacing.toBlockFace(): BlockFace = when (this) {
    DisplayFacing.NORTH -> BlockFace.NORTH
    DisplayFacing.EAST -> BlockFace.EAST
    DisplayFacing.SOUTH -> BlockFace.SOUTH
    DisplayFacing.WEST -> BlockFace.WEST
    DisplayFacing.UP -> BlockFace.UP
    DisplayFacing.DOWN -> BlockFace.DOWN
}

/**
 * The two enum families name the same thing and must not be mixed.
 *
 * `ServerAddonHost` speaks upstream's `com.dreamdisplays.*` enums (so an addon compiled against upstream sees the types
 * it knows), while a stored row uses this fork's `com.dreamdisplayx.*` ones. Both lists are identical, so the mapping is
 * a rename per constant; doing it here keeps every translation in the loader that owns the row.
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