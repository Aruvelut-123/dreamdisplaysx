@file:Suppress("Since15")

package com.dreamdisplayx.media.player.process

import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import org.slf4j.LoggerFactory
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

/**
 * In-process frame extraction for scrub-preview thumbnails — replaces the FFmpeg CLI
 * `buildFrameExtract` path with a JavaCPP [FFmpegFrameGrabber]. Opens the media URL,
 * seeks to a timestamp, decodes one video frame, and encodes it as a JPEG byte array.
 */
object JavaCppFrameExtractor {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/JavaCppFrameExtractor")

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Extracts a single frame at [offsetNanos] from [url], scales it to [w]x[h] (maintaining aspect),
     * and returns JPEG bytes, or null on any failure.
     */
    fun extractJpeg(url: String, offsetNanos: Long, w: Int, h: Int): ByteArray? {
        val g = safeGrabber(url) ?: return null
        return try {
            // Seek to the target timestamp (FFmpegFrameGrabber timestamps are in microseconds)
            if (offsetNanos > 0) {
                g.setTimestamp(offsetNanos / 1000L)
            }
            // Grab a few frames after the sync point so the decoder isn't sitting on a keyframe boundary
            var frame: Frame? = null
            for (i in 0 until 8) {
                frame = g.grabImage() ?: break
                if (frame.image != null && !frame.image.isEmpty()) break
            }
            val f = frame ?: return null
            if (f.image == null || f.image.isEmpty()) return null

            Java2DFrameConverter().use { converter ->
                val image = converter.convert(f) ?: return null
                val scaled = scale(image, w, h)
                val out = ByteArrayOutputStream()
                if (!ImageIO.write(scaled, "jpg", out)) {
                    logger.warn("ImageIO has no JPEG writer; returning scaled PNG instead.")
                    ByteArrayOutputStream().use { pngOut ->
                        ImageIO.write(scaled, "png", pngOut)
                        return pngOut.toByteArray()
                    }
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            logger.warn("Frame extraction failed for $url@$offsetNanos: ${e.message}")
            null
        } finally {
            runCatching { g.release() }
        }
    }

    /** Opens and configures a frame grabber, returning null on failure. */
    private fun safeGrabber(url: String): FFmpegFrameGrabber? = try {
        FFmpegFrameGrabber(url).apply {
            setOption("probesize", "1M")
            setOption("analyzeduration", "1000000")
            setOption("rw_timeout", "15000000")
            setOption("user_agent", USER_AGENT)
            setOption("multiple_requests", "1")
            setOption("reconnect", "1")
            setOption("reconnect_streamed", "1")
            setOption("reconnect_delay_max", "10")
            setOption("reconnect_on_network_error", "1")
            setOption("reconnect_on_http_error", "5xx")
            start()
        }
    } catch (e: Exception) {
        logger.warn("Could not open grabber for $url: ${e.message}")
        null
    }

    /** Letterboxes [src] into [w]x[h] keeping aspect; returns [src] itself when the size already matches. */
    private fun scale(src: BufferedImage, w: Int, h: Int): BufferedImage {
        if (src.width == w && src.height == h) return src
        val scaleW = w.toDouble() / src.width
        val scaleH = h.toDouble() / src.height
        val scale = minOf(scaleW, scaleH)
        val dw = (src.width * scale).toInt().coerceAtLeast(1)
        val dh = (src.height * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g2 = out.createGraphics()
        try {
            g2.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null)
        } finally {
            g2.dispose()
        }
        return out
    }
}