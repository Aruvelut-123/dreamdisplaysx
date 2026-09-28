package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.api.display.model.property.DisplayRotation
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.core.protocol.common.packets.ClearCache
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.protocol.common.packets.SetDisplaysEnabled
import com.dreamdisplays.platform.server.managers.PlayerManager
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.jspecify.annotations.NullMarked
import java.util.*

private const val VERTICAL_SINCE = "1.8.0"
private const val CONFORMING_SINCE = "1.10.0-preview.3"

/**
 * @return `true` if the client identified by [uuid] runs a mod version that understands vertical
 * display facings (>= 1.8.0).
 *
 * Older clients would crash decoding facing bytes, so vertical displays are simply never sent to them.
 */
internal fun supportsVertical(uuid: UUID): Boolean {
    val v = PlayerManager.getVersion(uuid) ?: return false
    return v.isGreaterThanOrEqualTo(VERTICAL_SINCE)
}

/**
 * @return `true` if the client identified by [uuid] can wrap a screen over slabs and stairs (>= 1.10.0 Preview 3).
 *
 * Older clients would draw a flat quad hanging in front of the steps, so wrapped displays are never sent to them.
 */
internal fun supportsConforming(uuid: UUID): Boolean {
    val v = PlayerManager.getVersion(uuid) ?: return false
    if (v.major != 1 || v.minor != 10 || v.patch != 0) return v.isGreaterThan(CONFORMING_SINCE)
    val tag = v.preRelease.firstOrNull() ?: return true
    if (tag == "dev") return true
    return v.isGreaterThanOrEqualTo(CONFORMING_SINCE)
}

/** Whether the client identified by [uuid] understands a display with these traits. */
internal fun canReceive(uuid: UUID, vertical: Boolean, conforming: Boolean): Boolean =
    (!vertical || supportsVertical(uuid)) && (!conforming || supportsConforming(uuid))

/**
 * Protocol-v2 send facade for the Paper flavor. Only players that completed the v2 hello (see
 * [V2PlayerTracker]) receive anything; protocol v1 is no longer supported.
 */
@PaperOnly
@NullMarked
object PacketUtil {
    /** Encodes and broadcasts a [DisplayInfo] packet describing a single display to [players]. */
    fun sendDisplayInfo(
        players: List<Player?>,
        id: UUID,
        ownerId: UUID,
        position: Vector,
        width: Int,
        height: Int,
        url: String,
        lang: String,
        facing: BlockFace,
        isSync: Boolean,
        isLocked: Boolean = true,
        access: DisplayAccess = DisplayAccess.DEFAULT,
        mode: PlaybackMode = if (isSync) PlaybackMode.SYNCED else PlaybackMode.LOCAL,
        qualityCap: Int = 0,
        rotation: DisplayRotation = DisplayRotation.NONE,
        virtual: Boolean = false,
        forced: Boolean = false,
        scheduledStartEpochMillis: Long = 0,
        scheduledAction: Int = -1,
        inRegion: Boolean = false,
        isRegionMember: ((Player) -> Boolean)? = null,
        depth: Int = 1,
        conforming: Boolean = false,
    ) {
        val isVertical = facing == BlockFace.UP || facing == BlockFace.DOWN
        val recipients = v2Players(players).filter { canReceive(it.uniqueId, isVertical, conforming) }
        val info = DisplayInfo(
            id = id, ownerId = ownerId,
            x = position.blockX, y = position.blockY, z = position.blockZ,
            width = width, height = height, url = url,
            facing = facing.toPacketByte(),
            isSync = isSync, lang = lang, isLocked = isLocked,
            mode = mode.wire, qualityCap = qualityCap,
            rotation = rotation.quarterTurns,
            virtual = virtual, forced = forced,
            scheduledStartEpochMillis = scheduledStartEpochMillis, scheduledAction = scheduledAction,
            access = access.wire, inRegion = inRegion,
            depth = depth.coerceAtLeast(1), conforming = conforming,
        )
        if (access == DisplayAccess.REGION && isRegionMember != null) {
            recipients.forEach { player ->
                PaperV2Networking.send(listOf(player), info.copy(viewerInRegion = isRegionMember(player)))
            }
        } else {
            PaperV2Networking.send(recipients, info)
        }
    }

    /** Tells [players] to remove the display with [id] from their local registry. */
    fun sendDelete(players: List<Player?>, id: UUID) {
        PaperV2Networking.send(v2Players(players), DisplayDelete(id))
    }

    /** Pushes the global displays-enabled flag for [player] to the client. */
    fun sendDisplayEnabled(player: Player, isEnabled: Boolean) {
        PaperV2Networking.send(v2Players(listOf(player)), SetDisplaysEnabled(isEnabled))
    }

    /** Tells [players] to evict the listed display UUIDs from any local caches. */
    fun sendClearCache(players: List<Player?>, displayUuids: List<UUID>) {
        if (displayUuids.isEmpty()) return
        PaperV2Networking.send(v2Players(players), ClearCache(displayUuids))
    }

    /** The recipients that negotiated protocol v2. */
    private fun v2Players(players: List<Player?>): List<Player> =
        players.filterNotNull().filter { V2PlayerTracker.isV2(it.uniqueId) }

    /** Maps a [BlockFace] to its wire value; faces not in the protocol fall back to north. */
    private fun BlockFace.toPacketByte(): Int = when (this) {
        BlockFace.NORTH -> 0
        BlockFace.EAST -> 1
        BlockFace.SOUTH -> 2
        BlockFace.WEST -> 3
        BlockFace.UP -> 4
        BlockFace.DOWN -> 5
        else -> 0
    }
}
