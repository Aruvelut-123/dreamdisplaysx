package com.dreamdisplays.platform.server.datatypes.display

import com.dreamdisplays.api.display.model.property.DisplayRotation
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.shared.playback.PlaybackPermissions
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/** Shared display data. */
interface DisplayData {
    val id: UUID
    val ownerId: UUID
    val width: Int
    val height: Int
    val depth: Int get() = 1
    val conforming: Boolean get() = false
    val rotation: DisplayRotation
    val virtual: Boolean
    var url: String
    val customData: MutableMap<String, ByteArray>
    var lang: String
    var name: String?
    var mode: PlaybackMode
    var access: DisplayAccess

    /** Boolean view of [access]: `true` unless everyone may use the display. */
    var isLocked: Boolean
        get() = access != DisplayAccess.EVERYONE
        set(value) {
            access = DisplayAccess.fromLegacyLocked(value)
        }

    var duration: Long?
    var scheduledStart: Instant?
    var scheduledAction: PlaybackAction?

    /** Boolean view of [mode]: `true` only when the mode is [PlaybackMode.SYNCED]. */
    val isSync: Boolean get() = mode == PlaybackMode.SYNCED

    /** Max video height clients must not exceed (0 = uncapped, 360 for [PlaybackMode.BROADCAST]). */
    val qualityCap: Int; get() = if (mode == PlaybackMode.BROADCAST) PlaybackPermissions.BROADCAST_QUALITY_CAP else 0
}

/**
 * Short, human-facing label for a display: its [DisplayData.name] when set (via `/display name`),
 * otherwise the first 8 hex characters of its [DisplayData.id].
 */
val DisplayData.shortLabel: String get() = name ?: id.toString().take(8)

abstract class BaseDisplayData(override val virtual: Boolean = false) : DisplayData {
    override var url: String = ""
    override val customData: MutableMap<String, ByteArray> = ConcurrentHashMap()
    override var lang: String = ""
    override var name: String? = null
    override var mode: PlaybackMode = PlaybackMode.LOCAL
    override var access: DisplayAccess = DisplayAccess.DEFAULT
    override var duration: Long? = null
    override var scheduledStart: Instant? = null
    override var scheduledAction: PlaybackAction? = null
}
