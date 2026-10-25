package com.dreamdisplays.media.source.youtube.cache

import com.dreamdisplays.media.source.youtube.model.YtStream
import com.dreamdisplays.media.source.youtube.model.YtStreams
import com.dreamdisplays.util.AsyncMemo
import com.dreamdisplays.util.DreamCoroutines
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import org.slf4j.LoggerFactory

/**
 * In-memory -> disk -> [resolve] cache for resolved YouTube stream lists, with separate staleness
 * rules for live (short-lived tokens) and partial ("walled") results (rechecked on a cadence in
 * case YouTube's PO-token wall lifts). Extracted from [com.dreamdisplays.media.source.youtube.YtDlp]
 * to isolate caching from the `NewPipeExtractor` / `yt-dlp` resolution strategy itself.
 */
class YtFormatCache(private val resolve: suspend (String) -> List<YtStream>) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val cacheTtlMs: Long = FormatDiskCache.DEFAULT_TTL_MS
    private val formatMemo = AsyncMemo<String, List<YtStream>>(200, cacheTtlMs, DreamCoroutines.clientIo, "fetch")
    private val fetchedAtMs: Cache<String, Long> = Caffeine.newBuilder().maximumSize(300).build()

    /** Returns stream list for videoUrl (in-memory -> disk -> [resolve]). */
    @Throws(IOException::class)
    fun fetch(videoUrl: String): List<YtStream> {
        var streams = loadFromDisk(videoUrl) ?: formatMemo.getBlocking(videoUrl) { fetchAndPersist(it) }
        if (isStaleLive(videoUrl, streams) || isStalePartial(videoUrl, streams)) {
            invalidate(videoUrl)
            streams = formatMemo.getBlocking(videoUrl) { fetchAndPersist(it) }
        }
        return streams
    }

    /** Fires a background fetch for [videoUrl] if not already cached, so it is ready before [fetch] is called. */
    fun prefetch(videoUrl: String) {
        val cached = formatMemo.peekFresh(videoUrl) ?: loadFromDisk(videoUrl)
        if (cached != null && !isStaleLive(videoUrl, cached) && !isStalePartial(videoUrl, cached)) return
        if (cached != null) invalidate(videoUrl)
        formatMemo.load(videoUrl) { fetchAndPersist(it) }.invokeOnCompletion { e ->
            if (e != null && e !is CancellationException) {
                logger.debug("Prefetch failed for {}: {}", videoUrl, e.message)
            }
        }
    }

    /** Removes [videoUrl] from the in-memory format cache, in-flight map, and disk cache. */
    fun invalidate(videoUrl: String) {
        formatMemo.invalidate(videoUrl)
        fetchedAtMs.invalidate(videoUrl)
        FormatDiskCache.deleteEntry(videoUrl)
    }

    private fun isStaleLive(videoUrl: String, streams: List<YtStream>): Boolean {
        if (streams.none { it.isLive }) return false
        val at = fetchedAtMs.getIfPresent(videoUrl) ?: return true
        return System.currentTimeMillis() - at > FormatDiskCache.LIVE_TTL_MS
    }

    private fun isStalePartial(videoUrl: String, streams: List<YtStream>): Boolean {
        if (streams.isEmpty() || offersFullResult(streams)) return false
        val at = fetchedAtMs.getIfPresent(videoUrl) ?: return true
        return System.currentTimeMillis() - at > FormatDiskCache.PARTIAL_TTL_MS
    }

    private fun offersFullResult(streams: List<YtStream>): Boolean =
        streams.any { it.isLive } || YtStreams.offersQualityLadder(streams)

    private fun loadFromDisk(videoUrl: String): List<YtStream>? {
        formatMemo.peekFresh(videoUrl)?.let { return it }
        val fromDisk = FormatDiskCache.load(videoUrl, cacheTtlMs)?.takeIf { it.isNotEmpty() } ?: return null
        val immutable = fromDisk.toList()
        formatMemo.put(videoUrl, immutable)
        fetchedAtMs.put(videoUrl, System.currentTimeMillis())
        return immutable
    }

    @Throws(IOException::class)
    private suspend fun fetchAndPersist(videoUrl: String): List<YtStream> {
        val streams = resolve(videoUrl).toList()
        fetchedAtMs.put(videoUrl, System.currentTimeMillis())
        if (streams.isNotEmpty()) FormatDiskCache.saveAsync(videoUrl, streams)
        return streams
    }
}
