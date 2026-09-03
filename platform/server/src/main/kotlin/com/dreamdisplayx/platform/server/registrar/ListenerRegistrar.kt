package com.dreamdisplayx.platform.server.registrar

import com.dreamdisplayx.platform.server.PaperServer
import com.dreamdisplayx.platform.server.listeners.PlayerListener
import com.dreamdisplayx.platform.server.listeners.ProtectionListener
import com.dreamdisplayx.platform.server.listeners.SelectionListener
import com.dreamdisplayx.platform.server.listeners.RemoteControlListener
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit

/**
 * Registers event listeners.
 */
@PaperOnly
object ListenerRegistrar {
    /** Registers selection, protection, and player listeners with `Bukkit`. */
    fun registerListeners(plugin: PaperServer) {
        val pm = Bukkit.getPluginManager()
        pm.registerEvents(SelectionListener(plugin), plugin)
        pm.registerEvents(RemoteControlListener(plugin), plugin)
        pm.registerEvents(ProtectionListener(), plugin)
        pm.registerEvents(PlayerListener(), plugin)
    }
}
