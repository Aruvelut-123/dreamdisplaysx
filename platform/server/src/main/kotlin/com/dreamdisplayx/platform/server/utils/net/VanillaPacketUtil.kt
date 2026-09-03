package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.core.protocol.common.packets.ClearCache
import com.dreamdisplayx.core.protocol.common.packets.DisplayDelete
import com.dreamdisplayx.core.protocol.common.packets.DisplayInfo
import com.dreamdisplayx.core.protocol.common.packets.DisplaySync
import com.dreamdisplayx.core.protocol.common.packets.SetDisplaysEnabled
import com.dreamdisplayx.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplayx.platform.server.datatypes.sync.SyncData
import com.dreamdisplayx.util.FacingUtil
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Sends protocol-v2 packets for Fabric and NeoForge servers. */
object VanillaPacketUtil {
    fun sendDisplayInfo(players: List<ServerPlayer>, display: VanillaDisplayData, forced: Boolean = false) {
        val recipients = players
        VanillaNetworking.adapter.sendV2(recipients, DisplayInfo(
            id = display.id, ownerId = display.ownerId, x = display.minX, y = display.minY, z = display.minZ,
            width = display.width, height = display.height, url = display.url,
            facing = directionToFacingUtil(display.facing).toPacket().toInt(), isSync = display.isSync,
            lang = display.lang, isLocked = display.isLocked, mode = display.mode.wire,
            qualityCap = display.qualityCap, rotation = display.rotation.quarterTurns,
            virtual = display.virtual, forced = forced,
            scheduledStartEpochMillis = display.scheduledStart?.toEpochMilliseconds() ?: 0,
            scheduledAction = display.scheduledAction?.wire ?: -1, positionNanos = display.seekPositionNanos,
        ))
    }

    fun sendSync(players: List<ServerPlayer>, syncData: SyncData) {
        val id = syncData.id ?: return
        VanillaNetworking.adapter.sendV2(players, DisplaySync(
            id = id, isSync = syncData.isSync, isPaused = !syncData.currentState,
            currentTimeMs = syncData.currentTime, durationMs = syncData.limitTime,
        ))
    }

    fun sendDelete(players: List<ServerPlayer>, id: UUID) =
        VanillaNetworking.adapter.sendV2(players, DisplayDelete(id))

    fun sendDisplayEnabled(player: ServerPlayer, isEnabled: Boolean) =
        VanillaNetworking.adapter.sendV2(listOf(player), SetDisplaysEnabled(isEnabled))

    fun sendClearCache(players: List<ServerPlayer>, uuids: List<UUID>) {
        if (uuids.isNotEmpty()) VanillaNetworking.adapter.sendV2(players, ClearCache(uuids))
    }

    private fun directionToFacingUtil(direction: net.minecraft.core.Direction): FacingUtil = when (direction) {
        Direction.NORTH -> FacingUtil.NORTH
        Direction.EAST -> FacingUtil.EAST
        Direction.SOUTH -> FacingUtil.SOUTH
        Direction.WEST -> FacingUtil.WEST
        Direction.UP -> FacingUtil.UP
        Direction.DOWN -> FacingUtil.DOWN
    }
}
