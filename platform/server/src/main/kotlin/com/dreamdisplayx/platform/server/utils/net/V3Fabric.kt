package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.api.protocol.model.PacketDirection
import com.dreamdisplayx.core.protocol.common.PacketRegistry
import com.dreamdisplayx.platform.client.net.V3Payload
import io.github.arnodoelinger.platformweaver.FabricOnly
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking

/** Generation-3 batch receiver for Fabric servers. */
@FabricOnly
object FabricV3Networking {
    fun registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(V3Payload.TYPE) { payload, context ->
            runCatching {
                PacketRegistry.decodeV3(payload.bytes, PacketDirection.CLIENT_TO_SERVER).forEach {
                    FabricV2Networking.dispatch(context.player(), context.server(), it)
                }
            }
        }
    }
}
