package com.dreamdisplayx.media.player.cdn

import com.dreamdisplayx.api.media.stream.model.MediaStream
import com.dreamdisplayx.api.security.policy.MediaHosts
import com.dreamdisplayx.util.net.DreamHttpClient
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Probes the CDN candidates of a stream before playback starts and reorders / rewrites them so the
 * player opens the fastest edge.
 *
 * **Bilibili mirror host replacement** (based on [PiliPlus](https://github.com/piliplus/piliplus)):
 * when a [BilibiliCdnMirror] is configured (either by the user explicit host or by auto-probe), the
 * `upos-*` / `bilivideo.com` host in the stream URL is replaced with the chosen mirror's host.
 * This is the same approach PiliPlus uses — the Bilibili API returns the same content from any
 * mirror, so picking the fastest edge for your location gives a reliable connection.
 *
 * **Bandwidth measurement**: instead of the old 32-byte TTFB probe (which picks the edge with the
 * best first-byte latency but can't tell how fast it actually downloads), each candidate host is
 * tested with a larger Range request (~256 KB) and ranked by throughput (MB/s).  The result is
 * cached per hostname so subsequent videos on the same session skip the probe.
 *
 * Non-Bilibili streams fall back to the simpler TTFB-based latency reorder.
 */
object CdnSpeedProbe {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/CdnSpeedProbe")

    /** Chunk size for bandwidth probes — 256 KB is enough to measure throughput without being heavy. */
    private const val PROBE_BANDWIDTH_BYTES = 256 * 1024

    /** Per-host probe budget. */
    private const val PROBE_TIMEOUT_MS = 3_000L

    /** Total budget for probing all candidates of one stream. */
    private const val TOTAL_BUDGET_MS = 6_000L

    /** Cache of hostname → measured bandwidth (MB/s × 1000, higher = faster); -1 = failed. */
    private val hostScoreCache = ConcurrentHashMap<String, Long>()

    /** Regex for Bilibili upos-mirror URLs whose host can be replaced. */
    private val MIRROR_REGEX = Regex(
        """^https?://(?:upos-\w+-(?!302)\w+|(?:upos|proxy)-tf-[^/]+)\.(?:bilivideo|akamaized)\.(?:com|net)/upgcxcode""",
    )

    /**
     * Returns [MediaStream]s whose CDN candidates are reordered / rewritten to the fastest edge.
     *
     * @param video  the video stream, or null for audio-only.
     * @param audio  the audio stream, or null for video-only.
     * @param preferredMirror  when non-null and non-empty, the explicit hostname of a Bilibili CDN
     *                         mirror (e.g. `"upos-sz-mirrorcos.bilivideo.com"`) — all Bilibili
     *                         mirror URLs in the candidate set are rewritten to this host.  Auto
     *                         probing is skipped entirely, so the session opens faster.  Pass
     *                         `"BASE_URL"` to keep the original API host, or `"BACKUP_URL"` to use
     *                         the first backup URL from the API response.
     * @param preferredMirrorHost  the explicit host to use when [preferredMirror] is set.
     * @param authReferer  the Referer header to send with probe requests (mirrors the decoder's
     *                     request), or null to omit.
     */
    fun reorderForPlayback(
        video: MediaStream?,
        audio: MediaStream?,
        preferredMirrorHost: String? = null,
        authReferer: String? = null,
    ): Pair<MediaStream?, MediaStream?> {
        if (video == null && audio == null) return video to audio

        val candidateUrls = buildList {
            video?.let { add(it.url); addAll(it.backupUrls) }
            audio?.let { add(it.url); addAll(it.backupUrls) }
        }
        if (candidateUrls.isEmpty()) return video to audio

        // Explicit mirror selected → rewrite every Bilibili mirror URL.
        if (preferredMirrorHost != null) {
            return rewriteToMirror(video, audio, candidateUrls, preferredMirrorHost, authReferer)
        }

        // Auto: separate Bilibili mirror URLs (host-replaceable) from regular URLs.
        val mirrorUrls = candidateUrls.filter { MIRROR_REGEX.containsMatchIn(it) }
        val regularUrls = candidateUrls.filter { !MIRROR_REGEX.containsMatchIn(it) }

        // Only Bilibili mirrors get bandwidth probing; everything else goes through the old TTFB path.
        if (mirrorUrls.isEmpty()) {
            return reorderByLatency(video, audio, regularUrls, authReferer)
        }

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_BUDGET_MS)

        // Group mirror URLs by hostname.
        val hostToUrl = LinkedHashMap<String, String>()
        for (url in mirrorUrls) {
            val host = MediaHosts.hostOf(url) ?: continue
            if (host !in hostToUrl) hostToUrl[host] = url
        }
        if (hostToUrl.size <= 1) {
            // Only one mirror host → just rewrite to it (skips probing, saves time).
            val bestHost = hostToUrl.keys.first()
            return rewriteToMirror(video, audio, candidateUrls, bestHost, authReferer)
        }

        // Probe bandwidth for each distinct host (cached or fresh).
        val scores = LinkedHashMap<String, Long>()
        for ((host, url) in hostToUrl) {
            if (System.nanoTime() > deadlineNanos) break
            val cached = hostScoreCache[host]
            if (cached != null) {
                scores[host] = cached
                continue
            }
            val score = probeBandwidth(host, url, deadlineNanos, authReferer)
            hostScoreCache[host] = score
            if (score >= 0) scores[host] = score
        }

        if (scores.isEmpty()) {
            // All probes failed — keep the original order.
            logger.info("CDN bandwidth probe: all hosts unreachable, using original order.")
            return video to audio
        }

        // Sort by throughput descending (higher = faster).
        val bestHost = scores.maxByOrNull { it.value }?.key ?: return video to audio
        val detail = scores.entries.joinToString(", ") { (h, s) -> "$h=${s / 1000.0}MB/s" }
        logger.info("CDN bandwidth probe: $detail  → selected $bestHost")

        return rewriteToMirror(video, audio, candidateUrls, bestHost, authReferer)
    }

    // ── Explicit mirror rewrite ─────────────────────────────────────────────

    /**
     * Rewrites every Bilibili mirror URL in the candidate set to use [mirrorHost].
     * Non-Bilibili URLs are left untouched.
     */
    private fun rewriteToMirror(
        video: MediaStream?,
        audio: MediaStream?,
        candidateUrls: List<String>,
        mirrorHost: String,
        authReferer: String?,
    ): Pair<MediaStream?, MediaStream?> {
        // Build a mapping: original URL → rewritten URL.
        val rewriteMap = candidateUrls.associateWith { url ->
            if (MIRROR_REGEX.containsMatchIn(url)) {
                replaceHost(url, mirrorHost)
            } else url
        }
        if (rewriteMap.isEmpty()) return video to audio

        val changed = rewriteMap.any { (orig, new) -> orig != new }

        fun rewriteStream(s: MediaStream): MediaStream {
            val newUrl = rewriteMap[s.url] ?: s.url
            val newBackups = s.backupUrls.map { rewriteMap[it] ?: it }
            if (newUrl == s.url && newBackups == s.backupUrls) return s
            return s.copy(url = newUrl, backupUrls = newBackups)
        }

        val newVideo = video?.let { rewriteStream(it) }
        val newAudio = audio?.let { rewriteStream(it) }
        if (!changed) return video to audio

        logger.info("CDN rewritten to mirror '{}' ({} URLs).", mirrorHost, rewriteMap.size)
        if (newVideo == video && newAudio == audio) return video to audio
        return newVideo to newAudio
    }

    /** Replaces the host of a Bilibili `upos-*` URL with [newHost]. */
    private fun replaceHost(url: String, newHost: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        return runCatching { URI(uri.scheme, newHost, uri.path, uri.query, uri.fragment).toString() }
            .getOrNull() ?: url
    }

    // ── Bandwidth probe ─────────────────────────────────────────────────────

    /**
     * Measures throughput to [host] by requesting a 256 KB Range from [url].
     * Returns throughput in MB/s × 1000 (higher = better), or -1 on failure.
     */
    private fun probeBandwidth(
        host: String,
        url: String,
        deadlineNanos: Long,
        authReferer: String?,
    ): Long {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            .coerceIn(500L, PROBE_TIMEOUT_MS)
        val start = System.nanoTime()
        return try {
            val headers = mutableListOf("Range" to "bytes=0-${PROBE_BANDWIDTH_BYTES - 1}")
            authReferer?.let { headers.add("Referer" to it) }
            MediaHosts.refererFor(url)?.let { ref ->
                if (ref !in headers.map { it.second }) headers.add("Referer" to ref)
            }
            val response = DreamHttpClient.executeLimited(
                url,
                PROBE_BANDWIDTH_BYTES,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf(*headers.toTypedArray()),
                    connectTimeoutMs = remainingMs,
                    readTimeoutMs = remainingMs,
                    callTimeoutMs = remainingMs,
                ),
            )
            if (!response.isSuccessful) {
                logger.warn("CDN bandwidth probe for $host returned HTTP ${response.code}; skipping.")
                return -1
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            if (elapsedMs <= 0.0) return Long.MAX_VALUE // instant = loopback
            val bytes = response.body.size.coerceAtLeast(1)
            (bytes / elapsedMs / 1000.0).toLong().coerceAtLeast(1)
        } catch (e: IOException) {
            logger.warn("CDN bandwidth probe for $host failed: ${e.message ?: e::class.java.simpleName}")
            -1
        } catch (e: Exception) {
            logger.warn("CDN bandwidth probe for $host aborted: ${e.message ?: e::class.java.simpleName}")
            -1
        }
    }

    // ── Fallback TTFB reorder (non-Bilibili) ────────────────────────────────

    /** Cache of hostname → latency (µs) for the current run. */
    private val hostLatencyMicros = ConcurrentHashMap<String, Long>()

    /** Reorders streams by latency (TTFB) for non-Bilibili candidates. */
    private fun reorderByLatency(
        video: MediaStream?,
        audio: MediaStream?,
        urls: List<String>,
        authReferer: String?,
    ): Pair<MediaStream?, MediaStream?> {
        if (urls.size <= 1) return video to audio

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_BUDGET_MS)
        val hostToUrls = LinkedHashMap<String, MutableList<String>>()
        for (url in urls) {
            val host = MediaHosts.hostOf(url) ?: continue
            hostToUrls.getOrPut(host) { mutableListOf() }.add(url)
        }
        if (hostToUrls.size <= 1) return video to audio

        val scores = LinkedHashMap<String, Long>()
        for ((host, hostUrls) in hostToUrls) {
            if (System.nanoTime() > deadlineNanos) break
            val cached = hostLatencyMicros[host]
            if (cached != null) {
                scores[host] = cached
                continue
            }
            val latency = probeLatency(host, hostUrls.first(), deadlineNanos, authReferer)
            if (latency >= 0) {
                hostLatencyMicros[host] = latency
                scores[host] = latency
            }
        }

        val order = hostToUrls.keys.sortedBy { scores[it] ?: Long.MAX_VALUE }
        if (order.size < 2 || scores.isEmpty()) return video to audio

        val detail = order.joinToString(", ") { "$it=${scores[it]?.let { s -> "${s / 1000.0}ms" } ?: "?"}" }
        logger.info("CDN latency probe: $detail")

        fun reorderStream(s: MediaStream): MediaStream {
            val candidates = listOf(s.url) + s.backupUrls
            val rank: (String) -> Int = { url ->
                val h = MediaHosts.hostOf(url)
                if (h == null) Int.MAX_VALUE else order.indexOf(h).let { if (it < 0) Int.MAX_VALUE else it }
            }
            val sorted = candidates.sortedBy(rank)
            if (sorted == candidates) return s
            return s.copy(url = sorted.first(), backupUrls = sorted.drop(1))
        }

        val newVideo = video?.let { reorderStream(it) }
        val newAudio = audio?.let { reorderStream(it) }
        if (newVideo == video && newAudio == audio) return video to audio
        return newVideo to newAudio
    }

    /** Measures TTFB latency in µs via a small Range request. */
    private fun probeLatency(
        host: String,
        url: String,
        deadlineNanos: Long,
        authReferer: String?,
    ): Long {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            .coerceIn(250L, PROBE_TIMEOUT_MS)
        val start = System.nanoTime()
        return try {
            val headers = mutableListOf("Range" to "bytes=0-31")
            authReferer?.let { headers.add("Referer" to it) }
            MediaHosts.refererFor(url)?.let { ref ->
                if (ref !in headers.map { it.second }) headers.add("Referer" to ref)
            }
            val response = DreamHttpClient.executeLimited(
                url, 32,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf(*headers.toTypedArray()),
                    connectTimeoutMs = remainingMs,
                    readTimeoutMs = remainingMs,
                    callTimeoutMs = remainingMs,
                ),
            )
            if (!response.isSuccessful) {
                logger.warn("CDN latency probe for $host returned HTTP ${response.code}; marking slow.")
                return -1
            }
            TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start)
        } catch (e: IOException) {
            logger.warn("CDN latency probe for $host failed: ${e.message ?: e::class.java.simpleName}")
            -1
        } catch (e: Exception) {
            logger.warn("CDN latency probe for $host aborted: ${e.message ?: e::class.java.simpleName}")
            -1
        }
    }
}