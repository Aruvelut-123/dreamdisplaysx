@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.dreamdisplayx.core.protocol.common.packets

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.core.protocol.common.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.UUID

/**
 * Experimental per-display playlist sync. Wire ints decode through
 * [com.dreamdisplayx.api.playback.model.PlaylistEndBehavior] /
 * [com.dreamdisplayx.api.playback.model.PlaylistEnqueuePolicy] /
 * [com.dreamdisplayx.api.playback.model.PlaylistCommandAction].
 */

/** Server snapshot of a display playlist and its policy. */
@DreamDisplaysXUnstableApi
@Serializable
data class PlaylistState(
    @ProtoNumber(1) @Serializable(UuidSerializer::class) val displayId: UUID = UUID(0, 0),
    @ProtoNumber(2) val items: List<PlaylistItem> = emptyList(),
    @ProtoNumber(3) val currentIndex: Int = -1,
    @ProtoNumber(4) val endBehavior: Int = 0,
    @ProtoNumber(5) val enqueuePolicy: Int = 2,
) : DreamPacket

/** One playlist entry as it travels on the wire. */
@DreamDisplaysXUnstableApi
@Serializable
data class PlaylistItem(
    @ProtoNumber(1) @Serializable(UuidSerializer::class) val itemId: UUID = UUID(0, 0),
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val lang: String = "",
    @ProtoNumber(4) val title: String = "",
    @ProtoNumber(5) val pending: Boolean = false,
    @ProtoNumber(6) @Serializable(UuidSerializer::class) val requesterId: UUID = UUID(0, 0),
)

/** Client playlist mutation/control request. */
@DreamDisplaysXUnstableApi
@Serializable
data class PlaylistCommand(
    @ProtoNumber(1) @Serializable(UuidSerializer::class) val displayId: UUID = UUID(0, 0),
    @ProtoNumber(2) val action: Int = 0,
    @ProtoNumber(3) @Serializable(UuidSerializer::class) val itemId: UUID = UUID(0, 0),
    @ProtoNumber(4) val url: String = "",
    @ProtoNumber(5) val lang: String = "",
    @ProtoNumber(6) val title: String = "",
    @ProtoNumber(7) val position: Int = -1,
    @ProtoNumber(8) val endBehavior: Int = 0,
    @ProtoNumber(9) val enqueuePolicy: Int = 2,
) : DreamPacket
