package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.util.daemon
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class UpstreamChunk(val status: Int, val total: Long?, val contentType: String?, val bytes: ByteArray)

internal fun interface UpstreamFetcher {
    fun fetch(url: String, from: Long, to: Long): UpstreamChunk
}

internal class RangeProxyServer(
    private val upstream: UpstreamFetcher,
    private val firstChunkBytes: Long = DEFAULT_FIRST_CHUNK_BYTES,
    private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)

    internal class Entry(@Volatile var url: String, @Volatile var total: Long?)

    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
    }

    private val random = SecureRandom()

    private val prefetch = Executors.newCachedThreadPool { r -> daemon(r, "MediaPlayer-range-proxy-fetch") }

    @Volatile
    private var server: ServerSocket? = null

    /** Registers [url] (of [total] bytes when known) and returns the loopback URL serving it, or null if unavailable. */
    fun register(url: String, total: Long?): String? {
        val port = ensureStarted() ?: return null
        val token = HexFormat.of().formatHex(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        synchronized(entries) { entries[token] = Entry(url, total) }
        return "http://$LOOPBACK_HOST:$port/$token"
    }

    /** The registered entry behind [token], for tests. */
    internal fun entry(token: String): Entry? = synchronized(entries) { entries[token] }

    /** Stops accepting connections; requests already being served run to completion. */
    override fun close() {
        server?.close()
        server = null
        prefetch.shutdownNow()
    }

    @Synchronized
    private fun ensureStarted(): Int? {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        return try {
            val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
            server = socket
            daemon({ acceptLoop(socket) }, "MediaPlayer-range-proxy").start()
            socket.localPort
        } catch (e: IOException) {
            logger.warn("Loopback range proxy unavailable, media will be fetched directly: ${e.message}.")
            null
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            daemon({ serve(client) }, "MediaPlayer-range-proxy-conn").start()
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use { s ->
                s.soTimeout = CLIENT_READ_TIMEOUT_MS
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val requestLine = readLine(input) ?: return
                var range: String? = null
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0 && line.substring(0, colon).trim().equals("Range", ignoreCase = true)) {
                        range = line.substring(colon + 1).trim()
                    }
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0) ?: return
                val token = parts.getOrNull(1)?.removePrefix("/") ?: return
                val entry = synchronized(entries) { entries[token] }
                    ?: return writeStatus(out, 404, "Not Found")
                if (method != "GET" && method != "HEAD") return writeStatus(out, 405, "Method Not Allowed")
                val requested = parseRange(range) ?: return writeStatus(out, 416, "Range Not Satisfiable")
                relay(entry, out, requested.first, requested.second, partial = range != null, headOnly = method == "HEAD")
            }
        } catch (e: IOException) {
            logger.debug("Range proxy connection ended: ${e.message}.")
        }
    }

    private fun relay(entry: Entry, out: OutputStream, from: Long, requestedTo: Long?, partial: Boolean, headOnly: Boolean) {
        val knownTotal = entry.total
        if (knownTotal != null && from >= knownTotal) {
            return writeStatus(out, 416, "Range Not Satisfiable", "Content-Range: bytes */$knownTotal\r\n")
        }
        val first = try {
            upstream.fetch(entry.url, from, chunkEnd(from, firstChunkBytes, requestedTo, knownTotal))
        } catch (e: IOException) {
            logger.debug("Range proxy upstream failed before any byte was sent: ${e.message}.")
            return writeStatus(out, 502, "Bad Gateway")
        }
        if (first.status != 206) {
            val code = if (first.status in 200..299) 502 else first.status
            return writeStatus(out, code, reasonFor(code))
        }
        val total = knownTotal ?: first.total ?: return writeStatus(out, 502, "Bad Gateway")
        entry.total = total
        if (from >= total) return writeStatus(out, 416, "Range Not Satisfiable", "Content-Range: bytes */$total\r\n")
        val end = minOf(requestedTo ?: (total - 1), total - 1)

        val head = StringBuilder()
            .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            .append("Content-Type: ").append(first.contentType ?: "application/octet-stream").append("\r\n")
            .append("Content-Length: ").append(end - from + 1).append("\r\n")
            .append("Accept-Ranges: bytes\r\n")
        if (partial) head.append("Content-Range: bytes ").append(from).append('-').append(end).append('/').append(total).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
        if (headOnly || first.bytes.isEmpty()) return

        var pos = from
        var current = first.bytes
        var next: Future<ByteArray>? = null
        try {
            while (true) {
                val length = minOf(current.size.toLong(), end - pos + 1).toInt()
                val nextPos = pos + length
                next = if (nextPos <= end) {
                    val to = chunkEnd(nextPos, chunkBytes, end, total)
                    prefetch.submit<ByteArray> { fetchFollowUp(entry, nextPos, to) }
                } else null
                out.write(current, 0, length)
                out.flush()
                pos = nextPos
                val pending = next ?: return
                current = try {
                    pending.get()
                } catch (e: ExecutionException) {
                    throw (e.cause as? IOException) ?: IOException(e.cause)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException(e)
                }
                next = null
                if (current.isEmpty()) throw IOException("Upstream returned an empty chunk at $pos.")
            }
        } finally {
            next?.cancel(true)
        }
    }

    private fun fetchFollowUp(entry: Entry, from: Long, to: Long): ByteArray {
        val chunk = upstream.fetch(entry.url, from, to)
        if (chunk.status != 206) throw IOException("Upstream answered ${chunk.status} mid-stream at $from.")
        return chunk.bytes
    }

    companion object {
        const val DEFAULT_FIRST_CHUNK_BYTES = 256L * 1024L
        const val DEFAULT_CHUNK_BYTES = 2L * 1024L * 1024L

        private const val DEFAULT_MAX_ENTRIES = 512
        private const val TOKEN_BYTES = 16
        private const val BACKLOG = 64
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val CLIENT_READ_TIMEOUT_MS = 30_000
        private const val MAX_LINE_BYTES = 8 * 1024

        private fun chunkEnd(from: Long, size: Long, limit: Long?, total: Long?): Long {
            var to = from + size - 1
            if (limit != null) to = minOf(to, limit)
            if (total != null) to = minOf(to, total - 1)
            return to
        }

        internal fun parseRange(header: String?): Pair<Long, Long?>? {
            if (header == null) return 0L to null
            if (!header.startsWith("bytes=", ignoreCase = true)) return null
            val spec = header.substring(6).trim()
            if (spec.contains(',')) return null
            val dash = spec.indexOf('-')
            if (dash <= 0) return null
            val from = spec.substring(0, dash).trim().toLongOrNull() ?: return null
            val toText = spec.substring(dash + 1).trim()
            if (toText.isEmpty()) return from to null
            val to = toText.toLongOrNull() ?: return null
            return if (to >= from) from to to else null
        }

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.isEmpty()) null else line.toString()
                if (b == '\n'.code) break
                if (b != '\r'.code) line.append(b.toChar())
                if (line.length > MAX_LINE_BYTES) return null
            }
            return line.toString()
        }

        private fun writeStatus(out: OutputStream, code: Int, reason: String, extraHeaders: String = "") {
            out.write("HTTP/1.1 $code $reason\r\n${extraHeaders}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        private fun reasonFor(code: Int): String = when (code) {
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            410 -> "Gone"
            416 -> "Range Not Satisfiable"
            429 -> "Too Many Requests"
            in 500..599 -> "Server Error"
            else -> "Error"
        }
    }
}
