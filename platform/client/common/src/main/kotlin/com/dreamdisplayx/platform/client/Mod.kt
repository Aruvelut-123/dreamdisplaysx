package com.dreamdisplayx.platform.client

import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * The base type every outbound payload implements on this Minecraft era.
 *
 * Minecraft 1.21 networking is payload-object based; 1.20.1 uses the Fabric API's `FabricPacket`
 * instead (see the matching legacy overlay).
 */
typealias OutgoingPayload = CustomPacketPayload

/** Custom packet sender. */
fun interface Mod {
    /** Sends [packet] to the connected server over the platform's networking. */
    fun sendPacket(packet: OutgoingPayload)
}