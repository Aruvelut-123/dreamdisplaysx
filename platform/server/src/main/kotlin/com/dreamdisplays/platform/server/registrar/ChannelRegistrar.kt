package com.dreamdisplays.platform.server.registrar

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.proxy.PROXY_CHANNEL
import com.dreamdisplays.platform.server.proxy.ProxyBridge
import com.dreamdisplays.platform.server.utils.net.LEGACY_VERSION_CHANNEL
import com.dreamdisplays.platform.server.utils.net.PacketReceiver
import com.dreamdisplays.platform.server.utils.net.PaperV2Networking
import com.dreamdisplays.platform.server.utils.net.V2_CHANNEL
import io.github.arnodoelinger.platformweaver.PaperOnly

/**
 * Manages the registration of plugin channels for incoming and outgoing messages.
 */
@PaperOnly
object ChannelRegistrar {
    /** Registers all incoming and outgoing plugin messaging channels for this plugin. */
    fun registerChannels(plugin: PaperServer) {
        val messenger = plugin.server.messenger
        messenger.registerIncomingPluginChannel(plugin, LEGACY_VERSION_CHANNEL, PacketReceiver())

        messenger.registerIncomingPluginChannel(plugin, V2_CHANNEL, PaperV2Networking)
        messenger.registerOutgoingPluginChannel(plugin, V2_CHANNEL)

        if (ProxyBridge.enabled) {
            messenger.registerIncomingPluginChannel(plugin, PROXY_CHANNEL, ProxyBridge)
            messenger.registerOutgoingPluginChannel(plugin, PROXY_CHANNEL)
        }
    }
}
