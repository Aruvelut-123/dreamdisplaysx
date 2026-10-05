package com.dreamdisplayx.media.player.subtitle

import com.dreamdisplayx.media.runtime.security.MediaHostGuard
import com.dreamdisplayx.util.net.DreamHttpClient
import java.net.URI
import java.util.concurrent.CancellationException

/** Bounded WebVTT/SRT and unencrypted HLS WebVTT VOD loading; never runs on the render thread. */
internal class SubtitleLoader(
    private val fetch: (String) -> Document = ::fetchDocument,
    private val resolve: (String) -> String = { target -> MediaHostGuard.resolveSafeUrl(target) },
) {
    data class Document(val url: String, val text: String)
    private data class Segment(val url: String, val start: Long)

    fun load(url: String, cancelled: () -> Boolean = { false }): List<SubtitleCue> {
        val deadline = System.nanoTime() + 60_000_000_000L
        var bytes = 0
        fun read(target: String): Document {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
            check(System.nanoTime() < deadline) { "Subtitle load timed out" }
            val document = fetch(target)
            bytes += document.text.toByteArray(Charsets.UTF_8).size
            require(bytes <= MAX_TOTAL_BYTES) { "Subtitle track is too large" }
            return document
        }
        val root = read(url)
        if (!root.text.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U")) return parse(root.text)
        val lines = root.text.lineSequence().map { it.trim() }.toList()
        require(lines.any { it == "#EXT-X-ENDLIST" }) { "Live HLS subtitles are not supported yet" }
        require(lines.none { it.startsWith("#EXT-X-KEY:") && !it.contains("METHOD=NONE") }) { "Encrypted subtitles are not supported" }
        require(lines.none { it.startsWith("#EXT-X-MAP:") || it.startsWith("#EXT-X-BYTERANGE:") }) { "Only standalone WebVTT HLS segments are supported" }
        val segments = ArrayList<Segment>()
        var cursor = 0L
        var duration: Long? = null
        for (line in lines) {
            if (line.startsWith("#EXTINF:")) {
                val seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                require(seconds != null && seconds.isFinite() && seconds > 0 && seconds < 86400) { "Invalid subtitle segment duration" }
                duration = (seconds * 1_000_000_000L).toLong()
            } else if (line.isNotEmpty() && !line.startsWith('#')) {
                val length = requireNotNull(duration) { "Invalid subtitle media playlist" }
                require(segments.size < MAX_SEGMENTS) { "Too many subtitle segments" }
                val resolved = URI(root.url).resolve(line).toString()
                // Fetch-time validation checks every segment and redirect, including absolute URIs.
                segments += Segment(resolve(resolved), cursor)
                cursor = Math.addExact(cursor, length)
                duration = null
            }
        }
        var transportOrigin: Long? = null
        val cues = ArrayList<SubtitleCue>()
        for (segment in segments) {
            val text = read(segment.url).text
            val mapping = timestampMap(text)
            var offset = 0L
            if (mapping != null) {
                val (local, transport) = mapping
                val origin = transportOrigin ?: (transport - local - segment.start).also { transportOrigin = it }
                // The 33-bit MPEGTS clock wraps about every 26.5 hours.
                val expected = origin + segment.start + local
                val unwrapped = transport + Math.round((expected - transport).toDouble() / MPEGTS_WRAP_NANOS) * MPEGTS_WRAP_NANOS
                offset = unwrapped - origin - local
            }
            // Without a timestamp map, WebVTT cue times already use the media timeline (RFC 8216).
            for (cue in parse(text)) {
                val start = Math.addExact(cue.startNanos, offset)
                val end = Math.addExact(cue.endNanos, offset)
                if (end > 0) cues += cue.copy(startNanos = maxOf(0, start), endNanos = end)
            }
        }
        return cues.distinct().sortedBy { it.startNanos }
    }

    private fun parse(text: String): List<SubtitleCue> = WebVttParser.parse(text)

    internal fun timestampMap(text: String): Pair<Long, Long>? {
        val line = text.lineSequence().map(String::trim)
            .firstOrNull { it.startsWith("X-TIMESTAMP-MAP=") } ?: return null
        val values = line.substringAfter('=').split(',').associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        val local = requireNotNull(values["LOCAL"]?.let(WebVttParser::timestamp)) { "Invalid local subtitle timestamp" }
        val ticks = requireNotNull(values["MPEGTS"]?.toLongOrNull()) { "Invalid subtitle MPEGTS timestamp" }
        require(ticks in 0 until (1L shl 33)) { "Invalid subtitle MPEGTS clock" }
        return local to (ticks * 1_000_000L / 90L)
    }

    companion object {
        private const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
        private const val MAX_SEGMENTS = 512
        private const val MPEGTS_WRAP_NANOS = (1L shl 33) * 1_000_000L / 90L

        private fun fetchDocument(url: String): Document {
            require(URI(url).scheme?.lowercase() in setOf("http", "https")) { "Subtitle URLs must use HTTP(S)" }
            val safe = MediaHostGuard.resolveSafeUrl(url)
            val response = DreamHttpClient.executeLimited(safe, MAX_DOCUMENT_BYTES + 1,
                DreamHttpClient.RequestOptions(readTimeoutMs = 10_000, callTimeoutMs = 12_000, followRedirects = false))
            check(response.isSuccessful) { "Subtitle HTTP ${response.code}" }
            require(response.body.size <= MAX_DOCUMENT_BYTES) { "Subtitle document is too large" }
            return Document(response.finalUrl, response.bodyString())
        }
    }
}
