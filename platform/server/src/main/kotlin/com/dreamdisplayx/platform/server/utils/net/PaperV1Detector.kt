package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.platform.server.PaperServer
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import org.jspecify.annotations.NullMarked
import java.util.concurrent.ConcurrentHashMap

/** Detects the legacy v1 handshake without restoring any v1 packet handling. */
@PaperOnly
@NullMarked
object PaperV1Detector : PluginMessageListener {
    private val notified = ConcurrentHashMap.newKeySet<java.util.UUID>()
    private val plugin: PaperServer by lazy { PaperServer.getInstance() }

    fun notify(player: Player) {
        if (notified.add(player.uniqueId)) {
            player.sendMessage("Dream DisplaysX: V1 protocol is not supported anymore. Please update your client.")
        }
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel == "dreamdisplayx:version") notify(player)
    }
}
