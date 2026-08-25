package com.dreamdisplayx.media.player.cdn

import com.dreamdisplayx.api.media.stream.model.MediaStream
import com.dreamdisplayx.api.security.policy.MediaHosts
import com.dreamdisplayx.util.net.DreamHttpClient
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Probes the CDN candidates of a stream before playback starts and reorders them fastest-first,
 * so the player opens the least-latency CDN instead of always trying the API's primary URL and
 * only failing over later.
 *
 * Measurement is a tiny Range request (32 bytes) that PDNs answer from their edge; the score is
 * cached per hostname for the lifetime of the process, so a session that plays several videos only
 * pays the probe cost once per CDN edge. Unreachable hosts are penalised by pushing them to the
 * back of the order (the player's existing stall-driven failover still runs).
 */
object CdnSpeedProbe {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/CdnSpeedProbe")

    /** Bytes asked from the CDN edge; enough to trip a real network round-trip, tiny enough to be cheap. */
    private const val PROBE_BYTES = 32

    /** Per-host probe budget; a CDN edge that cannot answer this fast is treated as slow. */
    private const val PROBE_TIMEOUT_MS = 2_500L

    /** Total budget for probing all candidates of one stream; beyond this we keep the current ordering. */
    private const val TOTAL_BUDGET_MS = 6_000L

    /** Cache of hostname → measured latency (µs) for the current run; prevents re-probing per video. */
    private val hostLatencyMicros = ConcurrentHashMap<String, Long>()

    /**
     * Returns [MediaStream]s (video/audio) whose CDN candidates are reordered with the fastest
     * hostname first. When no probe could be completed (no candidates, all failed, budget spent)
     * the original streams are returned unchanged.
     */
    fun reorderForPlayback(video: MediaStream?, audio: MediaStream?): Pair<MediaStream?, MediaStream?> {
        if (video == null && audio == null) return video to audio

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_BUDGET_MS)

        // Group all candidate URLs by hostname: one probe per distinct edge.
        val hostToUrls = LinkedHashMap<String, MutableList<String>>()
        for (url in candidateUrls(video) + candidateUrls(audio)) {
            if (url.isEmpty()) continue
            val host = MediaHosts.hostOf(url) ?: continue
            hostToUrls.getOrPut(host) { mutableListOf() }.add(url)
        }
        if (hostToUrls.size <= 1) return video to audio // nothing to compare

        // Score hosts, skipping already-known ones.
        val scores = LinkedHashMap<String, Long>()
        for ((host, urls) in hostToUrls) {
            if (System.nanoTime() > deadlineNanos) break
            val cached = hostLatencyMicros[host]
            if (cached != null) {
                scores[host] = cached
                continue
            }
            val latency = probe(host, urls.first(), deadlineNanos)
            if (latency >= 0) {
                hostLatencyMicros[host] = latency
                scores[host] = latency
            }
        }

        // A host with no score (unreachable / budget exhausted) sorts to the end.
        val order = hostToUrls.keys.sortedBy { scores[it] ?: Long.MAX_VALUE }
        if (order.size < 2 || scores.isEmpty()) return video to audio

        if (logger.isDebugEnabled) {
            val detail = order.joinToString(", ") { "$it=${scores[it]?.let { s -> "${s / 1000.0}ms" } ?: "?"}" }
            logger.debug("CDN probe result: $detail")
        }

        val newVideo = video?.let { reorder(it, order) }
        val newAudio = audio?.let { reorder(it, order) }
        if (newVideo == video && newAudio == audio) return video to audio
        return newVideo to newAudio
    }

    /** Reorders one stream's [url] + [backupUrls] by the host ranking, returning the same instance when unchanged. */
    private fun reorder(stream: MediaStream, hostOrder: List<String>): MediaStream {
        val candidates = listOf(stream.url) + stream.backupUrls
        val rank: (String) -> Int = { url ->
            val h = MediaHosts.hostOf(url)
            if (h == null) Int.MAX_VALUE
            else {
                val i = hostOrder.indexOf(h)
                if (i < 0) Int.MAX_VALUE else i
            }
        }
        val sorted = candidates.sortedBy(rank)
        if (sorted == candidates) return stream
        return stream.copy(url = sorted.first(), backupUrls = sorted.drop(1))
    }

    /** All candidate URLs of both streams, primary + backups, deduplicated by host later. */
    private fun candidateUrls(vararg streams: MediaStream?): List<String> = buildList {
        for (s in streams) {
            if (s == null) continue
            add(s.url)
            addAll(s.backupUrls)
        }
    }

    /**
     * Measures time-to-first-byte of one tiny Range GET against [url]. Returns µs latency, or -1
     * when the host rejected the probe (4xx/5xx, TLS failure, timeout, connection refused).
     */
    private fun probe(host: String, url: String, deadlineNanos: Long): Long {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            .coerceIn(250L, PROBE_TIMEOUT_MS)
        val start = System.nanoTime()
        return try {
            val headers = mutableListOf("Range" to "bytes=0-${PROBE_BYTES - 1}")
            // Same Referer the decoder will send; CDNs often 403 otherwise.
            MediaHosts.refererFor(url)?.let { headers.add("Referer" to it) }
            val response = DreamHttpClient.executeLimited(
                url,
                PROBE_BYTES,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf(*headers.toTypedArray()),
                    connectTimeoutMs = remainingMs,
                    readTimeoutMs = remainingMs,
                    callTimeoutMs = remainingMs,
                ),
            )
            if (!response.isSuccessful) {
                logger.debug("CDN probe for $host returned HTTP ${response.code}; marking slow.")
                return -1
            }
            TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start)
        } catch (e: IOException) {
            logger.debug("CDN probe for $host failed: ${e.message ?: e::class.java.simpleName}")
            -1
        } catch (e: Exception) {
            logger.debug("CDN probe for $host aborted: ${e.message ?: e::class.java.simpleName}")
            -1
        }
    }
}