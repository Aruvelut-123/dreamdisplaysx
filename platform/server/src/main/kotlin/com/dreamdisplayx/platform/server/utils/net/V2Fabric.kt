package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.api.capability.ServerFeature
import com.dreamdisplayx.api.playback.model.FullscreenAckAction
import com.dreamdisplayx.api.playback.model.PlaybackAction
import com.dreamdisplayx.api.playback.model.PlaybackMode
import com.dreamdisplayx.api.playback.model.WatchPartyAction
import com.dreamdisplayx.api.protocol.model.PacketDirection
import com.dreamdisplayx.core.protocol.common.PacketRegistry
import com.dreamdisplayx.core.protocol.common.packets.*
import com.dreamdisplayx.platform.client.net.V2Payload
import com.dreamdisplayx.platform.client.net.V3Payload
import com.dreamdisplayx.platform.server.credentials.CredentialActions
import com.dreamdisplayx.platform.server.VanillaServerState
import com.dreamdisplayx.platform.server.managers.DisplayManager
import com.dreamdisplayx.platform.server.managers.PlayerManager
import com.dreamdisplayx.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplayx.platform.server.playback.PipPinManager
import com.dreamdisplayx.platform.server.proxy.VanillaProxyBridge
import io.github.arnodoelinger.platformweaver.FabricOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

/**
 * Protocol-v2 networking for the `Fabric` flavor: one envelope payload in both directions.
 * Business logic is shared with [VanillaDisplayActions].
 */
@FabricOnly
object FabricV2Networking {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/FabricV2Networking")

    /** Encodes [packet] once and sends it to every player in [players]. */
    fun send(players: List<ServerPlayer>, packet: DreamPacket) {
        if (players.isEmpty()) return
        val v3Players = players.filter { V2PlayerTracker.isV3(it.uuid) }
        val v2Players = players.filterNot { V2PlayerTracker.isV3(it.uuid) }
        if (v3Players.isNotEmpty()) {
            val bytes = runCatching { PacketRegistry.encodeV3(packet) }
                .onFailure { logger.warn("Failed to encode v3 packet", it) }
                .getOrNull()
            if (bytes != null) v3Players.forEach { player ->
                runCatching { ServerPlayNetworking.send(player, V3Payload(bytes)) }
            }
        }
        if (v2Players.isNotEmpty()) {
            val bytes = runCatching { PacketRegistry.encode(packet) }
                .onFailure { logger.warn("Failed to encode v2 packet", it) }
                .getOrNull()
            if (bytes != null) v2Players.forEach { player ->
                runCatching { ServerPlayNetworking.send(player, V2Payload(bytes)) }
            }
        }
    }

    /**
     * Encodes [packets] once into a generation-3 batch envelope and sends it to every negotiated-v3
     * player; the remaining players get the packets one-by-one over v2. Used by the join-time display
     * stream so a chunk of displays travels in a single frame instead of one per display.
     */
    fun sendBatch(players: List<ServerPlayer>, packets: List<DreamPacket>) {
        if (players.isEmpty() || packets.isEmpty()) return
        val v3Players = players.filter { V2PlayerTracker.isV3(it.uuid) }
        val v2Players = players.filterNot { V2PlayerTracker.isV3(it.uuid) }
        if (v3Players.isNotEmpty()) {
            val bytes = runCatching { PacketRegistry.encodeV3(packets) }
                .onFailure { logger.warn("Failed to encode v3 batch", it) }
                .getOrNull()
            if (bytes != null) v3Players.forEach { player ->
                runCatching { ServerPlayNetworking.send(player, V3Payload(bytes)) }
            }
        }
        if (v2Players.isNotEmpty()) packets.forEach { packet -> send(v2Players, packet) }
    }

