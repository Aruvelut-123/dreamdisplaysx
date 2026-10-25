package com.dreamdisplays.platform.server.storage

import com.dreamdisplays.shared.storage.DisplayData
import com.dreamdisplays.core.services.DisplayStorage
import com.dreamdisplays.util.json.JsonFileStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.util.*

/**
 * In-memory registry and JSON persistence for the server-authoritative [DisplayData] of every display,
 * keyed by server id and stored in `server-{serverId}-displays.json`.
 *
 * The "current" server is set by [load]; lookups and per-display saves operate against it.
 */
object ServerDisplayStore {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val SCHEMA_VERSION = 1
    private val jsonFiles = JsonFileStore()
    private val displayMapSerializer = MapSerializer(String.serializer(), DisplayData.serializer())

    /** Loads the display registry for [serverId] from disk and marks it as the current server. */
    fun load(serverId: String) {
        val loaded: Map<String, DisplayData>? =
            jsonFiles.readVersioned(readableServerFile(serverId), displayMapSerializer, SCHEMA_VERSION, logger)
        val displays = HashMap<UUID, DisplayData>()
        loaded?.forEach { (key, value) ->
            runCatching { displays[UUID.fromString(key)] = value }
                .onFailure { logger.error("Invalid UUID in server displays: $key.") }
        }
        DisplayStorage.load(serverId, displays)
        logger.info("Loaded ${displays.size} displays for server: $serverId.")
    }

    /** Persists the display registry for [serverId] to disk. */
    fun save(serverId: String) {
        if (!jsonFiles.ensureDir(logger)) return
        val displays = DisplayStorage.snapshot(serverId)
        val toSave = displays.entries.associate { (k, v) -> k.toString() to v }
        jsonFiles.writeVersioned(safeServerFile(serverId), displayMapSerializer, toSave, SCHEMA_VERSION, logger)
    }

    /** Returns the cached [DisplayData] for [displayUuid] on the current server, or null if absent. */
    fun getDisplayData(displayUuid: UUID): DisplayData? = DisplayStorage.getDisplayData(displayUuid)

    /** Stores [data] for [displayUuid] in the current server's registry and persists it to disk. */
    fun saveDisplayData(displayUuid: UUID, data: DisplayData) {
        DisplayStorage.saveDisplayData(displayUuid, data)
        DisplayStorage.currentServerId()?.let(::save)
    }

    /** Removes [displayUuid] from every server registry, persisting each registry it was present in. */
    fun remove(displayUuid: UUID): Boolean {
        val removed = DisplayStorage.removeDisplay(displayUuid)
        if (removed) DisplayStorage.currentServerId()?.let(::save)
        return removed
    }

    private fun readableServerFile(serverId: String): File {
        val safe = safeServerFile(serverId)
        val legacy = jsonFiles.file("server-$serverId-displays.json")
        return if (safe.exists() || !legacy.exists()) safe else legacy
    }

    private fun safeServerFile(serverId: String): File =
        jsonFiles.file("server-${safeServerFileId(serverId)}-displays.json")

    private fun safeServerFileId(serverId: String): String {
        val slug = serverId
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .take(48)
            .ifEmpty { "server" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(serverId.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return "$slug-$hash"
    }
}
