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
)
