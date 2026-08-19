package com.dreamdisplayx.platform.client.screenshare

import com.dreamdisplayx.media.player.process.FFmpegBinary
import com.dreamdisplayx.media.player.process.MediaProcess
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the lifecycle of a single active screen-share session: starting it spawns one FFmpeg
 * process that captures the screen, encodes it, and pushes it to an RTMP endpoint; stopping it
 * tears that process down. Exactly one session may be active at a time.
 */
object ScreenShareManager {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ScreenShare")

    private val active = AtomicReference<Process?>(null)

    /** True while a screen-share FFmpeg process is running. */
    val isActive: Boolean get() = active.get() != null

    /**
     * Starts pushing the user's screen to [rtmpUrl]. Any existing session is stopped first.
     *
     * @throws IllegalStateException with a user-facing message when the platform is unsupported or
     * the FFmpeg binary is unavailable.
     */
    fun start(rtmpUrl: String) {
        stop()
        ScreenShare.unsupportedReason()?.let { throw IllegalStateException(it) }
        val ffmpeg = FFmpegBinary.getPath() ?: throw IllegalStateException("FFmpeg binary is unavailable.")
        val command = ScreenShare.buildCommand(ffmpeg, rtmpUrl)
        logger.info("Starting screen share to {}: {}", rtmpUrl, command.joinToString(" "))
        active.set(ProcessBuilder(command).start())
    }

    /** Stops an in-progress screen share, if any. Safe to call when nothing is running. */
    fun stop() {
        val process = active.getAndSet(null) ?: return
        logger.info("Stopping screen share.")
        MediaProcess.gracefulDestroy(process)
    }
}
