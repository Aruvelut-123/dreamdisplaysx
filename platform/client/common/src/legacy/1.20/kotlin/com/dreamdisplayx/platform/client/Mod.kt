package com.dreamdisplayx.platform.client

import net.fabricmc.fabric.api.networking.v1.FabricPacket

/**
 * The base type every outbound payload implements on this Minecraft era.
 *
 * 1.20.1 predates `CustomPacketPayload`, so the Fabric API's `FabricPacket` plays that role here.
 */
typealias OutgoingPayload = FabricPacket

/** Custom packet sender. */
fun interface Mod {
    /** Sends [packet] to the connected server over the platform's networking. */
    fun sendPacket(packet: OutgoingPayload)
}