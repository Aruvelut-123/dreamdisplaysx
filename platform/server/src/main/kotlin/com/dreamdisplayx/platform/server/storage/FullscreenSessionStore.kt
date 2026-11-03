package com.dreamdisplayx.platform.server.storage

import com.dreamdisplayx.util.log.DreamLog
import com.dreamdisplayx.api.playback.model.FullscreenSessionRecord
import com.dreamdisplayx.util.json.JsonFileStore
import kotlinx.serialization.builtins.ListSerializer

/**
 * JSON persistence for non-transient fullscreen broadcast sessions, so they survive a server
 * restart. Modeled on [ServerDisplayStore]'s versioned envelope, but flat (no per-server keying) -
 * fullscreen sessions aren't part of a display's own record.
 */
object FullscreenSessionStore {
    private val logger = DreamLog.of("FullscreenSessionStore")
    private const val SCHEMA_VERSION = 1
    private const val FILE_NAME = "fullscreen-sessions.json"
    private val jsonFiles = JsonFileStore()
    private val listSerializer = ListSerializer(FullscreenSessionRecord.serializer())

    /** Loads every persisted session record, or an empty list if none are saved yet. */
    fun load(): List<FullscreenSessionRecord> =
        jsonFiles.readVersioned(jsonFiles.file(FILE_NAME), listSerializer, SCHEMA_VERSION, logger.delegate) ?: emptyList()

    /** Overwrites the store with [records]. */
    fun save(records: List<FullscreenSessionRecord>) {
        if (!jsonFiles.ensureDir(logger.delegate)) return
        jsonFiles.writeVersioned(jsonFiles.file(FILE_NAME), listSerializer, records, SCHEMA_VERSION, logger.delegate)
    }
}
