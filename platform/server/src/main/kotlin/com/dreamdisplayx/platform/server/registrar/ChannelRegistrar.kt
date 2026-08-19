package com.dreamdisplayx.platform.server.registrar

import com.dreamdisplayx.platform.server.PaperServer
import com.dreamdisplayx.platform.server.proxy.PROXY_CHANNEL
import com.dreamdisplayx.platform.server.proxy.ProxyBridge
import com.dreamdisplayx.platform.server.utils.net.PacketReceiver
import com.dreamdisplayx.platform.server.utils.net.PaperV2Networking
import com.dreamdisplayx.platform.server.utils.net.V2_CHANNEL
import io.github.arnodoelinger.platformweaver.PaperOnly

/**
 * Manages the registration of plugin channels for incoming and outgoing messages.
 */
@PaperOnly
object ChannelRegistrar {
    /** Incoming plugin channels. */
    private val incomingChannels = listOf(
        "dreamdisplayx:sync",
        "dreamdisplayx:req_sync",
        "dreamdisplayx:delete",
        "dreamdisplayx:report",
        "dreamdisplayx:version",
        "dreamdisplayx:display_enabled",
        "dreamdisplayx:set_video",
        "dreamdisplayx:set_locked"
    )

    /** Outgoing plugin channels. */
    private val outgoingChannels = listOf(
        "dreamdisplayx:premium",
        "dreamdisplayx:is_admin",
        "dreamdisplayx:display_info",
        "dreamdisplayx:sync",
        "dreamdisplayx:delete",
        "dreamdisplayx:display_enabled",
        "dreamdisplayx:report_enabled",
        "dreamdisplayx:clear_cache"
    )

    /** Registers all incoming and outgoing plugin messaging channels for this plugin. */
    fun registerChannels(plugin: PaperServer) {
        val messenger = plugin.server.messenger
        val receiver = PacketReceiver(plugin)

        incomingChannels.forEach { messenger.registerIncomingPluginChannel(plugin, it, receiver) }
        outgoingChannels.forEach { messenger.registerOutgoingPluginChannel(plugin, it) }

        messenger.registerIncomingPluginChannel(plugin, V2_CHANNEL, PaperV2Networking)
        messenger.registerOutgoingPluginChannel(plugin, V2_CHANNEL)

        if (ProxyBridge.enabled) {
            messenger.registerIncomingPluginChannel(plugin, PROXY_CHANNEL, ProxyBridge)
            messenger.registerOutgoingPluginChannel(plugin, PROXY_CHANNEL)
        }
    }
}
