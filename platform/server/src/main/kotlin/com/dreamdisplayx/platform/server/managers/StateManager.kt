package com.dreamdisplayx.platform.server.managers

import com.dreamdisplayx.api.playback.model.PlaybackMode
import com.dreamdisplayx.api.playback.policy.PlaybackPermissions
import com.dreamdisplayx.core.protocol.common.packets.DisplaySync
import com.dreamdisplayx.platform.server.PaperServer
import com.dreamdisplayx.platform.server.datatypes.display.DisplayData
import com.dreamdisplayx.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplayx.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplayx.platform.server.datatypes.state.StateData
import com.dreamdisplayx.platform.server.datatypes.sync.SyncData
import com.dreamdisplayx.platform.server.managers.DisplayManager.getDisplayData
import com.dreamdisplayx.platform.server.managers.DisplayManager.getReceivers
import com.dreamdisplayx.platform.server.playback.PlaybackContexts
import com.dreamdisplayx.platform.server.playback.WatchPartyManager
import com.dreamdisplayx.platform.server.utils.PlatformUtil
import com.dreamdisplayx.platform.server.utils.net.PacketUtil
import com.dreamdisplayx.platform.server.utils.net.VanillaPacketUtil
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages server-side playback state for synced displays. Processes sync packets from clients,
 * rate-limits rebroadcasts, and periodically pushes the authoritative position to keep
 * all viewers in lockstep.
 */
@NullMarked
object StateManager {
    /** Play state for each display, keyed by display UUID. */
    private val playStates: MutableMap<UUID, StateData> = ConcurrentHashMap()

    /** Last broadcast timestamp for each display, keyed by display UUID. Used for rate-limiting rebroadcasts. */
    private val lastSyncBroadcast: MutableMap<UUID, Long> = ConcurrentHashMap()

    /** Minimum interval between rebroadcasts of sync packets for a display, in milliseconds. */
    private const val SYNC_MIN_INTERVAL_MS = 250L

    /** Forgets a removed display's v1 sync state, so [tickBroadcast] stops carrying its dead entry. */
    fun remove(displayId: UUID) {
        playStates.remove(displayId)
        lastSyncBroadcast.remove(displayId)
    }

    /** Interval for periodic sync broadcasts to keep clients in lockstep, in milliseconds. */
    private const val PERIODIC_BROADCAST_INTERVAL_MS = 2000L

    /** Sanity ceiling (24h in ns) for client-reported position and duration. */
    private const val MAX_TIME_NS = 24L * 60 * 60 * 1_000_000_000L

    /**
     * Validates a sync [packet] sent by [senderId], updates the per-display state, and applies
     * the rebroadcast rate limit. Returns true when the caller should rebroadcast to other
     * receivers, false when the packet was rejected, cleared the state, or was throttled.
     */
    private fun applySyncPacket(packet: SyncData, senderId: UUID, isAdmin: Boolean): Boolean {
        val displayId = packet.id ?: return false
        val data = getDisplayData(displayId)

        if (data == null) {
            playStates.remove(displayId)
            lastSyncBroadcast.remove(displayId)
            return false
        }

        if (data.mode != PlaybackMode.SYNCED || WatchPartyManager.hasSession(displayId)) {
            playStates.remove(displayId)
            lastSyncBroadcast.remove(displayId)
            return false
        }

        val context = PlaybackContexts.of(data, senderId, isAdmin)
        if (!PlaybackPermissions.canSeek(context)) return false

        if (!packet.isSync) {
            playStates.remove(displayId)
            lastSyncBroadcast.remove(displayId)
            return false
        }

        if (packet.currentTime < 0 || packet.limitTime < 0
            || packet.currentTime > MAX_TIME_NS || packet.limitTime > MAX_TIME_NS
        ) return false

        val state = playStates.computeIfAbsent(displayId) { id -> StateData(id) }
        state.update(packet)
        data.duration = packet.limitTime

        val now = System.currentTimeMillis()
        val lastBroadcast = lastSyncBroadcast[displayId] ?: 0L
        if (now - lastBroadcast < SYNC_MIN_INTERVAL_MS) return false
        lastSyncBroadcast[displayId] = now
        return true
    }

    /**
     * Handles a sync packet from [player]: validates it, updates the per-display state,
     * and rebroadcasts to other receivers (rate-limited to avoid packet floods).
     */
    @PaperOnly
    @JvmStatic
    fun processSyncPacket(packet: SyncData, player: Player) {
        if (!applySyncPacket(
                packet,
                player.uniqueId,
                player.hasPermission(PaperServer.config.permissions.delete)
            )
        ) return
        val data = getDisplayData(packet.id) ?: return
        val receivers = getReceivers(data as PaperDisplayData)
        PacketUtil.sendSync(receivers.filter { it.uniqueId != player.uniqueId }, packet)
    }

    /**
     * Handles a sync packet from [player]: validates it, updates the per-display state,
     * and rebroadcasts to other receivers (rate-limited to avoid packet floods).
     */
    fun processSyncPacket(packet: SyncData, player: ServerPlayer, server: MinecraftServer, isAdmin: Boolean) {
        if (!applySyncPacket(packet, player.uuid, isAdmin)) return
        val data = getDisplayData(packet.id) ?: return
        val receivers = getReceivers(data as VanillaDisplayData, server)
            .filter { it.uuid != player.uuid }
        VanillaPacketUtil.sendSync(receivers, packet)
    }

