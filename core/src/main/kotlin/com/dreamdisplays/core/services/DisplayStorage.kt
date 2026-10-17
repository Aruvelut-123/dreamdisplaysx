package com.dreamdisplays.core.services

import com.dreamdisplays.shared.storage.DisplayData
import java.util.*
import com.dreamdisplays.shared.storage.StorageService as DisplayStorageContract

/** In-memory registry for server-authoritative display snapshots. */
object DisplayStorage : DisplayStorageContract {
    private val serverDisplays = HashMap<String, MutableMap<UUID, DisplayData>>()
    private var currentServerId: String? = null

    override fun load(serverId: String, displays: Map<UUID, DisplayData>) {
        currentServerId = serverId
        serverDisplays[serverId] = displays.toMutableMap()
    }

    override fun snapshot(serverId: String): Map<UUID, DisplayData> =
        serverDisplays[serverId]?.toMap() ?: emptyMap()

    override fun currentServerId(): String? = currentServerId

    override fun getDisplayData(displayUuid: UUID): DisplayData? {
        val serverId = currentServerId ?: return null
        return serverDisplays[serverId]?.get(displayUuid)
    }

    override fun saveDisplayData(displayUuid: UUID, data: DisplayData) {
        val serverId = currentServerId ?: return
        serverDisplays.getOrPut(serverId) { HashMap() }[displayUuid] = data
    }

    override fun removeDisplay(displayUuid: UUID): Boolean {
        var removed = false
        for (displays in serverDisplays.values) {
            removed = displays.remove(displayUuid) != null || removed
        }
        return removed
    }
}
