package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.utils.PlatformUtil
import com.dreamdisplays.platform.server.utils.WorldGuardRegions
import com.dreamdisplays.platform.server.utils.net.PaperV2Networking
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.jspecify.annotations.NullMarked
import java.util.*

/** `Paper` implementation of [PlaybackTransport]: v2 envelopes via [PaperV2Networking]. */
@PaperOnly
@NullMarked
object PaperPlaybackTransport : PlaybackTransport {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun broadcast(display: DisplayData, packet: DreamPacket) {
        val paper = display as? PaperDisplayData ?: return
        if (PlatformUtil.isFolia) {
            DisplayManager.sendV2ToTrackedNearbyPlayers(paper, packet)
            return
        }
        val receivers = DisplayManager.getReceivers(paper).filter { V2PlayerTracker.isV2(it.uniqueId) }
        if (receivers.isNotEmpty()) PaperV2Networking.send(receivers, packet)
    }

    override fun sendTo(playerId: UUID, packet: DreamPacket) {
        if (PlatformUtil.isFolia) {
            Scheduler.runTrackedPlayer(playerId) { player ->
                if (V2PlayerTracker.isV2(playerId)) PaperV2Networking.send([player], packet)
            }
            return
        }
        val player = PaperServer.getInstance().server.getPlayer(playerId) ?: return
        if (V2PlayerTracker.isV2(playerId)) PaperV2Networking.send([player], packet)
    }

    override fun nearbyPlayerIds(display: DisplayData): List<UUID> {
        val paper = display as? PaperDisplayData ?: return []
        if (PlatformUtil.isFolia) return DisplayManager.getTrackedNearbyPlayerIds(paper)
        return DisplayManager.getReceivers(paper).map { it.uniqueId }
    }

    override fun playerName(playerId: UUID): String? {
        if (PlatformUtil.isFolia) return Scheduler.trackedPlayerName(playerId)
        return PaperServer.getInstance().server.getPlayer(playerId)?.name
    }

    override fun isAdmin(playerId: UUID): Boolean {
        if (PlatformUtil.isFolia) return Scheduler.trackedPlayerIsAdmin(playerId)
        return PaperServer.getInstance().server.getPlayer(playerId)
            ?.hasPermission(PaperServer.config.permissions.deleteOthers) == true
    }

    override fun isTerritoryMember(display: DisplayData, playerId: UUID): Boolean {
        val paper = display as? PaperDisplayData ?: return false
        val player = PaperServer.getInstance().server.getPlayer(playerId) ?: return false
        return WorldGuardRegions.isRegionMember(player, paper.pos1)
    }

    override fun onlinePlayerIds(): List<UUID> = PaperServer.getInstance().server.onlinePlayers.map { it.uniqueId }

    override fun playerDistanceSq(playerId: UUID, world: String, x: Double, y: Double, z: Double): Double? {
        val loc = PaperServer.getInstance().server.getPlayer(playerId)?.location ?: return null
        if (loc.world?.name != world) return null
        val dx = loc.x - x
        val dy = loc.y - y
        val dz = loc.z - z
        return dx * dx + dy * dy + dz * dz
    }

    override fun sendDisplayInfo(playerId: UUID, display: DisplayData, forced: Boolean) {
        val paper = display as? PaperDisplayData ?: return
        val player = PaperServer.getInstance().server.getPlayer(playerId) ?: return
        DisplayManager.sendUpdate(paper, [player], forced)
    }

    override fun runOnMainThread(task: () -> Unit) {
        Scheduler.runSync(task)
    }

    override fun saveDisplay(display: DisplayData) {
        val paper = display as? PaperDisplayData ?: return
        Scheduler.runAsync { PaperServer.getInstance().storage.saveDisplay(paper) }
    }

    override fun createVirtualDisplay(id: UUID, ownerId: UUID): DisplayData? {
        val world = PaperServer.getInstance().server.worlds.firstOrNull() ?: return null
        val loc = org.bukkit.Location(world, 0.0, 0.0, 0.0)
        return PaperDisplayData(id, ownerId, loc, loc, 1, 1, virtual = true)
    }
}
