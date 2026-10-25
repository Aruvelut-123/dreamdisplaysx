package com.dreamdisplays.platform.server.storage

import com.dreamdisplays.shared.playback.FullscreenSession
import com.dreamdisplays.util.json.JsonFileStore
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory

/**
 * JSON persistence for non-transient fullscreen broadcast sessions, so they survive a server
 * restart. Modeled on [ServerDisplayStore]'s versioned envelope, but flat (no per-server keying) -
 * fullscreen sessions aren't part of a display's own record.
 */
object FullscreenSessionStore {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val SCHEMA_VERSION = 1
    private const val FILE_NAME = "fullscreen-sessions.json"
    private val jsonFiles = JsonFileStore()
    private val listSerializer = ListSerializer(FullscreenSession.serializer())

    /** Loads every persisted session record, or an empty list if none are saved yet. */
    fun load(): List<FullscreenSession> =
        jsonFiles.readVersioned(jsonFiles.file(FILE_NAME), listSerializer, SCHEMA_VERSION, logger) ?: []

    /** Overwrites the store with [records]. */
    fun save(records: List<FullscreenSession>) {
        if (!jsonFiles.ensureDir(logger)) return
        jsonFiles.writeVersioned(jsonFiles.file(FILE_NAME), listSerializer, records, SCHEMA_VERSION, logger)
    }
}
