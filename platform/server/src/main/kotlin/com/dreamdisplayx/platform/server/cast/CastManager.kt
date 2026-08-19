package com.dreamdisplayx.platform.server.cast

import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory

/**
 * The server-side relay for screen-sharing casts: owns the per-cast [CastBuffer]s and exposes each
 * live cast as a chunked MPEG-TS HTTP endpoint that the existing playback pipeline can open
 * (`http://<host>:<port>/cast/<castId>`). Only ever active on a modded server (v2 peer).
 */
object CastManager {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/CastManager")

    private val casts = ConcurrentHashMap<String, CastBuffer>()

    @Volatile
    private var httpServer: HttpServer? = null

    /** Base URL prefix (scheme + host + port) once the HTTP server is up; empty before [start]. */
    @Volatile
    private var baseUrl = ""

    /**
     * Starts the relay HTTP server (idempotent). [publicHost] is the hostname/address viewers use to
     * reach the server, or blank to default to `localhost`.
     */
    fun start(port: Int, publicHost: String) {
        if (httpServer != null) return
        val host = publicHost.ifBlank { "localhost" }
        val server = runCatching { HttpServer.create(InetSocketAddress(port), 0) }.getOrElse { e ->
            logger.error("Could not start screen-share HTTP server on port {}: {}.", port, e.message)
            return
        }
        server.createContext("/cast/") { exchange -> handleCastRequest(exchange) }
        server.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "dreamdisplayx-cast-http").also { it.isDaemon = true }
        }
        server.start()
        httpServer = server
        baseUrl = "http://$host:$port"
        logger.info("Screen-share relay listening at {} ({} cast(s) active).", baseUrl, casts.size)
    }

    /** Stops the relay and drops every cast. */
    fun stop() {
        casts.values.forEach { it.close() }
        casts.clear()
        httpServer?.stop(0)
        httpServer = null
    }

    /** Registers a new cast and returns the watch URL for [castId]. */
    fun handleStart(castId: String, width: Int, height: Int): String? {
        if (httpServer == null) return null
        casts.computeIfAbsent(castId) { CastBuffer(castId, width, height) }
        return "$baseUrl/cast/$castId"
    }

    /** Appends one encoded chunk to the cast, if it exists. */
    fun handleData(castId: String, sequence: Int, payload: ByteArray) {
        casts[castId]?.append(sequence, payload)
    }

    /** Ends a cast and drops its buffer. */
    fun handleStop(castId: String) {
        casts.remove(castId)?.close()
    }

    /** Live buffer for [castId], or null when no such cast is active. */
    fun buffer(castId: String): CastBuffer? = casts[castId]

    /** Serves one cast as a chunked MPEG-TS stream until the cast closes or the viewer disconnects. */
    private fun handleCastRequest(exchange: HttpExchange) {
        val castId = exchange.requestURI.path.removePrefix("/cast/")
        val buffer = casts[castId]
        if (buffer == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        exchange.responseHeaders.add("Content-Type", "video/mp2t")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, 0)
        try {
            buffer.copyTo(exchange.responseBody)
        } catch (_: IOException) {
            // Viewer disconnected; nothing to clean up beyond the exchange itself.
        } finally {
            exchange.close()
        }
    }
}
