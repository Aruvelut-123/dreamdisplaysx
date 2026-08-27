@file:Suppress("Since15")

package com.dreamdisplayx.media.player.process

import com.dreamdisplayx.media.player.managers.LibVlc
import com.dreamdisplayx.media.player.util.LibVlcMediaOptions
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * In-process frame extraction for scrub-preview thumbnails, driven by the low-level libvlc binding
 * (no vlcj). Opens the media URL on a temporary media player, seeks to a timestamp, decodes one
 * frame through the low-level video callbacks, and encodes it as a JPEG byte array.
 *
 * This replaces the old vlcj-based extractor whose `CallbackVideoSurface` was rebuilt per sample
 * without a strong reference, so JNA garbage-collected the trampolines mid-playback and spammed
 * `JNA: callback object has been garbage collected` / `Invalid memory access`.
 */
object LibVlcFrameExtractor {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/LibVlcFrameExtractor")

    private const val SETUP_TIMEOUT_MS = 15_000L

    /**
     * Extracts a single frame at [offsetNanos] from [url], scales it to [w]x[h] (maintaining aspect),
     * and returns JPEG bytes, or null on any failure.
     */
    fun extractJpeg(url: String, offsetNanos: Long, w: Int, h: Int): ByteArray? {
        if (!LibVlc.ensureLoaded()) {
            logger.warn("LibVLC not available for frame extraction.")
            return null
        }
        val lib = LibVlc.lib
        val mp = lib.libvlc_media_player_new(LibVlc.libvlcInstance)
            ?: run { logger.warn("libvlc_media_player_new failed: ${LibVlc.errmsg()}"); return null }

        val grab = FrameGrab()
        try {
            // Register the low-level format + video callbacks once on this short-lived player.
            lib.libvlc_video_set_format_callbacks(mp, grab.formatCb, grab.cleanupCb)
            lib.libvlc_video_set_callbacks(mp, grab.lockCb, grab.unlockCb, grab.displayCb, null)

            val media = LibVlc.createMedia(url, LibVlcMediaOptions.forUrl(url) + arrayOf(":no-audio"))
            lib.libvlc_media_player_set_media(mp, media)
            lib.libvlc_media_release(media)
            lib.libvlc_media_player_play(mp)

            // Wait for the first decoded frame (this proves the format + lock/display callbacks ran).
            if (!grab.setupLatch.await(SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.warn("Frame extraction: no video setup for $url@$offsetNanos (err=${LibVlc.errmsg()})")
                return null
            }
            if (!grab.firstFrameLatch.await(SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.warn("Frame extraction: no first frame for $url@$offsetNanos (err=${LibVlc.errmsg()})")
                return null
            }

            // Seek to the requested offset and wait for a fresh frame.
            if (offsetNanos > 0) {
                // libvlc silently ignores set_time while the media is not yet seekable (the player
                // keeps playing from 0, so every sample would grab an opening frame). Wait for
                // seekability first — bounded, because some URLs never report it.
                pollSeekable(lib, mp)
                val targetMs = offsetNanos / 1_000_000L
                // libvlc_media_player_set_time takes MILLISECONDS; convert ns -> ms.
                lib.libvlc_media_player_set_time(mp, targetMs)
                // The seek is asynchronous: pre-seek frames that were already in flight will still
                // hit the display callback (and satisfy a naïve latch with a stale frame, which is
                // why every thumbnail used to be the opening frame). Wait until the player clock
                // actually reaches the target region before latching the next displayed frame.
                awaitPosition(lib, mp, targetMs)
                val seekLatch = CountDownLatch(1)
                grab.onFrame = { seekLatch.countDown() }
                if (!seekLatch.await(10, TimeUnit.SECONDS)) {
                    logger.warn("Frame extraction: seek frame timeout for $url@$offsetNanos")
                    return null
                }
            }

            val frame = grab.consumeFrame()
                ?: run { logger.warn("Frame extraction: no frame data for $url@$offsetNanos"); return null }

            val image = i420ToBufferedImage(frame, grab.frameW, grab.frameH) ?: return null
            val scaled = scale(image, w, h)
            val out = ByteArrayOutputStream()
            if (!ImageIO.write(scaled, "jpg", out)) {
                logger.warn("ImageIO has no JPEG writer; returning scaled PNG instead.")
                ByteArrayOutputStream().use { pngOut ->
                    ImageIO.write(scaled, "png", pngOut)
                    return pngOut.toByteArray()
                }
            }
            return out.toByteArray()
        } catch (e: Exception) {
            logger.warn("Frame extraction failed for $url@$offsetNanos: ${e.message}")
            return null
        } finally {
            runCatching { lib.libvlc_media_player_stop(mp) }
            runCatching { lib.libvlc_media_player_release(mp) }
        }
    }

    /**
     * Polls [libvlc_media_player_is_seekable] until true or a bounded timeout, so a subsequent
     * [libvlc_media_player_set_time] is not silently ignored. Returns when seekable or timed out.
     */
    private fun pollSeekable(lib: LibVlc.LibVlcNative, mp: Pointer) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            try {
                if (lib.libvlc_media_player_is_seekable(mp) != 0) return
            } catch (_: Throwable) {
                return
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /**
     * Polls [libvlc_media_player_get_time] until it reaches within [toleranceMs] of [targetMs]
     * (or times out), so the next displayed frame is guaranteed to come from the seeked region
     * rather than a stale pre-seek frame.
     */
    private fun awaitPosition(lib: LibVlc.LibVlcNative, mp: Pointer, targetMs: Long) {
        val tolerance = 600L
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            try {
                val t = lib.libvlc_media_player_get_time(mp)
                if (t >= targetMs - tolerance) return
            } catch (_: Throwable) {
                return
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /**
     * Single-frame I420 grabber: the setup callback reports dimensions and the lock callback hands
     * libvlc a direct buffer; the display callback copies the YUV planes into a private buffer and
     * latches. All callbacks are held as strong fields for the lifetime of the grab (no vlcj).
     */
    private class FrameGrab {
        // Strong references — never dropped while the player may touch them.
        val formatCb = LibVlc.VideoFormatCallback { _opaque, chroma, width, height, pitches, lines ->
            setup(chroma, width, height, pitches, lines)
        }
        val cleanupCb = LibVlc.VideoCleanupCallback { }
        val lockCb = LibVlc.VideoLockCallback { _opaque, planes -> lock(planes) }
        val unlockCb = LibVlc.VideoUnlockCallback { _, _, _ -> }
        val displayCb = LibVlc.VideoDisplayCallback { _opaque, picture -> display(picture) }

        @Volatile var frameW = 0
        @Volatile var frameH = 0
        private var yPlane: ByteBuffer? = null
        private var uPlane: ByteBuffer? = null
        private var vPlane: ByteBuffer? = null
        private var captured: ByteBuffer? = null

        val setupLatch = CountDownLatch(1)
        val firstFrameLatch = CountDownLatch(1)
        @Volatile var frameSeen = false
        @Volatile var onFrame: () -> Unit = {}

        private fun setup(chroma: Pointer?, width: Pointer?, height: Pointer?, pitches: Pointer?, lines: Pointer?): Int {
            if (width == null || height == null || chroma == null || pitches == null || lines == null) return 0
            val w = width.getInt(0)
            val h = height.getInt(0)
            if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return 0
            frameW = w
            frameH = h
            // I420 chroma.
            val i420 = byteArrayOf('I'.code.toByte(), '4'.code.toByte(), '2'.code.toByte(), '0'.code.toByte())
            chroma.write(0, i420, 0, i420.size)
            pitches.setInt(0, w)
            pitches.setInt(4, (w + 1) / 2)
            pitches.setInt(8, (w + 1) / 2)
            lines.setInt(0, h)
            lines.setInt(4, (h + 1) / 2)
            lines.setInt(8, (h + 1) / 2)
            setupLatch.countDown()
            return 1
        }

        private fun lock(planes: Pointer?): Pointer? {
            if (planes == null) return LibVlc.dropToken()
            val w = frameW
            val h = frameH
            if (w <= 0 || h <= 0) return LibVlc.dropToken()
            val ySize = w * h
            val uvSize = ((w + 1) / 2) * ((h + 1) / 2)
            val total = ySize + 2 * uvSize
            val buf = ByteBuffer.allocateDirect(total).order(ByteOrder.nativeOrder())
            yPlane = buf
            uPlane = buf.duplicate()
            vPlane = buf.duplicate()
            val ptr = Native.getDirectBufferPointer(buf)
            planes.setPointer(0, ptr)
            planes.setPointer(Native.POINTER_SIZE.toLong(), ptr.share(ySize.toLong()))
            planes.setPointer((2 * Native.POINTER_SIZE).toLong(), ptr.share((ySize + uvSize).toLong()))
            return Pointer.createConstant(1L)
        }

        private fun display(picture: Pointer?) {
            if (picture == null) return
            val w = frameW
            val h = frameH
            if (w <= 0 || h <= 0) return
            val ySize = w * h
            val uvSize = ((w + 1) / 2) * ((h + 1) / 2)
            val total = ySize + 2 * uvSize
            val base = yPlane ?: return
            if (base.capacity() < total) return
            val copy = ByteBuffer.allocateDirect(total).order(ByteOrder.nativeOrder())
            base.duplicate().rewind().let { src -> for (i in 0 until total) copy.put(src.get()) }
            copy.flip()
            captured = copy
            frameSeen = true
            onFrame()
            firstFrameLatch.countDown()
        }

        fun consumeFrame(): ByteBuffer? {
            val f = captured ?: return null
            captured = null
            return f
        }
    }

    /** Converts packed I420 buffer to a BufferedImage (RGB). */
    private fun i420ToBufferedImage(i420: ByteBuffer, w: Int, h: Int): BufferedImage? {
        if (w <= 0 || h <= 0) return null
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val ySize = w * h
        val uvSize = ((w + 1) / 2) * ((h + 1) / 2)
        i420.rewind()
        for (row in 0 until h) {
            for (col in 0 until w) {
                val y = i420.get(row * w + col).toInt() and 0xFF
                val u = i420.get(ySize + (row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF
                val v = i420.get(ySize + uvSize + (row / 2) * ((w + 1) / 2) + (col / 2)).toInt() and 0xFF
                var r = (y + 1.402 * (v - 128)).toInt().coerceIn(0, 255)
                var g = (y - 0.344 * (u - 128) - 0.714 * (v - 128)).toInt().coerceIn(0, 255)
                var b = (y + 1.772 * (u - 128)).toInt().coerceIn(0, 255)
                image.setRGB(col, row, (r shl 16) or (g shl 8) or b)
            }
        }
        i420.rewind()
        return image
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
