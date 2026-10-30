package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.api.playback.model.PlaybackAction
import com.dreamdisplayx.api.playback.model.PlaybackMode
import com.dreamdisplayx.api.playback.model.WatchPartyAction
import com.dreamdisplayx.core.protocol.common.PacketRegistry
import com.dreamdisplayx.core.protocol.common.packets.*
import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.net.LegacyProbe
import com.dreamdisplayx.platform.client.net.ProxyPayload
import com.dreamdisplayx.platform.client.net.V2Payload
import com.dreamdisplayx.platform.client.net.compat.CustomPacketPayload
import com.dreamdisplayx.platform.client.net.compat.CompatPayloads
import com.dreamdisplayx.platform.server.VanillaServerState
import com.dreamdisplayx.platform.server.managers.DisplayManager
import com.dreamdisplayx.platform.server.managers.PlayerManager
import com.dreamdisplayx.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplayx.platform.server.playback.PipPinManager
import com.dreamdisplayx.platform.server.proxy.VanillaProxyBridge
import com.dreamdisplayx.platform.server.utils.RegionUtil
import com.dreamdisplayx.platform.server.utils.MessageUtil
import com.dreamdisplayx.api.capability.ServerFeature
import com.dreamdisplayx.api.playback.model.FullscreenAckAction
import com.dreamdisplayx.api.protocol.model.PacketDirection
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import org.slf4j.LoggerFactory

object ForgeChannels {
    private const val PROTOCOL = "1"
    private val logger = LoggerFactory.getLogger(javaClass)

    private val isClientDist: Boolean = FMLEnvironment.dist.isClient

    fun register() {
        channel(V2Payload.TYPE.id(), ::onV2)
        channel(ProxyPayload.TYPE.id(), ::onProxy)
        channel(LegacyProbe.Version.PACKET_ID.id(), ::onLegacyVersion)
        channel(LegacyProbe.Premium.PACKET_ID.id(), ::onLegacyPremium)
    }

    fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) {
        player.connection.send(ClientboundCustomPayloadPacket(payload.type().id(), CompatPayloads.encode(payload)))
    }

    private fun channel(id: ResourceLocation, handler: (ByteArray, NetworkEvent.Context) -> Unit) {
        NetworkRegistry.newEventChannel(
            id,
            { PROTOCOL },
            NetworkRegistry.acceptMissingOr(PROTOCOL),
            NetworkRegistry.acceptMissingOr(PROTOCOL),
        ).addListener { event: NetworkEvent ->
            val payload = event.payload ?: return@addListener
            val context = event.source.get()
            val bytes = ByteArray(payload.readableBytes()).also(payload::readBytes)
            context.enqueueWork {
                runCatching { handler(bytes, context) }.onFailure { logger.warn("Failed to handle {} packet.", id, it) }
            }
            context.packetHandled = true
        }
    }

    private fun onV2(bytes: ByteArray, context: NetworkEvent.Context) {
        if (context.direction == NetworkDirection.PLAY_TO_SERVER) {
            val player = context.sender ?: return
            ForgeV2Networking.dispatch(
                player,
                RegionUtil.playerServer(player),
                PacketRegistry.decode(bytes, PacketDirection.CLIENT_TO_SERVER) ?: return,
            )
        } else if (isClientDist) {
            Initializer.onV2Packet(bytes)
        }
    }

    private fun onProxy(bytes: ByteArray, context: NetworkEvent.Context) {
        if (context.direction != NetworkDirection.PLAY_TO_SERVER) return
        val player = context.sender ?: return
        VanillaProxyBridge.onMessage(player, RegionUtil.playerServer(player), bytes)
    }

    private fun onLegacyVersion(bytes: ByteArray, context: NetworkEvent.Context) {
        if (context.direction != NetworkDirection.PLAY_TO_SERVER) return
        val player = context.sender as? ServerPlayer ?: return
        if (V2PlayerTracker.isV2(player.uuid)) return
        MessageUtil.sendMessage(player, "outdatedClient")
    }

    private fun onLegacyPremium(bytes: ByteArray, context: NetworkEvent.Context) {
        if (context.direction == NetworkDirection.PLAY_TO_CLIENT && isClientDist) Initializer.onLegacyServerDetected()
    }
}

object ForgeNetworkingAdapter : VanillaNetworkingAdapter {
    override fun sendV2(players: List<ServerPlayer>, packet: DreamPacket) {
        ForgeV2Networking.send(players, packet)
    }

    override fun sendV3Batch(players: List<ServerPlayer>, packets: List<DreamPacket>) {
        // Legacy Forge builds have no generation-3 envelope; fall back to per-packet v2 frames.
        packets.forEach { ForgeV2Networking.send(players, it) }
    }

    override fun sendProxy(player: ServerPlayer, packet: CustomPacketPayload) {
        runCatching { ForgeChannels.sendToPlayer(player, packet) }
    }
}

object ForgeV2Networking {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun send(players: List<ServerPlayer>, packet: DreamPacket) {
        if (players.isEmpty()) return
        val bytes = runCatching { PacketRegistry.encode(packet) }
            .onFailure { logger.warn("Failed to encode v2 packet", it) }
            .getOrNull() ?: return
        players.forEach { player ->
            runCatching { ForgeChannels.sendToPlayer(player, V2Payload(bytes)) }
        }
    }

    fun dispatch(player: ServerPlayer, server: MinecraftServer, packet: DreamPacket) {
        when (packet) {
            is ClientHello -> handleHello(player, server, packet)
            is RequestSync -> VanillaDisplayActions.requestSync(player, packet.id)
            is ReportDuration -> VanillaDisplayActions.reportDuration(player, packet.id, packet.durationMs)
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

            is SetDisplaysEnabled -> PlayerManager.setDisplaysEnabled(player.uuid, packet.enabled)
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

    private fun handleHello(player: ServerPlayer, server: MinecraftServer, hello: ClientHello) {
        if (V2PlayerTracker.isV2(player.uuid)) return
        V2PlayerTracker.markV2(player.uuid, hello)
        send(
            [player],
            ServerHello(
                isPremium = VanillaDisplayActions.isPremium(player),
                isAdmin = VanillaDisplayActions.isAdmin(player),
                isReportingEnabled = VanillaServerState.config.settings.webhookUrl.isNotEmpty(),
                allowedFeatures = ServerFeature.playbackFeatureWires,
                defaultVolume = VanillaServerState.config.settings.defaultVolume,
                maxDisplays = VanillaDisplayActions.maxDisplaysFor(player),
            ),
        )
        VanillaDisplayActions.recordVersionAndCheckUpdates(player, hello.modVersion)
        VanillaDisplayActions.sendAllDisplays(player, server)
        FullscreenBroadcastManager.onPlayerJoin(player.uuid)
        PipPinManager.onPlayerJoin(player.uuid)
        VanillaProxyBridge.onPlayerReady(player, server)
    }
}
