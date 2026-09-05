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

    /** Chunk size for on-the-fly bandwidth probes (short budget during playback). */
    private const val PROBE_BANDWIDTH_BYTES = 256 * 1024

    /**
     * Chunk size for the startup pre-probe — same as PiliPlus's CDN speed test
     * (`maxSize = 8 * 1024 * 1024`).  A large download lets TCP leave slow-start and reflects the
     * real achievable throughput; a 256 KB chunk mostly measures connection setup.
     */
    private const val STARTUP_PROBE_BANDWIDTH_BYTES = 8 * 1024 * 1024

    /** Per-host probe budget. */
    private const val PROBE_TIMEOUT_MS = 3_000L

    /** Per-host budget for the startup pre-probe, matching PiliPlus's 15 s receive timeout. */
    private const val STARTUP_PROBE_TIMEOUT_MS = 15_000L

    /** Total budget for probing all candidates of one stream. */
    private const val TOTAL_BUDGET_MS = 6_000L

    /** Budget for the startup pre-probe; runs on a background thread so it can afford to be slow. */
    private const val WARMUP_BUDGET_MS = 60_000L

    /** Cache of hostname → measured bandwidth (MB/s × 1000, higher = faster); -1 = failed. */
    private val hostScoreCache = ConcurrentHashMap<String, Long>()

    /** Guards [startupProbe] so it only runs once per process. */
    private val startupProbeStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Regex for Bilibili upos-mirror URLs whose host can be replaced. */
    private val MIRROR_REGEX = Regex(
        """^https?://(?:upos-\w+-(?!302)\w+|(?:upos|proxy)-tf-[^/]+)\.(?:bilivideo|akamaized)\.(?:com|net)/upgcxcode""",
    )

    /**
     * Returns [MediaStream]s whose CDN candidates are reordered / rewritten to the fastest edge.
     *
     * @param video  the video stream, or null for audio-only.
     * @param audio  the audio stream, or null for video-only.
     * @param preferredMirrorHost  the raw value of the `bilibili-cdn-mirror` config option:
     *   - `null`, blank or `"auto"` → auto-probe the mirror hosts by bandwidth;
     *   - `"BASE_URL"` → keep the API's original stream URLs untouched;
     *   - `"BACKUP_URL"` → use the first backup URL from the API response;
     *   - anything else → treated as a mirror hostname and substituted into every mirror URL
     *     (e.g. `"upos-sz-mirrorcos.bilivideo.com"`).  Hosts that fail basic validation are
     *     rejected with a warning and playback proceeds unmodified.
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

        // Resolve the config value: `null`/blank/`auto` fall through to the auto probe below.
        val explicit = preferredMirrorHost?.trim()
        if (!explicit.isNullOrEmpty() && !explicit.equals("auto", ignoreCase = true)) {
            when {
                explicit.equals("BASE_URL", ignoreCase = true) -> {
                    logger.info("CDN mirror: BASE_URL selected, keeping the API's original URLs.")
                    return video to audio
                }
                explicit.equals("BACKUP_URL", ignoreCase = true) -> {
                    logger.info("CDN mirror: BACKUP_URL selected, using first backup URL when available.")
                    return promoteFirstBackup(video, audio)
                }
                else -> {
                    // Friendly mirror names ("cos", "hw", "ali", ...) resolve to their host via
                    // the BilibiliCdnMirror list (based on PiliPlus's CDNService); a full hostname
                    // ("upos-sz-mirrorcos.bilivideo.com") is used as-is.
                    val mirror = BilibiliCdnMirror.entries.firstOrNull {
                        it.host != null && (it.name.equals(explicit, ignoreCase = true) ||
                                it.host.equals(explicit, ignoreCase = true))
                    }
                    val hostCandidate = mirror?.host ?: explicit
                    // Validate the candidate host before rewriting: must parse as a real hostname.
                    val host = runCatching { java.net.URI("https://$hostCandidate/").host }
                        .getOrNull()?.removeSurrounding("[", "]")
                    if (host == null || host.isBlank() || !host.contains('.') || host.contains(' ') ||
                        host.any { !it.isLetterOrDigit() && it != '.' && it != '-' }
                    ) {
                        logger.warn("CDN mirror '{}' is not a valid hostname; ignoring.", explicit)
                        return video to audio
                    }
                    return rewriteToMirror(video, audio, candidateUrls, host, authReferer)
                }
            }
        }

        // Auto: separate Bilibili mirror URLs (host-replaceable) from regular URLs.
        val mirrorUrls = candidateUrls.filter { MIRROR_REGEX.containsMatchIn(it) }
        val regularUrls = candidateUrls.filter { !MIRROR_REGEX.containsMatchIn(it) }

        // Only Bilibili mirrors get bandwidth probing; everything else goes through the old TTFB path.
        if (mirrorUrls.isEmpty()) {
            return reorderByLatency(video, audio, regularUrls, authReferer)
        }

        // Candidate hosts: the full known mirror list (PiliPlus-based BilibiliCdnMirror) first,
        // so we never depend on whichever hosts the Bilibili API happened to return.  URL templates
        // come from the stream itself — the host is replaced per candidate.
        val templateByHost = LinkedHashMap<String, String>()
        for (url in mirrorUrls) {
            val h = MediaHosts.hostOf(url) ?: continue
            templateByHost.getOrPut(h) { url }
        }
        val templateUrl = templateByHost.values.firstOrNull()
        if (templateUrl == null) return video to audio

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_BUDGET_MS)
        val candidateHosts = (BilibiliCdnMirror.entries.mapNotNull { it.host } + templateByHost.keys).distinct()
        val scores = LinkedHashMap<String, Long>()
        var probed = false
        for (host in candidateHosts) {
            if (isPenalized(host)) continue
            val cached = hostScoreCache[host]
            if (cached != null) {
                if (cached >= 0) scores[host] = cached
                continue
            }
            if (System.nanoTime() > deadlineNanos) break
            probed = true
            val probeUrl = templateByHost[host] ?: replaceHost(templateUrl, host)
            val score = probeBandwidth(host, probeUrl, deadlineNanos, authReferer)
            hostScoreCache[host] = score
            if (score >= 0) scores[host] = score
        }

        if (scores.isEmpty()) {
            logger.info("CDN ranking: all hosts unreachable, using original order.")
            return video to audio
        }

        val bestHost = scores.maxByOrNull { it.value }?.key ?: return video to audio
        val detail = scores.entries.sortedByDescending { it.value }
            .joinToString(", ") { (h, s) -> "$h=${s / 1000.0}MB/s" }
        logger.info("CDN ranking (${if (probed) "probing" else "cached"}): $detail → selected $bestHost")
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

    /**
     * `BACKUP_URL` selector: promotes each stream's first backup URL to the primary position
     * (matching PiliPlus's "backup URL" CDN option). Streams without backups are unchanged.
     */
    private fun promoteFirstBackup(
        video: MediaStream?,
        audio: MediaStream?,
    ): Pair<MediaStream?, MediaStream?> {
        var changed = false
        fun promote(s: MediaStream): MediaStream {
            val first = s.backupUrls.firstOrNull() ?: return s
            if (first == s.url) return s
            changed = true
            return s.copy(
                url = first,
                backupUrls = listOf(s.url) + s.backupUrls.drop(1),
            )
        }
        val newVideo = video?.let { promote(it) }
        val newAudio = audio?.let { promote(it) }
        return if (changed) newVideo to newAudio else video to audio
    }

    // ── Bandwidth probe ─────────────────────────────────────────────────────

    /**
     * Measures throughput to [host] by requesting a Range from [url].
     * @param chunkBytes  number of bytes to request (Range `bytes=0-{chunkBytes-1}`).
     *                    Startup probe uses 8 MB (matching PiliPlus); on-the-fly uses 256 KB.
     * @param timeoutMs  per-host timeout; startup uses 15 s (matching PiliPlus), on-the-fly uses 3 s.
     * Returns throughput in MB/s × 1000 (higher = better), or -1 on failure.
     */
    private fun probeBandwidth(
        host: String,
        url: String,
        deadlineNanos: Long,
        authReferer: String?,
        chunkBytes: Int = PROBE_BANDWIDTH_BYTES,
        timeoutMs: Long = PROBE_TIMEOUT_MS,
    ): Long {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            .coerceIn(500L, timeoutMs)
        val start = System.nanoTime()
        return try {
            val headers = mutableListOf("Range" to "bytes=0-${chunkBytes - 1}")
            authReferer?.let { headers.add("Referer" to it) }
            MediaHosts.refererFor(url)?.let { ref ->
                if (ref !in headers.map { it.second }) headers.add("Referer" to ref)
            }
            val response = DreamHttpClient.executeLimited(
                url,
                chunkBytes,
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
            // bytes / elapsedMs = B/ms = MB/s × 1000 (since B/ms * 1000 = B/s, B/s / 1e6 = MB/s).
            // So the raw quotient is already MB/s × 1000 — no extra factor needed.
            (bytes / elapsedMs).toLong().coerceAtLeast(1)
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

    // ── Startup pre-probe & public queries ─────────────────────────────────

    /**
     * Startup pre-probe: given sample Bilibili stream URLs fetched from a known public video
     * (e.g. PiliPlus's sample `BV1fK4y1t7hj`), this measures bandwidth to every known
     * [BilibiliCdnMirror] host and caches the scores.  Called at game startup on a background
     * thread so the first Bilibili playback already has a complete mirror ranking — subsequent
     * auto playbacks consume the cached scores and never probe again.
     *
     * Based on the PiliPlus CDNService approach — the same content is available from any mirror,
     * so we pick the fastest edge for the user's location.
     */
    fun startupProbe(templateUrls: List<String>, authReferer: String? = null) {
        if (!startupProbeStarted.compareAndSet(false, true)) return
        val templateUrl = templateUrls.firstOrNull { MIRROR_REGEX.containsMatchIn(it) }
            ?: templateUrls.firstOrNull { it.contains("/upgcxcode/") }
            ?: return
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WARMUP_BUDGET_MS)
        val ranked = LinkedHashMap<String, Long>()
        val visible = BilibiliCdnMirror.entries.filter { it.host != null }
        val total = visible.size
        for (mirror in visible) {
            val host = mirror.host!!
            if (isPenalized(host)) continue
            val cached = hostScoreCache[host]
            if (cached != null) {
                if (cached >= 0) ranked[host] = cached
                continue
            }
            if (System.nanoTime() > deadline) {
                logger.info("CDN startup probe: budget exhausted after {}/{} mirrors.", ranked.size, total)
                break
            }
            val probeUrl = replaceHost(templateUrl, host)
            if (probeUrl == templateUrl) continue // replaceHost failed
            val score = probeBandwidth(
                host, probeUrl, deadline, authReferer,
                chunkBytes = STARTUP_PROBE_BANDWIDTH_BYTES,  // 8 MB, same as PiliPlus
                timeoutMs = STARTUP_PROBE_TIMEOUT_MS,        // 15 s, same as PiliPlus
            )
            hostScoreCache[host] = score
            if (score >= 0) ranked[host] = score
        }
        if (ranked.isEmpty()) {
            logger.info("CDN startup probe: no mirrors reachable.")
            return
        }
        val ordered = ranked.entries.sortedByDescending { it.value }
        val detail = ordered.joinToString(", ") { (h, s) -> "$h=${s / 1000.0}MB/s" }
        logger.info("CDN startup probe (based on PiliPlus mirror list, {} mirrors): {}", ranked.size, detail)
        val best = ordered.first()
        logger.info(
            "CDN startup probe: fastest mirror = {} ({} MB/s)",
            best.key,
            "%.1f".format(best.value / 1000.0),
        )
    }

    /** Returns the hostname with the highest cached bandwidth score, or null if none is cached. */
    fun bestMirrorHost(): String? = hostScoreCache
        .filter { it.value >= 0 }
        .maxByOrNull { it.value }?.key

    /**
     * Returns all known mirrors with their cached bandwidth scores, sorted fastest-first.
     * Each pair is `(hostname, MB/s × 1000)`.
     */
    fun mirrorRanking(): List<Pair<String, Long>> = hostScoreCache
        .filter { it.value >= 0 }
        .entries.sortedByDescending { it.value }
        .map { it.key to it.value }

    /** Clears all cached scores so the next probe runs fresh. */
    fun clearScores() {
        hostScoreCache.clear()
        startupProbeStarted.set(false)
    }

    // ── Early-failure penalty ───────────────────────────────────────────────

    /**
     * Hosts that dropped a live stream mid-play (early EOS / repeated stalls) with the timestamp of
     * the failure. Short-burst bandwidth probes can badly overestimate a throttling edge, so a
     * host that starves during actual playback is excluded from ranking for a cooldown window.
     */
    private val penalizedHosts = ConcurrentHashMap<String, Long>()

    /** How long a penalized host stays out of the ranking. */
    private const val HOST_PENALTY_MS = 10 * 60_000L

    /**
     * Records that [url]'s host failed during playback, so the next resolution prefers a
     * different mirror. Score cache entry is dropped so a fresh probe may re-rank it later.
     */
    fun penalizeHost(url: String?) {
        val host = url?.let { MediaHosts.hostOf(it) } ?: return
        penalizedHosts[host] = System.currentTimeMillis()
        hostScoreCache.remove(host)
        logger.warn("CDN host '{}' penalized for {} s after a mid-play failure.", host, HOST_PENALTY_MS / 1000)
    }

    /** True while [host] is inside its post-failure cooldown. */
    private fun isPenalized(host: String): Boolean {
        val at = penalizedHosts[host] ?: return false
        if (System.currentTimeMillis() - at >= HOST_PENALTY_MS) {
            penalizedHosts.remove(host)
            return false
        }
        return true
    }

    /** True if the host of [url] is currently serving (not penalized). */
    fun isHostUsable(url: String?): Boolean {
        val host = url?.let { MediaHosts.hostOf(it) } ?: return true
        return !isPenalized(host)
    }
}