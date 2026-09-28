package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.protocol.common.packets.ClearCache
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.protocol.common.packets.SetDisplaysEnabled
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.util.FacingUtil
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import java.util.*

/**
 * Protocol-v2 send facade for the vanilla Minecraft API flavor (`Fabric` / `NeoForge`). Only players
 * that completed the v2 hello receive anything; protocol v1 is no longer supported.
 */
object VanillaPacketUtil {
    /** The recipients that negotiated protocol v2. */
    private fun v2Players(players: List<ServerPlayer>): List<ServerPlayer> =
        players.filter { V2PlayerTracker.isV2(it.uuid) }

    /** Encodes and broadcasts a [DisplayInfo] packet describing a single display to [players]. */
    fun sendDisplayInfo(players: List<ServerPlayer>, display: VanillaDisplayData, forced: Boolean = false) {
        val isVertical = display.facing == Direction.UP || display.facing == Direction.DOWN
        val recipients = v2Players(players).filter { canReceive(it.uuid, isVertical, display.conforming) }
        VanillaNetworking.adapter.sendV2(
            recipients,
            DisplayInfo(
                id = display.id, ownerId = display.ownerId,
                x = display.minX, y = display.minY, z = display.minZ,
                width = display.width, height = display.height, url = display.url,
                facing = directionToFacingUtil(display.facing).toPacket().toInt(),
                isSync = display.isSync, lang = display.lang, isLocked = display.isLocked,
                mode = display.mode.wire, qualityCap = display.qualityCap,
                rotation = display.rotation.quarterTurns,
                virtual = display.virtual, forced = forced,
                scheduledStartEpochMillis = display.scheduledStart?.toEpochMilliseconds() ?: 0,
                scheduledAction = display.scheduledAction?.wire ?: -1,
                access = display.access.wire,
                depth = display.depth, conforming = display.conforming,
            ),
        )
    }

    /** Tells [players] to remove the display with [id] from their local registry. */
    fun sendDelete(players: List<ServerPlayer>, id: UUID) {
        VanillaNetworking.adapter.sendV2(v2Players(players), DisplayDelete(id))
    }

    /** Pushes the global displays-enabled flag for [player] to the client. */
    fun sendDisplayEnabled(player: ServerPlayer, isEnabled: Boolean) {
        VanillaNetworking.adapter.sendV2(v2Players(listOf(player)), SetDisplaysEnabled(isEnabled))
    }

    /** Tells [players] to evict the listed display UUIDs from any local caches. */
    fun sendClearCache(players: List<ServerPlayer>, uuids: List<UUID>) {
        if (uuids.isEmpty()) return
        VanillaNetworking.adapter.sendV2(v2Players(players), ClearCache(uuids))
    }

    /** Maps a [Direction] to its wire [FacingUtil]; faces not in the protocol fall back to north. */
    private fun directionToFacingUtil(direction: Direction): FacingUtil {
        return when (direction) {
            Direction.NORTH -> FacingUtil.NORTH
            Direction.EAST -> FacingUtil.EAST
            Direction.SOUTH -> FacingUtil.SOUTH
            Direction.WEST -> FacingUtil.WEST
            Direction.UP -> FacingUtil.UP
            Direction.DOWN -> FacingUtil.DOWN
        }
    }
}
