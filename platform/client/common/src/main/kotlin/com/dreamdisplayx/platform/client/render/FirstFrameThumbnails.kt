package com.dreamdisplayx.platform.client.render

import com.dreamdisplayx.media.player.process.LibVlcFrameExtractor
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Extracts the first frame of a direct-link video as its search-card thumbnail.
 *
 * Platform results (YouTube / Twitch / Bilibili) all carry a thumbnail, but a pasted direct link — a
 * custom media file with no cover art — has none to show. This schedules one one-shot libvlc
 * extraction per unique card key and hands the resulting JPEG bytes to [Thumbnails.registerBytes] so
 * they flow through the exact same decode / GPU-register / disk-cache pipeline as any other thumbnail.
 *
 * Guard rails:
 *  - a single worker thread: one full libvlc player at a time (player setup is ~1-2s, far too heavy
 *    to parallelize across cards);
 *  - requests coalesced by key, so re-rendering or re-hovering the same card never re-extracts;
 *  - extraction is strictly best-effort: a pasted link may be an HTML page, a dead host, or a file
 *    libvlc cannot demux. Every failure resolves to `null` and the card simply keeps its placeholder
 *    shimmer — the search panel must never block or crash on a thumbnail.
 */
object FirstFrameThumbnails {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/FirstFrameThumbnails")

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DDX-FirstFrame").apply { isDaemon = true }
    }

    /** In-flight extraction: key -> callbacks awaiting the JPEG bytes (`null` = failed). */
    private val pending = ConcurrentHashMap<String, MutableList<(ByteArray?) -> Unit>>()

    /**
     * Schedules a first-frame extraction of [url], reporting the JPEG bytes (or `null` on any
     * failure) to [onFrame] once. Concurrent requests for the same [key] are coalesced into a single
     * extraction. May be called from any thread; [onFrame] runs on the extraction worker thread.
     */
    fun request(key: String, url: String, onFrame: (ByteArray?) -> Unit) {
        val list = pending.computeIfAbsent(key) { Collections.synchronizedList(mutableListOf()) }
        synchronized(list) { list.add(onFrame) }
        if (list.size > 1) return // a worker is already extracting this video
        executor.execute {
            val jpeg = runCatching { LibVlcFrameExtractor.extractJpeg(url, 0L, 320, 180) }
                .getOrElse { t ->
                    logger.warn("First-frame extraction failed for $key: ${t.message}")
                    null
                }
            val callbacks = synchronized(list) { pending.remove(key) ?: emptyList() }
            callbacks.forEach { it(jpeg) }
        }
    }
}
