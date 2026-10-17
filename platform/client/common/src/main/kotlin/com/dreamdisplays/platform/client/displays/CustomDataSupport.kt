package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.display.data.DisplayCustomDataLimits
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo

/**
 * The addon data of a display packet, or nothing at all when the server sent something outside the limits
 * ([DisplayCustomDataLimits]): a misbehaving server must not be able to make every client hold large blobs.
 */
internal fun acceptedCustomData(packet: DisplayInfo): Map<String, ByteArray> =
    if (DisplayCustomDataLimits.isValid(packet.customData)) packet.customData else emptyMap()
