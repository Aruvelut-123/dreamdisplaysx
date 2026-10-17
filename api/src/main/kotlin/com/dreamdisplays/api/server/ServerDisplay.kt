package com.dreamdisplays.api.server

import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackMode
import java.util.UUID

/**
 * A display as the server knows it: who owns it, where it is and what it shows. The server does not play video;
 * playback happens on the players' clients.
 *
 * @since 1.10.x
 */
@Unstable
interface ServerDisplay {
    /** Persistent ID. */
    val id: UUID

    /** The player who created the display. */
    val ownerId: UUID

    /** `false` once the display has been removed. */
    val isAlive: Boolean

    /** Name of the world the display is in. */
    val world: String

    /** [x] coordinate in the world. */
    val x: Double

    /** [y] coordinate in the world. */
    val y: Double

    /** [z] coordinate in the world. */
    val z: Double

    /** Width of display. */
    val width: Int

    /** Height of display. */
    val height: Int

    /** Optional short name, or `null`. */
    val name: String?

    /** The video link, or empty when none is set. */
    val url: String

    /** Preferred audio language code of the video. */
    val lang: String

    /** Persistent base playback mode. */
    val mode: PlaybackMode

    /** Who may change this display. */
    val access: DisplayAccess

    /**
     * Changes the video.
     *
     * The link goes through the same rules as a player's request (allowed hosts, custom-link policy);
     * a refused link answers [CommandResult.REFUSED].
     *
     * Saves, tells nearby players and restarts the timeline.
     */
    fun setUrl(url: String, lang: String = ""): CommandResult

    /** Changes who may use the display. */
    fun setAccess(access: DisplayAccess): CommandResult

    /** Removes the display and tells nearby players. */
    fun delete(): CommandResult
}
