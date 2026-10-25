package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions
import com.dreamdisplays.platform.server.utils.net.VanillaNetworking
import com.dreamdisplays.platform.server.utils.net.VanillaPacketUtil
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import java.util.*

/**
 * Vanilla Minecraft API implementation of [PlaybackTransport], shared by `Fabric` and `NeoForge`.
 */
object VanillaPlaybackTransport : PlaybackTransport {
    @Volatile
    private var server: MinecraftServer? = null

    /** Binds the running server; called from `SERVER_STARTED` / `ServerStartedEvent`. */
    fun bind(server: MinecraftServer) {
        this.server = server
    }

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun broadcast(display: DisplayData, packet: DreamPacket) {
        val s = server ?: return
        val vanilla = display as? VanillaDisplayData ?: return
        val receivers = DisplayManager.getReceivers(vanilla, s).filter { V2PlayerTracker.isV2(it.uuid) }
        if (receivers.isNotEmpty()) VanillaNetworking.adapter.sendV2(receivers, packet)
    }

    override fun sendTo(playerId: UUID, packet: DreamPacket) {
        val s = server ?: return
        val player = s.playerList.getPlayer(playerId) ?: return
        if (V2PlayerTracker.isV2(playerId)) VanillaNetworking.adapter.sendV2([player], packet)
    }

    override fun nearbyPlayerIds(display: DisplayData): List<UUID> {
        val s = server ?: return []
        val vanilla = display as? VanillaDisplayData ?: return []
        return DisplayManager.getReceivers(vanilla, s).map { it.uuid }
    }

    override fun playerName(playerId: UUID): String? =
        server?.playerList?.getPlayer(playerId)?.gameProfile?.name

    override fun isAdmin(playerId: UUID): Boolean {
        val player = server?.playerList?.getPlayer(playerId) ?: return false
        return VanillaDisplayActions.isAdmin(player)
    }

    override fun isTerritoryMember(display: DisplayData, playerId: UUID): Boolean = false

    override fun onlinePlayerIds(): List<UUID> = server?.playerList?.players?.map { it.uuid } ?: []

    override fun playerDistanceSq(playerId: UUID, world: String, x: Double, y: Double, z: Double): Double? {
        val player = server?.playerList?.getPlayer(playerId) ?: return null
        if (RegionUtil.getPlayerLevelKey(player) != world) return null
        val dx = player.x - x
        val dy = player.y - y
        val dz = player.z - z
        return dx * dx + dy * dy + dz * dz
    }

    override fun sendDisplayInfo(playerId: UUID, display: DisplayData, forced: Boolean) {
        val vanilla = display as? VanillaDisplayData ?: return
        val player = server?.playerList?.getPlayer(playerId) ?: return
        VanillaPacketUtil.sendDisplayInfo([player], vanilla, forced)
    }

    override fun runOnMainThread(task: () -> Unit) {
        server?.execute(task)
    }

    override fun saveDisplay(display: DisplayData) {
        val vanilla = display as? VanillaDisplayData ?: return
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(vanilla) }
    }

    override fun createVirtualDisplay(id: UUID, ownerId: UUID): DisplayData? {
        val s = server ?: return null
        val worldKey = RegionUtil.getLevelKey(s.overworld())
        val pos = BlockPos(0, 0, 0)
        return VanillaDisplayData(id, ownerId, worldKey, pos, pos, 1, 1, Direction.NORTH, virtual = true)
    }
}
