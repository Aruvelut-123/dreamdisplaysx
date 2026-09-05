package com.dreamdisplayx.platform.server.managers

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.playback.model.PlaybackMode
import com.dreamdisplayx.platform.server.datatypes.display.DisplayData
import com.dreamdisplayx.platform.server.playback.TimelineManager
import java.util.UUID

/** Experimental named display groups shared by Paper, Fabric, and NeoForge. */
@DreamDisplaysXUnstableApi
object DisplayGroupManager {
    fun create(name: String): Boolean = GroupRegistry.create(name)
    fun delete(name: String): Boolean = GroupRegistry.delete(name)
    fun names(): List<String> = GroupRegistry.names()
    fun contains(name: String): Boolean = GroupRegistry.contains(name)

    fun add(name: String, display: DisplayData): Boolean = GroupRegistry.add(name, display.id)
    fun remove(name: String, displayId: UUID): Boolean = GroupRegistry.remove(name, displayId)

    fun members(name: String): List<DisplayData> = GroupRegistry.memberIds(name).mapNotNull(DisplayManager::getDisplayData)

    /** Updates every member, then delegates persistence and transport to the active platform. */
    fun setVideo(
        name: String,
        url: String,
        lang: String,
        persistAndBroadcast: (DisplayData) -> Unit,
    ) {
        members(name).forEach { display ->
            display.url = url
            display.lang = lang
            display.mode = PlaybackMode.SYNCED
            persistAndBroadcast(display)
            TimelineManager.onVideoChanged(display)
        }
    }
}
