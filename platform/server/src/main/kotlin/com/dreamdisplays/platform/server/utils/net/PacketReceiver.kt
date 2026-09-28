package com.dreamdisplays.platform.server.utils.net

import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import org.jspecify.annotations.NullMarked

/** The protocol-v1 handshake channel. */
const val LEGACY_VERSION_CHANNEL: String = "dreamdisplays:version"

/**
 * Protocol v1 is no longer supported. Every client sends the v1 `version` packet after its v2
 * hello, so one arriving from a player that never said hello means a v1-only client.
 */
@PaperOnly
@NullMarked
class PacketReceiver : PluginMessageListener {
    /** Tells a v1-only client to update; v2 players already negotiated and are ignored. */
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != LEGACY_VERSION_CHANNEL || V2PlayerTracker.isV2(player.uniqueId)) return
        DisplayActions.notifyOutdatedClient(player)
    }
}
