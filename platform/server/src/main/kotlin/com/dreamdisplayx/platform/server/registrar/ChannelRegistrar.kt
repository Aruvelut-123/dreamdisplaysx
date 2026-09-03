package com.dreamdisplayx.platform.server.registrar

import com.dreamdisplayx.platform.server.PaperServer
import com.dreamdisplayx.platform.server.proxy.PROXY_CHANNEL
import com.dreamdisplayx.platform.server.proxy.ProxyBridge
import com.dreamdisplayx.platform.server.utils.net.PaperV2Networking
import com.dreamdisplayx.platform.server.utils.net.PaperV3Networking
import com.dreamdisplayx.platform.server.utils.net.V2_CHANNEL
import com.dreamdisplayx.platform.server.utils.net.V3_CHANNEL
import com.dreamdisplayx.platform.server.utils.net.PaperV1Detector
import io.github.arnodoelinger.platformweaver.PaperOnly

/** Manages registration of the protocol-v2 and proxy plugin channels. */
@PaperOnly
object ChannelRegistrar {
    /** Registers plugin messaging channels for the protocol-v2 and optional proxy bridges. */
    fun registerChannels(plugin: PaperServer) {
        val messenger = plugin.server.messenger
        messenger.registerIncomingPluginChannel(plugin, V2_CHANNEL, PaperV2Networking)
        messenger.registerOutgoingPluginChannel(plugin, V2_CHANNEL)
        messenger.registerIncomingPluginChannel(plugin, V3_CHANNEL, PaperV3Networking)
        messenger.registerOutgoingPluginChannel(plugin, V3_CHANNEL)
        messenger.registerIncomingPluginChannel(plugin, "dreamdisplayx:version", PaperV1Detector)
        if (ProxyBridge.enabled) {
            messenger.registerIncomingPluginChannel(plugin, PROXY_CHANNEL, ProxyBridge)
            messenger.registerOutgoingPluginChannel(plugin, PROXY_CHANNEL)
        }
    }
}
