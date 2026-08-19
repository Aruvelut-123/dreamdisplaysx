@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplayx.core.protocol.common.packets

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Platform login credentials the server shares back with a v2 client. The server stores them
 * encrypted at rest; this packet carries the decrypted values only to the owning player's own
 * client, which uses them to unlock VIP / higher-quality playback (e.g. a Bilibili SESSDATA cookie).
 */
@Serializable
data class PlatformCredentials(
    /** Bilibili `SESSDATA` cookie value (empty when the player is not logged in). */
    @ProtoNumber(1) val bilibiliSessdata: String = "",
) : DreamPacket
