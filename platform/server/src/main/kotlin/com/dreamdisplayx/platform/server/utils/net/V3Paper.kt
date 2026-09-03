package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.api.protocol.model.PacketDirection
import com.dreamdisplayx.core.protocol.common.PacketRegistry
import com.dreamdisplayx.platform.server.PaperServer
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory

/** Generation-3 batch receiver for Paper servers. */
@PaperOnly
@NullMarked
object PaperV3Networking : PluginMessageListener {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/PaperV3Networking")
    fun send(players: List<Player?>, packet: com.dreamdisplayx.core.protocol.common.packets.DreamPacket) {
        val bytes = runCatching { PacketRegistry.encodeV3(packet) }
            .onFailure { logger.warn("Failed to encode v3 packet", it) }.getOrNull() ?: return
        players.filterNotNull().forEach { player ->
            runCatching { player.sendPluginMessage(PaperServer.getInstance(), V3_CHANNEL, bytes) }
        }
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != V3_CHANNEL) return
        runCatching {
            PacketRegistry.decodeV3(message, PacketDirection.CLIENT_TO_SERVER).forEach {
                PaperV2Networking.dispatch(player, it)
            }
        }.onFailure { logger.warn("Failed to handle v3 packet from ${player.name}", it) }
    }
}
