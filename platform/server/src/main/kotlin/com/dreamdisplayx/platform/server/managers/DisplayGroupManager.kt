package com.dreamdisplayx.platform.server.managers

import com.dreamdisplayx.platform.server.PaperServer
import com.dreamdisplayx.platform.server.datatypes.display.PaperDisplayData
import io.github.arnodoelinger.platformweaver.PaperOnly
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** In-memory named display groups used by the V3 same-content playback commands. */
@PaperOnly
object DisplayGroupManager {
    private val groups = ConcurrentHashMap<String, MutableSet<UUID>>()

    fun create(name: String): Boolean = groups.putIfAbsent(name, ConcurrentHashMap.newKeySet()) == null

    fun delete(name: String): Boolean = groups.remove(name) != null

    fun names(): List<String> = groups.keys().toList().sorted()

    fun add(name: String, display: PaperDisplayData): Boolean {
        val members = groups[name] ?: return false
        return members.add(display.id)
    }

    fun remove(name: String, displayId: UUID): Boolean = groups[name]?.remove(displayId) == true

    fun members(name: String): List<PaperDisplayData> = groups[name].orEmpty().mapNotNull {
        DisplayManager.getDisplayData(it) as? PaperDisplayData
    }

    fun contains(name: String): Boolean = groups.containsKey(name)

    /** Updates every member to the same URL/language and re-announces its display state. */
    fun setVideo(name: String, url: String, lang: String) {
        members(name).forEach { display ->
            display.url = url
            display.lang = lang
            display.mode = com.dreamdisplayx.api.playback.model.PlaybackMode.SYNCED
            PaperServer.getInstance().storage.saveDisplay(display)
            DisplayManager.broadcastUpdate(display)
            com.dreamdisplayx.platform.server.playback.TimelineManager.onVideoChanged(display)
        }
    }
}