    /** Registers the single v2 envelope receiver. */
    fun registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(V2Payload.TYPE) { payload, context ->
            runCatching {
                dispatch(
                    context.player(),
                    context.server(),
                    PacketRegistry.decode(payload.bytes, PacketDirection.CLIENT_TO_SERVER) ?: return@runCatching,
                )
            }.onFailure { e ->
                logger.warn("Failed to handle v2 packet", e)
            }
        }
    }

    /** Routes a decoded serverbound packet to the shared action handlers. */
    internal fun dispatch(player: ServerPlayer, server: MinecraftServer, packet: DreamPacket) {
        when (packet) {
            is ClientHello -> handleHello(player, server, packet)
            is RequestSync -> VanillaDisplayActions.requestSync(player, packet.id)
            is ReportDuration -> VanillaDisplayActions.reportDuration(player, packet.id, packet.durationMs)
            is ReportPosition -> VanillaDisplayActions.reportPosition(player, packet.id, packet.positionNanos)
            is DisplayDelete -> VanillaDisplayActions.delete(player, server, packet.id)
            is ReportDisplay -> DisplayManager.report(packet.id, player, server)
            is SetVideo -> VanillaDisplayActions.setVideo(player, server, packet.id, packet.url, packet.lang)
            is SetLocked -> VanillaDisplayActions.setAccess(player, server, packet.id, packet.accessLevel())
            is SetMode -> VanillaDisplayActions.setMode(
                player,
                server,
                packet.id,
                PlaybackMode.fromWire(packet.mode),
                packet.positionMs
            )

            is PlaybackCommand -> PlaybackAction.fromWire(packet.action)?.let {
                VanillaDisplayActions.playbackCommand(player, packet.id, it, packet.positionMs)
            }

            is WatchPartyStart -> VanillaDisplayActions.watchPartyStart(player, packet.id, packet.url, packet.lang)
            is WatchPartyControl -> WatchPartyAction.fromWire(packet.action)?.let {
                VanillaDisplayActions.watchPartyControl(player, packet.id, it, packet.positionMs)
            }

            is SetDisplaysEnabled -> PlayerManager.setDisplaysEnabled(player, packet.enabled)
            is FullscreenAck -> FullscreenBroadcastManager.handleAck(
                packet.sessionId, player.uuid, FullscreenAckAction.fromWire(packet.action),
            )

            is PipPin -> if (packet.pinned) {
                PipPinManager.pin(player.uuid, packet.id)
            } else {
                PipPinManager.unpin(player.uuid, packet.id)
            }

            else -> logger.debug("Ignoring non-serverbound v2 packet {}.", packet::class.simpleName)
        }
    }

    /**
     * Marks [player] as a v2 peer, replies with the [ServerHello] and the display batch. Fullscreen re-delivery also
     * runs here for reconnecting viewers.
     */
    private fun handleHello(player: ServerPlayer, server: MinecraftServer, hello: ClientHello) {
        V2PlayerTracker.markV2(player.uuid, hello)
        send(
            listOf(player),
            ServerHello(
                generation = hello.generation.coerceAtMost(com.dreamdisplayx.api.protocol.ProtocolGeneration.CURRENT),
                isPremium = VanillaDisplayActions.isPremium(player),
                isAdmin = VanillaDisplayActions.isAdmin(player),
                isReportingEnabled = VanillaServerState.config.settings.webhookUrl.isNotEmpty(),
                allowedFeatures = ServerFeature.playbackFeatureWires,
                defaultVolume = VanillaServerState.config.settings.defaultVolume,
                defaultStretchMode = VanillaServerState.config.settings.defaultStretchMode,
                maxDisplays = VanillaDisplayActions.maxDisplaysFor(player),
            ),
        )
        send(listOf(player), CredentialActions.snapshotFor(player.uuid.toString()))
        VanillaDisplayActions.recordVersionAndCheckUpdates(player, hello.modVersion)
        VanillaDisplayActions.sendAllDisplays(player, server)
        FullscreenBroadcastManager.onPlayerJoin(player.uuid)
        PipPinManager.onPlayerJoin(player.uuid)
        VanillaProxyBridge.onPlayerReady(player, server)
    }
}
