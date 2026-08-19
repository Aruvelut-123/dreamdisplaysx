package com.dreamdisplayx.platform.client.screenshare

import com.dreamdisplayx.core.protocol.common.packets.ScreenShareData
import com.dreamdisplayx.core.protocol.common.packets.ScreenShareStart
import com.dreamdisplayx.core.protocol.common.packets.ScreenShareStop
import com.dreamdisplayx.media.player.process.FFmpegBinary
import com.dreamdisplayx.media.player.process.MediaProcess
import com.dreamdisplayx.platform.client.net.ProtocolRouter
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the lifecycle of a single active screen-sharing cast over the mod's own protocol: FFmpeg
 * captures + encodes the screen to a low-latency MPEG-TS stream on stdout, a background thread
 * relays the bytes to the server as [ScreenShareData] chunks, and the server hands viewers a watch
 * URL. Only works on a modded (v2) server — on a legacy peer the packets are dropped, so nothing
 * happens.
 */
object ScreenShareManager {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ScreenShare")

    /** Maximum payload per [ScreenShareData] chunk, well under the Minecraft packet ceiling. */
    private const val CHUNK_SIZE = 16 * 1024

    private val active = AtomicReference<Process?>(null)

    @Volatile
    private var uploadThread: Thread? = null

    @Volatile
    private var castId: String = ""

    /** True while a screen-share FFmpeg process is running. */
    val isActive: Boolean get() = active.get() != null

    /**
     * Starts a screen-sharing cast to the connected modded server. Any existing session is stopped
     * first.
     *
     * @throws IllegalStateException with a user-facing message when the platform is unsupported or
     * the FFmpeg binary is unavailable.
     */
    fun start() {
        stop()
        ScreenShare.unsupportedReason()?.let { throw IllegalStateException(it) }
        val ffmpeg = FFmpegBinary.getPath() ?: throw IllegalStateException("FFmpeg binary is unavailable.")
        val command = ScreenShare.buildCastCommand(ffmpeg)
        val id = UUID.randomUUID().toString()
        logger.info("Starting screen-share cast {}: {}", id, command.joinToString(" "))
        val process = ProcessBuilder(command).start()
        active.set(process)
        castId = id
        ProtocolRouter.send(ScreenShareStart(castId))
        Thread({ relayStdout(process, id) }, "dreamdisplayx-screen-share").also { uploadThread = it }.start()
    }

    /** Stops an in-progress screen share (if any) and tells the server the cast ended. */
    fun stop() {
        val id = castId
        castId = ""
        if (id.isNotEmpty()) ProtocolRouter.send(ScreenShareStop(id))
        uploadThread = null
        val process = active.getAndSet(null) ?: return
        logger.info("Stopping screen-share cast {}.", id)
        MediaProcess.gracefulDestroy(process)
    }

    /** Reads the FFmpeg stdout and relays it to the server in ordered chunks until the process ends. */
    private fun relayStdout(process: Process, id: String) {
        val input = BufferedInputStream(process.inputStream, CHUNK_SIZE)
        val buffer = ByteArray(CHUNK_SIZE)
        var sequence = 0
        try {
            while (active.get() === process) {
                val read = input.read(buffer)
                if (read <= 0) break
                val payload = buffer.copyOf(read)
                ProtocolRouter.send(ScreenShareData(id, sequence++, payload))
            }
        } catch (_: IOException) {
            // Process killed underneath us; the shutdown path already sent ScreenShareStop.
        } finally {
            runCatching { input.close() }
        }
    }
}
