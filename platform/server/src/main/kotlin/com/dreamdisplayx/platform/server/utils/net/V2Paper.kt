package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.api.capability.ServerFeature
import com.dreamdisplayx.api.playback.model.FullscreenAckAction
import com.dreamdisplayx.api.playback.model.PlaybackAction
import com.dreamdisplayx.api.playback.model.PlaybackMode
import com.dreamdisplayx.api.playback.model.WatchPartyAction
import com.dreamdisplayx.api.protocol.model.PacketDirection
import com.dreamdisplayx.core.protocol.common.PacketRegistry
import com.dreamdisplayx.core.protocol.common.packets.*
import com.dreamdisplayx.platform.server.PaperServer
import com.dreamdisplayx.platform.server.credentials.CredentialActions
import com.dreamdisplayx.platform.server.managers.DisplayManager
import com.dreamdisplayx.platform.server.managers.PlayerManager
import com.dreamdisplayx.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplayx.platform.server.playback.PipPinManager
import com.dreamdisplayx.platform.server.proxy.ProxyBridge
import com.dreamdisplayx.platform.server.utils.WorldGuardRegions
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory

/** The single protocol-v2 plugin-message channel. */
const val V2_CHANNEL: String = "dreamdisplayx:v2"
const val V3_CHANNEL: String = "dreamdisplayx:v3"

/**
 * Protocol-v2 networking for the Paper flavor: receives envelope frames on [V2_CHANNEL], answers
 * the [ClientHello] handshake, and sends v2 packets to negotiated players. Business logic is
 * shared with [DisplayActions].
 */
@PaperOnly
@NullMarked
object PaperV2Networking : PluginMessageListener {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/PaperV2Networking")
    private val plugin: PaperServer by lazy { PaperServer.getInstance() }

    /** Encodes [packet] once and sends it to every non-null player in [players]. */
    fun send(players: List<Player?>, packet: DreamPacket) {
        val v3Players = players.filterNotNull().filter { V2PlayerTracker.isV3(it.uniqueId) }
        val v2Players = players.filterNotNull().filterNot { V2PlayerTracker.isV3(it.uniqueId) }
        if (v3Players.isNotEmpty()) PaperV3Networking.send(v3Players, packet)
        if (v2Players.isEmpty()) return
        val bytes = runCatching { PacketRegistry.encode(packet) }
            .onFailure { logger.warn("Failed to encode v2 packet", it) }
            .getOrNull() ?: return
        v2Players.forEach { player ->
            runCatching { player.sendPluginMessage(plugin, V2_CHANNEL, bytes) }
                .onFailure { logger.warn("Failed to send v2 packet to ${player.name}", it) }
        }
    }

    /** The capability snapshot for [player], rebuilt from permissions and config. */
    fun buildServerHello(player: Player): ServerHello = ServerHello(
        isPremium = player.hasPermission(PaperServer.config.permissions.premium),
        isAdmin = player.hasPermission(PaperServer.config.permissions.deleteOthers),
        isReportingEnabled = PaperServer.config.settings.webhookUrl.isNotEmpty(),
        allowedFeatures = serverFeatureWires(),
        defaultVolume = PaperServer.config.settings.defaultVolume,
        defaultStretchMode = PaperServer.config.settings.defaultStretchMode,
        maxDisplays = maxDisplaysFor(player.hasPermission(PaperServer.config.permissions.createBypass)),
    )

    /**
     * The feature tokens for this server: the unconditional playback set, plus region access only
     * where `WorldGuard` is actually installed to answer membership questions.
     */
    private fun serverFeatureWires(): List<String> =
        if (WorldGuardRegions.isAvailable()) {
            ServerFeature.playbackFeatureWires + ServerFeature.REGION_ACCESS.wire
        } else {
            ServerFeature.playbackFeatureWires
        }

    /** [ServerHello.maxDisplays] for a player: `-1` (unlimited) when [hasBypass] or no cap is configured. */
    private fun maxDisplaysFor(hasBypass: Boolean): Int {
        val cap = PaperServer.config.settings.maxDisplaysPerPlayer
        return if (hasBypass || cap <= 0) -1 else cap
    }

