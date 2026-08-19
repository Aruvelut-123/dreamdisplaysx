package com.dreamdisplayx.media.source.bilibili

import com.dreamdisplayx.api.media.source.model.MediaSource
import com.dreamdisplayx.media.source.platform.PlatformMetadataCache
import com.dreamdisplayx.media.source.platform.PlatformVideoMetadata
import com.dreamdisplayx.media.source.platform.YtDlpMetadataFallback

/**
 * Metadata cache for Bilibili VODs and live rooms, so a pasted BIlibili link shows a real title /
 * thumbnail / live badge without waiting for stream resolution. Keyed so a VOD, its parts, and a
 * live room never collide.
 *
 * @since 1.9.x
 */
object BilibiliMetadataCache {
    private val cache = PlatformMetadataCache(
        name = "Bilibili",
        liveTtlSeconds = 60,
        staticTtlMinutes = 30,
        fetch = { key -> sourceFor(key)?.let { BilibiliApi.metadata(it) ?: YtDlpMetadataFallback.fetch(it.url) } },
    )

    /** The cache key for [source]: `video:<bvid|av<avid>>:<part>` for a VOD, `room:<id>` for a live room, `ep:<id>` / `ss:<id>` for bangumi. */
    fun cacheKey(source: MediaSource.Bilibili): String? = when {
        source.bvid != null -> "video:${source.bvid}:${source.part ?: 1}"
        source.avid != null -> "video:av${source.avid}:${source.part ?: 1}"
        source.roomId != null -> "room:${source.roomId}"
        source.epId != null -> "ep:${source.epId}"
        source.seasonId != null -> "ss:${source.seasonId}"
        else -> "short:${source.url}"
    }

    /** Reconstructs the [MediaSource.Bilibili] the fetch needs from a [cacheKey]. */
    private fun sourceFor(key: String): MediaSource.Bilibili? = when {
        key.startsWith("video:av") -> {
            val (avid, part) = key.removePrefix("video:av").split(':')
            avid.toLongOrNull()?.let {
                MediaSource.Bilibili(url = "https://www.bilibili.com/video/av$avid", avid = it, part = part.toIntOrNull())
            }
        }

        key.startsWith("video:") -> {
            val (bvid, part) = key.removePrefix("video:").split(':')
            MediaSource.Bilibili(url = "https://www.bilibili.com/video/$bvid", bvid = bvid, part = part.toIntOrNull())
        }

        key.startsWith("room:") -> {
            val roomId = key.removePrefix("room:").toLongOrNull()
            roomId?.let { MediaSource.Bilibili(url = "https://live.bilibili.com/$roomId", roomId = it) }
        }

        key.startsWith("ep:") -> {
            val epId = key.removePrefix("ep:").toLongOrNull()
            epId?.let { MediaSource.Bilibili(url = "https://www.bilibili.com/bangumi/play/ep$epId", epId = it) }
        }

        key.startsWith("ss:") -> {
            val seasonId = key.removePrefix("ss:").toLongOrNull()
            seasonId?.let { MediaSource.Bilibili(url = "https://www.bilibili.com/bangumi/play/ss$seasonId", seasonId = it) }
        }

        key.startsWith("short:") -> MediaSource.Bilibili(url = key.removePrefix("short:"))
        else -> null
    }

    /** Returns cached metadata for [key], or null when not yet fetched. */
    fun get(key: String): PlatformVideoMetadata? = cache.get(key)

    /** Seeds [metadata] for [source] (used by [BilibiliResolver] after it fetches the API). */
    fun put(source: MediaSource.Bilibili, metadata: PlatformVideoMetadata) {
        cacheKey(source)?.let { cache.put(it, metadata) }
    }

    /** Warms the cache for [source] in the background. */
    fun requestAsync(source: MediaSource.Bilibili) {
        cacheKey(source)?.let { cache.requestAsync(it) }
    }

    /** Fetches metadata for [source] now (blocking); for background search threads. */
    fun resolveBlocking(source: MediaSource.Bilibili): PlatformVideoMetadata? =
        cacheKey(source)?.let { cache.resolveBlocking(it) }
}
