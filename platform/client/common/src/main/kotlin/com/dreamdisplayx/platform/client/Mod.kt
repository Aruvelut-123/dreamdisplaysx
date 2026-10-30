package com.dreamdisplayx.platform.client

//? if >=1.20.5 {
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//?} else
/*import com.dreamdisplayx.platform.client.net.compat.CustomPacketPayload*/

/** Custom packet sender. */
fun interface Mod {
    /** Sends [packet] to the connected server over the platform's networking. */
    fun sendPacket(packet: CustomPacketPayload)
}
