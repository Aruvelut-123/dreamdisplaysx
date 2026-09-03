@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.dreamdisplayx.core.protocol.common.packets

import com.dreamdisplayx.core.protocol.common.UuidSerializer
import com.dreamdisplayx.core.protocol.common.ZERO_UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.UUID

/**
 * Shared-content playback snapshot for V3 clients. A single timeline snapshot can address several
 * displays carrying the same URL, avoiding independent drift between those displays.
 */
@Serializable
data class SameContentState(
    @ProtoNumber(1) val groupId: String = "",
    @ProtoNumber(2) val displayIds: List<@Serializable(UuidSerializer::class) UUID> = emptyList(),
    @ProtoNumber(3) val url: String = "",
    @ProtoNumber(4) val lang: String = "",
    @ProtoNumber(5) val positionMs: Long = 0,
    @ProtoNumber(6) val serverTimeMs: Long = 0,
    @ProtoNumber(7) val durationMs: Long = 0,
    @ProtoNumber(8) val paused: Boolean = true,
    @ProtoNumber(9) val loop: Boolean = true,
    @ProtoNumber(10) val mode: Int = 0,
) : DreamPacket
