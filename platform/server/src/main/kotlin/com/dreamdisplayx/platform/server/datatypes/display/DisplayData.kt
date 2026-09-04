package com.dreamdisplayx.platform.server.datatypes.display

import com.dreamdisplayx.api.display.model.property.DisplayRotation
import com.dreamdisplayx.api.playback.model.PlaybackAction
import com.dreamdisplayx.api.playback.model.PlaybackMode
import com.dreamdisplayx.api.playback.policy.PlaybackPermissions
import java.util.*
import kotlin.time.Instant

/**
 * Shared display data.
 *
 * Per-platform concrete classes ([PaperDisplayData], [VanillaDisplayData]) carry platform-specific
 * position / box types; shared state and identity live on this interface.
 */
interface DisplayData {
    /** Identifier of the display. */
    val id: UUID

    /** Identifier of the display owner. */
    val ownerId: UUID

    /** Display [width] in blocks. */
    val width: Int

    /** Display [height] in blocks. */
    val height: Int

    /** Content rotation; only meaningful for floor / ceiling (`UP` / `DOWN`) facings. */
    val rotation: DisplayRotation

    /** True for synthetic displays backing a URL-only fullscreen broadcast; the world position is a placeholder. */
    val virtual: Boolean

    /** The display's URL. */
    var url: String

    /** Video's language code. */
    var lang: String

    /** Optional, space-free alias usable anywhere a display id is accepted (see [com.dreamdisplayx.platform.server.managers.DisplayManager.resolveByIdOrPrefix]). */
    var name: String?

    /** The persistent base playback mode. Source of truth; never [PlaybackMode.WATCH_PARTY]. */
    var mode: PlaybackMode

    /**
     * Who may change this display. Region membership behind [DisplayAccess.REGION] is resolved live
     * against the display's location on every permission check, never stored — a stored snapshot goes
     * stale the moment a region is created, resized, or has its member list edited.
     */
    var access: DisplayAccess

    /** Legacy mirror of [access] for frozen-v1 peers, which only knew locked / unlocked. */
    var isLocked: Boolean
        get() = access != DisplayAccess.EVERYONE
        set(value) {
            access = DisplayAccess.fromLegacyLocked(value)
        }

    /** Duration of the video. */
    var duration: Long?

    /** Playback position in nanoseconds, persisted so a server restart resumes rather than replays from the start. */
    var seekPositionNanos: Long

    /** Pending scheduled-playback start; null when no schedule is set. One-shot, cleared once it fires. */
    var scheduledStart: Instant?

    /** The action ([PlaybackAction.PLAY] / [PlaybackAction.PAUSE]) [scheduledStart] will apply. */
    var scheduledAction: PlaybackAction?

    /** Whether this display uses the synchronized playback mode. */
    val isSync: Boolean get() = mode == PlaybackMode.SYNCED

    /** Max video height clients must not exceed (0 = uncapped, 360 for [PlaybackMode.BROADCAST]). */
    val qualityCap: Int; get() = if (mode == PlaybackMode.BROADCAST) PlaybackPermissions.BROADCAST_QUALITY_CAP else 0
}

/**
 * Short, human-facing label for a display: its [DisplayData.name] when set (via `/display name`),
 * otherwise the first 8 hex characters of its [DisplayData.id].
 */
val DisplayData.shortLabel: String get() = name ?: id.toString().take(8)

/**
 * Base shared by [PaperDisplayData] and [VanillaDisplayData], holding the mutable playback /
 * content fields common to both so platform subclasses only add their position types.
 */
abstract class BaseDisplayData(override val virtual: Boolean = false) : DisplayData {
    /** The display's URL. */
    override var url: String = ""

    /** Video's language code. */
    override var lang: String = ""

    /** Optional, space-free alias usable anywhere a display id is accepted. */
    override var name: String? = null

    /** The persistent base playback mode. */
    override var mode: PlaybackMode = PlaybackMode.LOCAL

    /** Who may change this display. */
    override var access: DisplayAccess = DisplayAccess.DEFAULT

    /** Duration of the video. */
    override var duration: Long? = null

    /** Persisted playback position in nanoseconds (see [DisplayData.seekPositionNanos]). */
    override var seekPositionNanos: Long = 0L

    /** Pending scheduled-playback start; null when no schedule is set. */
    override var scheduledStart: Instant? = null

    /** The action [scheduledStart] will apply. */
    override var scheduledAction: PlaybackAction? = null
}
