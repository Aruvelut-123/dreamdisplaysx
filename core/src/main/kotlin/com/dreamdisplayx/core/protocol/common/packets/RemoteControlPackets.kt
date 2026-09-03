@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.dreamdisplayx.core.protocol.common.packets

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.core.protocol.common.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.UUID

/** Experimental V3 instruction asking the client to open a remotely linked display. */
@DreamDisplaysXUnstableApi
@Serializable
data class RemoteControlOpen(
    @ProtoNumber(1) @Serializable(UuidSerializer::class) val displayId: UUID = UUID(0, 0),
    @ProtoNumber(2) val displayName: String = "",
) : DreamPacket
