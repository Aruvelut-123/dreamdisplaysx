package com.dreamdisplays.platform.client.storage

import com.dreamdisplays.util.json.JsonFileStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory

/**
 * Local client-only record of watched video IDs, powers suggestions panel's recency and uniqueness.
 */
object WatchedVideoStore {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val FILE_NAME = "watched-videos.json"
    private const val SCHEMA_VERSION = 1
    private const val MAX_ENTRIES = 2000
    private val jsonFiles = JsonFileStore()
    private val idsSerializer = ListSerializer(String.serializer())
    private val watched = LinkedHashSet<String>()
    private val lock = Any()

    /** Loads the watched-ID list from disk into memory, replacing any current state. */
    fun load() {
        val loaded = jsonFiles.readVersioned(jsonFiles.file(FILE_NAME), idsSerializer, SCHEMA_VERSION, logger)
            ?: return
        synchronized(lock) {
            watched.clear()
            watched.addAll(loaded)
        }
    }

    private fun save(snapshot: List<String>) {
        if (!jsonFiles.ensureDir(logger)) return
        jsonFiles.writeVersioned(jsonFiles.file(FILE_NAME), idsSerializer, snapshot, SCHEMA_VERSION, logger)
    }

    /** Returns true if [videoId] has been marked watched. */
    fun isWatched(videoId: String): Boolean = synchronized(lock) { videoId in watched }

    /**
     * Marks [videoId] watched, bumping it to most-recent if already present, trimming the oldest
     * entry once [MAX_ENTRIES] is exceeded, and persisting the change.
     */
    fun markWatched(videoId: String) {
        if (videoId.isBlank()) return
        val snapshot = synchronized(lock) {
            watched.remove(videoId)
            watched.add(videoId)
            while (watched.size > MAX_ENTRIES) {
                val oldest = watched.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
            watched.toList()
        }
        save(snapshot)
    }
}
