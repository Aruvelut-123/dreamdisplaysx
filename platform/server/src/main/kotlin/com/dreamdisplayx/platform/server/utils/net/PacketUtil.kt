package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.api.display.model.property.DisplayRotation
import com.dreamdisplayx.api.playback.model.DisplayAccess
import com.dreamdisplayx.api.playback.model.PlaybackMode
import com.dreamdisplayx.core.protocol.common.packets.ClearCache
import com.dreamdisplayx.core.protocol.common.packets.DisplayDelete
import com.dreamdisplayx.core.protocol.common.packets.DisplayInfo
import com.dreamdisplayx.core.protocol.common.packets.DisplaySync
import com.dreamdisplayx.core.protocol.common.packets.SetDisplaysEnabled
import com.dreamdisplayx.platform.server.datatypes.sync.SyncData
import com.dreamdisplayx.platform.server.playback.TimelineManager
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.jspecify.annotations.NullMarked
import java.util.UUID

/** Paper protocol-v2 packet facade. */
@PaperOnly
@NullMarked
object PacketUtil {
    fun sendDisplayInfo(
        players: List<Player?>, id: UUID, ownerId: UUID, position: Vector, width: Int, height: Int,
        url: String, lang: String, facing: BlockFace, isSync: Boolean, isLocked: Boolean = true,
        access: DisplayAccess = DisplayAccess.DEFAULT,
        mode: PlaybackMode = if (isSync) PlaybackMode.SYNCED else PlaybackMode.LOCAL,
        qualityCap: Int = 0, rotation: DisplayRotation = DisplayRotation.NONE, virtual: Boolean = false,
        forced: Boolean = false, scheduledStartEpochMillis: Long = 0, scheduledAction: Int = -1,
        positionNanos: Long = 0,
        inRegion: Boolean = false,
        isRegionMember: ((Player) -> Boolean)? = null,
    ) {
        val info = DisplayInfo(
            id = id, ownerId = ownerId, x = position.blockX, y = position.blockY, z = position.blockZ,
            width = width, height = height, url = url, facing = facing.toPacketByte().toInt(),
            isSync = isSync, lang = lang, isLocked = isLocked, mode = mode.wire,
            qualityCap = qualityCap, rotation = rotation.quarterTurns, virtual = virtual, forced = forced,
            scheduledStartEpochMillis = scheduledStartEpochMillis, scheduledAction = scheduledAction,
            positionNanos = positionNanos,
            access = access.wire, inRegion = inRegion,
        )
        if (access == DisplayAccess.REGION && isRegionMember != null) {
            players.filterNotNull().forEach { player ->
                PaperV2Networking.send(listOf(player), info.copy(viewerInRegion = isRegionMember(player)))
            }
        } else {
            PaperV2Networking.send(players, info)
        }
    }

    fun sendSync(players: List<Player?>, syncData: SyncData) {
        val id = syncData.id ?: return
        PaperV2Networking.send(players, DisplaySync(
            id = id, isSync = syncData.isSync, isPaused = !syncData.currentState,
            currentTimeMs = syncData.currentTime, durationMs = syncData.limitTime,
        ))
    }

    fun sendDelete(players: List<Player?>, id: UUID) = PaperV2Networking.send(players, DisplayDelete(id))

    fun sendDisplayEnabled(player: Player, isEnabled: Boolean) =
        PaperV2Networking.send(listOf(player), SetDisplaysEnabled(isEnabled))

    fun sendClearCache(players: List<Player?>, displayUuids: List<UUID>) {
        if (displayUuids.isNotEmpty()) PaperV2Networking.send(players, ClearCache(displayUuids))
    }

    /** Maps a [BlockFace] to its wire byte; faces not in the protocol fall back to north. */
    private fun BlockFace.toPacketByte(): Byte = when (this) {
        BlockFace.NORTH -> 0
        BlockFace.EAST -> 1
        BlockFace.SOUTH -> 2
        BlockFace.WEST -> 3
        BlockFace.UP -> 4
        BlockFace.DOWN -> 5
        else -> 0
    }
}