    /** Dispatches a decoded serverbound packet. */
    internal fun dispatch(player: Player, packet: DreamPacket) {
        when (packet) {
            is ClientHello -> handleHello(player, packet)
            is RequestSync -> DisplayActions.requestSync(player, packet.id)
            is ReportDuration -> DisplayActions.reportDuration(player, packet.id, packet.durationMs)
            is ReportPosition -> DisplayActions.reportPosition(player, packet.id, packet.positionNanos)
            is DisplayDelete -> DisplayActions.delete(player, packet.id)
            is ReportDisplay -> DisplayManager.report(packet.id, player)
            is SetVideo -> DisplayActions.setVideo(player, packet.id, packet.url, packet.lang)
            is SetLocked -> DisplayActions.setAccess(player, packet.id, packet.accessLevel())
            is SetMode -> DisplayActions.setMode(player, packet.id, PlaybackMode.fromWire(packet.mode), packet.positionMs)
            is PlaybackCommand -> PlaybackAction.fromWire(packet.action)?.let { DisplayActions.playbackCommand(player, packet.id, it, packet.positionMs) }
            is WatchPartyStart -> DisplayActions.watchPartyStart(player, packet.id, packet.url, packet.lang)
            is WatchPartyControl -> WatchPartyAction.fromWire(packet.action)?.let { DisplayActions.watchPartyControl(player, packet.id, it, packet.positionMs) }
            is SetDisplaysEnabled -> PlayerManager.setDisplaysEnabled(player.uniqueId, packet.enabled)
            is FullscreenAck -> FullscreenBroadcastManager.handleAck(packet.sessionId, player.uniqueId, FullscreenAckAction.fromWire(packet.action))
            is PipPin -> if (packet.pinned) PipPinManager.pin(player.uniqueId, packet.id) else PipPinManager.unpin(player.uniqueId, packet.id)
            else -> logger.debug("Ignoring non-serverbound protocol packet {}.", packet::class.simpleName)
        }
    }

    /** Decodes an envelope frame and dispatches the packet; unknown type ids are skipped. */
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != V2_CHANNEL) return
        val packet = runCatching { PacketRegistry.decode(message, PacketDirection.CLIENT_TO_SERVER) }
            .onFailure { logger.warn("Failed to decode v2 packet from ${player.name}", it) }
            .getOrNull() ?: return

        when (packet) {
            is ClientHello -> handleHello(player, packet)
            is RequestSync -> DisplayActions.requestSync(player, packet.id)
            is ReportDuration -> DisplayActions.reportDuration(player, packet.id, packet.durationMs)
            is ReportPosition -> DisplayActions.reportPosition(player, packet.id, packet.positionNanos)
            is DisplayDelete -> DisplayActions.delete(player, packet.id)
            is ReportDisplay -> DisplayManager.report(packet.id, player)
            is SetVideo -> DisplayActions.setVideo(player, packet.id, packet.url, packet.lang)
            is SetLocked -> DisplayActions.setAccess(player, packet.id, packet.accessLevel())
            is SetMode -> DisplayActions.setMode(
                player,
                packet.id,
                PlaybackMode.fromWire(packet.mode),
                packet.positionMs
            )

            is PlaybackCommand -> PlaybackAction.fromWire(packet.action)?.let {
                DisplayActions.playbackCommand(player, packet.id, it, packet.positionMs)
            }

            is WatchPartyStart -> DisplayActions.watchPartyStart(player, packet.id, packet.url, packet.lang)
            is WatchPartyControl -> WatchPartyAction.fromWire(packet.action)?.let {
                DisplayActions.watchPartyControl(player, packet.id, it, packet.positionMs)
            }

            is SetDisplaysEnabled -> PlayerManager.setDisplaysEnabled(player, packet.enabled)
            is FullscreenAck -> FullscreenBroadcastManager.handleAck(
                packet.sessionId, player.uniqueId, FullscreenAckAction.fromWire(packet.action),
            )

            is PipPin -> if (packet.pinned) {
                PipPinManager.pin(player.uniqueId, packet.id)
            } else {
                PipPinManager.unpin(player.uniqueId, packet.id)
            }

            else -> logger.debug("Ignoring non-serverbound v2 packet {}.", packet::class.simpleName)
        }
    }

    /**
     * Marks [player] as a v2 peer, replies with the [ServerHello] and the display batch, and runs the shared
     * version-check flow.
     */
    private fun handleHello(player: Player, hello: ClientHello) {
        V2PlayerTracker.markV2(player.uniqueId, hello)
        send(listOf(player), buildServerHello(player).copy(generation = hello.generation.coerceAtMost(com.dreamdisplayx.api.protocol.ProtocolGeneration.CURRENT)))
        send(listOf(player), CredentialActions.snapshotFor(player.uniqueId.toString()))
        DisplayActions.recordVersionAndCheckUpdates(player, hello.modVersion)
        DisplayActions.sendAllDisplays(player)
        FullscreenBroadcastManager.onPlayerJoin(player.uniqueId)
        PipPinManager.onPlayerJoin(player.uniqueId)
        ProxyBridge.onPlayerReady(player)
    }
}
