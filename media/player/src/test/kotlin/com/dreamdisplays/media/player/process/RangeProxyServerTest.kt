package com.dreamdisplays.media.player.process

import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RangeProxyServerTest {
    private val file = ByteArray(1_000_003) { (it * 31 + it / 7).toByte() }
    private val requested = CopyOnWriteArrayList<LongRange>()
    private var statusOverride: Int? = null

    private val server = RangeProxyServer(
        upstream = { _, from, to ->
            requested += from..to
            val status = statusOverride
            if (status != null) {
                UpstreamChunk(status, null, null, ByteArray(0))
            } else {
                val end = minOf(to, file.size - 1L)
                UpstreamChunk(206, file.size.toLong(), "audio/mp4", file.copyOfRange(from.toInt(), end.toInt() + 1))
            }
        },
        firstChunkBytes = 4_096,
        chunkBytes = 100_000,
    )

    @AfterTest
    fun close() = server.close()

    private fun get(url: String, range: String?): Triple<String, Map<String, String>, ByteArray> {
        val uri = URI(url)
        Socket(uri.host, uri.port).use { s ->
            val request = buildString {
                append("GET ").append(uri.rawPath).append(" HTTP/1.1\r\nHost: ").append(uri.host).append("\r\n")
                if (range != null) append("Range: ").append(range).append("\r\n")
                append("\r\n")
            }
            s.getOutputStream().write(request.toByteArray())
            val raw = s.getInputStream().readAllBytes()
            val split = (0 until raw.size - 3).first {
                raw[it] == '\r'.code.toByte() && raw[it + 1] == '\n'.code.toByte() &&
                    raw[it + 2] == '\r'.code.toByte() && raw[it + 3] == '\n'.code.toByte()
            }
            val lines = String(raw, 0, split, Charsets.US_ASCII).split("\r\n")
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            val body = ByteArrayOutputStream().apply { write(raw, split + 4, raw.size - split - 4) }.toByteArray()
            return Triple(lines.first(), headers, body)
        }
    }

    @Test
    fun `an open-ended range is served in full from bounded upstream chunks`() {
        val url = assertNotNull(server.register("https://example.invalid/media", file.size.toLong()))
        val (status, headers, body) = get(url, "bytes=0-")
        assertEquals("HTTP/1.1 206 Partial Content", status)
        assertEquals("bytes 0-${file.size - 1}/${file.size}", headers["content-range"])
        assertEquals(file.size.toString(), headers["content-length"])
        assertContentEquals(file, body)
        assertTrue(requested.all { it.last - it.first + 1 <= 100_000 }, "Every upstream request must be bounded.")
        assertEquals(4_096L, requested.first().last + 1, "The first chunk stays small for a fast first byte.")
    }

    @Test
    fun `a seek is a fresh request that starts at its own offset`() {
        val url = assertNotNull(server.register("https://example.invalid/media", null))
        val (status, headers, body) = get(url, "bytes=500000-")
        assertEquals("HTTP/1.1 206 Partial Content", status)
        assertEquals("bytes 500000-${file.size - 1}/${file.size}", headers["content-range"])
        assertContentEquals(file.copyOfRange(500_000, file.size), body)
        assertEquals(500_000L, requested.first().first)
    }

    @Test
    fun `a closed range is honored exactly`() {
        val url = assertNotNull(server.register("https://example.invalid/media", file.size.toLong()))
        val (_, headers, body) = get(url, "bytes=10-20009")
        assertEquals("bytes 10-20009/${file.size}", headers["content-range"])
        assertContentEquals(file.copyOfRange(10, 20_010), body)
    }

    @Test
    fun `upstream errors are mirrored before any byte is sent`() {
        statusOverride = 403
        val url = assertNotNull(server.register("https://example.invalid/media", file.size.toLong()))
        val (status, _, body) = get(url, "bytes=0-")
        assertEquals("HTTP/1.1 403 Forbidden", status)
        assertEquals(0, body.size)
    }

    @Test
    fun `unknown tokens and ranges past the end are refused`() {
        val url = assertNotNull(server.register("https://example.invalid/media", file.size.toLong()))
        assertEquals("HTTP/1.1 404 Not Found", get(url.substringBeforeLast('/') + "/nope", null).first)
        assertEquals("HTTP/1.1 416 Range Not Satisfiable", get(url, "bytes=${file.size}-").first)
    }

    @Test
    fun `range headers parse the forms FFmpeg sends`() {
        assertEquals(0L to null, RangeProxyServer.parseRange(null))
        assertEquals(5L to null, RangeProxyServer.parseRange("bytes=5-"))
        assertEquals(5L to 9L, RangeProxyServer.parseRange("bytes=5-9"))
        assertNull(RangeProxyServer.parseRange("bytes=-500"))
        assertNull(RangeProxyServer.parseRange("bytes=0-1,5-9"))
        assertNull(RangeProxyServer.parseRange("bytes=9-5"))
    }

    @Test
    fun `only googlevideo VOD playback URLs are proxied`() {
        val vod = "https://rr2---sn-abc.googlevideo.com/videoplayback?expire=1&itag=140&clen=12345&dur=10"
        assertTrue(GoogleVideoRangeProxy.eligible(vod))
        assertEquals(12_345L, GoogleVideoRangeProxy.contentLength(vod))
        assertTrue(!GoogleVideoRangeProxy.eligible("https://rr2---sn-abc.googlevideo.com/videoplayback?live=1&sq=5"))
        assertTrue(!GoogleVideoRangeProxy.eligible("https://manifest.googlevideo.com/api/manifest/hls_playlist/x"))
        assertTrue(!GoogleVideoRangeProxy.eligible("https://evil.example/videoplayback?clen=5"))
        assertTrue(!GoogleVideoRangeProxy.eligible("http://rr2---sn-abc.googlevideo.com/videoplayback?clen=5"))
    }

    @Test
    fun `google drive downloads are proxied`() {
        assertTrue(GoogleVideoRangeProxy.eligible("https://drive.usercontent.google.com/download?id=abc&export=download&confirm=t"))
        assertTrue(!GoogleVideoRangeProxy.eligible("https://drive.usercontent.google.com/other?id=abc"))
        assertTrue(!GoogleVideoRangeProxy.eligible("https://evil.example/download?id=abc"))
        assertTrue(!GoogleVideoRangeProxy.eligible("http://drive.usercontent.google.com/download?id=abc"))
    }
}