    /** Sends the current sync packet for display [id] to a single [player], if state exists. */
    @PaperOnly
    @JvmStatic
    fun sendSyncPacket(id: UUID?, player: Player?) {
        val displayId = id ?: return
        val state = playStates[displayId] ?: return

        val packet = state.createPacket()
        PacketUtil.sendSync(listOf(player), packet)
    }

    /** Sends the current sync packet for display [id] to a single [player], if state exists. */
    fun sendSyncPacket(id: UUID?, player: ServerPlayer) {
        val displayId = id ?: return
        val state = playStates[displayId] ?: return
        val display = getDisplayData(displayId) as? VanillaDisplayData
        val packet = state.createPacket(display)
        VanillaPacketUtil.sendSync(listOf(player), packet)
    }

    /**
     * Resets the server-side clock for [displayId] to 0 and returns the freshly reset state,
     * or null if the display no longer exists.
     */
    private fun resetState(displayId: UUID): StateData? {
        val display = getDisplayData(displayId) ?: return null
        val state = playStates.computeIfAbsent(displayId) { id -> StateData(id) }
        state.update(SyncData(displayId, true, false, 0L, 0L))
        display.duration = 0L
        lastSyncBroadcast[displayId] = System.currentTimeMillis()
        return state
    }

    /** Resets the server-side clock for [displayId] to 0 (called when owner switches video). */
    @PaperOnly
    @JvmStatic
    fun resetAndBroadcast(displayId: UUID, receivers: List<Player>) {
        val state = resetState(displayId) ?: return
        PacketUtil.sendSync(receivers, state.createPacket())
    }

    /** Resets and broadcasts over the correct `Paper` / `Folia` player scheduling path. */
    @PaperOnly
    fun resetAndBroadcast(display: PaperDisplayData) {
        if (PlatformUtil.isFolia) resetAndBroadcastForTrackedPlayers(display)
        else resetAndBroadcast(display.id, getReceivers(display))
    }

    /** `Folia`-safe reset broadcast: location checks and plugin messages run on each player's entity scheduler. */
    @PaperOnly
    fun resetAndBroadcastForTrackedPlayers(display: PaperDisplayData) {
        val state = resetState(display.id) ?: return
        val packet = syncPacketOf(state.createPacket()) ?: return
        DisplayManager.sendV2ToTrackedNearbyPlayers(display, packet)
    }

    /** Resets the server-side clock for [displayId] to 0 (called when owner switches video). */
    @JvmName("resetAndBroadcastVanilla")
    fun resetAndBroadcast(displayId: UUID, receivers: List<ServerPlayer>) {
        val state = resetState(displayId) ?: return
        val display = getDisplayData(displayId) as? VanillaDisplayData
        VanillaPacketUtil.sendSync(receivers, state.createPacket(display))
    }

    /**
     * Iterates every active sync display whose periodic broadcast interval has elapsed, marks it
     * as just-broadcast, and invokes [action] with the display's state and data. Centralizes the
     * rate-limit bookkeeping shared by the platform-specific tick handlers.
     */
    private inline fun forEachBroadcastDue(action: (StateData, DisplayData) -> Unit) {
        if (playStates.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((displayId, state) in playStates) {
            val last = lastSyncBroadcast[displayId] ?: 0L
            if (now - last < PERIODIC_BROADCAST_INTERVAL_MS) continue
            val display = getDisplayData(displayId) ?: continue
            if (!display.isSync) continue
            lastSyncBroadcast[displayId] = now
            action(state, display)
        }
    }

    /**
     * Periodically broadcasts the current sync packet for every active sync display to keep
     * clients in lockstep. Without this, clients drift after the initial sync.
     */
    @PaperOnly
    @JvmStatic
    fun tickBroadcast() = forEachBroadcastDue { state, display ->
        val receivers = getReceivers(display as PaperDisplayData)
        if (receivers.isNotEmpty()) PacketUtil.sendSync(receivers, state.createPacket())
    }

    /**
     * `Folia` variant of [tickBroadcast]. The due-state bookkeeping runs on the global coordinator,
     * while location checks and plugin messages are dispatched to each player's entity scheduler.
     */
    @PaperOnly
    fun tickBroadcastForTrackedPlayers() = forEachBroadcastDue { state, display ->
        val packet = syncPacketOf(state.createPacket()) ?: return@forEachBroadcastDue
        DisplayManager.sendV2ToTrackedNearbyPlayers(display as PaperDisplayData, packet)
    }

    /** Converts the state model to the V2 packet used by the tracked-player transport. */
    private fun syncPacketOf(syncData: SyncData): DisplaySync? {
        val id = syncData.id ?: return null
        return DisplaySync(
            id = id,
            isSync = syncData.isSync,
            isPaused = !syncData.currentState,
            currentTimeMs = syncData.currentTime,
            durationMs = syncData.limitTime,
        )
    }

    /**
     * Periodically broadcasts the current sync packet for every active sync display to keep
     * clients in lockstep. Without this, clients drift after the initial sync.
     */
    fun tickBroadcast(server: MinecraftServer) = forEachBroadcastDue { state, display ->
        val vanillaDisplay = display as VanillaDisplayData
        val receivers = getReceivers(vanillaDisplay, server)
        if (receivers.isNotEmpty()) VanillaPacketUtil.sendSync(receivers, state.createPacket(vanillaDisplay))
    }
}
