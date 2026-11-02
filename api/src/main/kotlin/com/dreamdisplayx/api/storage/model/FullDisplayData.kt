package com.dreamdisplayx.api.storage.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.display.model.property.DisplayFacing
import com.dreamdisplayx.api.playback.model.PlaybackMode
import kotlinx.serialization.Serializable
import java.util.*

/**
 * Full persisted snapshot of a display; holds all state needed to recreate it.
 *
 * @since 1.0.x
 */
@DreamDisplaysXUnstableApi
@Serializable
data class FullDisplayData(
    @Serializable(with = UuidStringSerializer::class)
    var uuid: UUID = UUID(0L, 0L),
    var x: Int = 0,
    var y: Int = 0,
    var z: Int = 0,
    var facing: DisplayFacing = DisplayFacing.NORTH,
    var width: Int = 1,
    var height: Int = 1,
    var depth: Int = 1,
    var conforming: Boolean = false,
    var videoUrl: String = "",
    var lang: String = "",
    var volume: Float = 0.5f,
    var quality: String = "1080",
    var brightness: Float = 1.0f,
    var muted: Boolean = false,
    var mode: PlaybackMode? = PlaybackMode.LOCAL,
    @Serializable(with = UuidStringSerializer::class)
    var ownerUuid: UUID = uuid,
    var currentTimeNanos: Long = 0,
    var rotation: Int = 0,
    var qualityCap: Int = 0,
    var dimensionKey: String = "",
    /**
     * Valkyrien Skies ship the block coordinates belong to, or `0` for a display anchored to the world.
     *
     * The block position is always expressed in the *structure* space of that ship, so nothing else has
     * to change when a ship moves: only rendering has to multiply by the ship-to-world matrix. `0` is the
     * world sentinel, so every row written before this column existed keeps meaning "not on a ship".
     */
    var shipId: Long = 0L,
)
