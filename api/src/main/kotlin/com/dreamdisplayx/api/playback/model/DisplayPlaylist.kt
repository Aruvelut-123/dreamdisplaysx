package com.dreamdisplayx.api.playback.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.storage.model.UuidStringSerializer
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Server-side playlist for one display: ordered media items, the playing index, the end-of-playlist
 * behavior, and who may enqueue. Persisted per display in the server database (SQLite / MySQL).
 *
 * @since 1.9.6
 */
@DreamDisplaysXUnstableApi
@Serializable
data class DisplayPlaylist(
    /** The display this playlist belongs to. */
    @Serializable(with = UuidStringSerializer::class)
    val displayId: UUID = UUID(0L, 0L),

    /** Ordered queue items; the list order is the queue order. */
    val items: List<PlaylistItemRecord> = emptyList(),

    /** Index into [items] currently playing, or -1 when nothing is playing. */
    val currentIndex: Int = -1,

    /** What happens when the current item finishes. */
    val endBehavior: PlaylistEndBehavior = PlaylistEndBehavior.CONTINUE,

    /** Who may add items to this playlist. */
    val enqueuePolicy: PlaylistEnqueuePolicy = PlaylistEnqueuePolicy.OWNER_ONLY,

    /**
     * Whether playlist mode is active for this display. When true, picking a video adds it to the
     * queue (and starts playback when the queue was idle); when false the display plays directly
     * like a single video and the queue does not auto-advance.
     */
    val enabled: Boolean = true,
)

/**
 * One playlist entry.
 *
 * @since 1.9.6
 */
@DreamDisplaysXUnstableApi
@Serializable
data class PlaylistItemRecord(
    /** Stable identity of this item, used by client remove / move / skip commands. */
    @Serializable(with = UuidStringSerializer::class)
    val itemId: UUID = UUID(0L, 0L),

    /** Media URL to play. */
    val url: String = "",

    /** Optional audio language for the media. */
    val lang: String = "",

    /** Optional display title resolved by the requester's client. */
    val title: String = "",

    /** True while waiting for the display owner's approval (enqueue policy [PlaylistEnqueuePolicy.OWNER_APPROVAL]). */
    val pending: Boolean = false,

    /** The player who added the item. */
    @Serializable(with = UuidStringSerializer::class)
    val requesterId: UUID = UUID(0L, 0L),
)
