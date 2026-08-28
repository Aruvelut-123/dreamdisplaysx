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
     * How long to keep the temporary player playing after a seek before consuming the frame. The
     * first display(s) after a seek can be stale pre-seek pictures (get_time already reports the
     * target but the pixels are an old frame); letting the vout settle lets later displays overwrite
     * the capture with the true target frame. Too large makes hover laggy, too small misses the frame.
     */
    private const val SCRUB_SETTLE_MS = 300L

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

            // Force SOFTWARE decoding for the temporary extractor player: it shares the global
            // libvlc instance whose --avcodec-hw=dxva2 option would otherwise make it decode on the
            // GPU too. DXVA2 + vmem copy-back on libvlc 3.0 can hand back a stale GPU surface after a
            // seek (get_time reaches the target but the copied-back pixels are still an old frame),
            // which would make every scrub thumbnail the opening frame. Software decode has no such
            // stale-surface path and is plenty fast for a single thumbnail.
            val media = LibVlc.createMedia(
                url,
                LibVlcMediaOptions.forUrl(url) + arrayOf(":no-audio", ":avcodec-hw=none")
            )
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

            // Seek to the requested offset and grab the resulting frame.
            if (offsetNanos > 0) {
                // libvlc silently ignores set_time while the media is not yet seekable (the player
                // keeps playing from 0, so every sample would grab an opening frame). Wait for
                // seekability first — bounded, because some URLs never report it.
                pollSeekable(lib, mp)
                val targetMs = offsetNanos / 1_000_000L
                // Seek while PLAYING, then gate the display callback by get_time. The classic paused
                // seek (pause → set_time → vout renders the target frame) has a libvlc 3.0 vmem
                // race: get_time jumps to the target instantly but the vout can still render a stale
                // PRE-seek picture whose CONTENT is the opening frame — the time gate passes because
                // get_time already reports the target, yet the pixels are the first frame. Playing
                // (even briefly) forces the decoder to actually decode up to the target position and
                // display the correct frame.
                grab.clearCaptured()
                val seekLatch = CountDownLatch(1)
                grab.timeProvider = { runCatching { lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L) }
                grab.acceptAfterMs = (targetMs - 400L).coerceAtLeast(0L)
                grab.onFrame = { if (seekLatch.count > 0) seekLatch.countDown() }
                val preSeekTime = runCatching { lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                LibVlc.lib.libvlc_media_player_set_time(mp, targetMs)
                if (!awaitPosition(lib, mp, targetMs)) {
                    logger.warn("Frame extraction: seek did not reach target for $url@$offsetNanos")
                    return null
                }
                val postSeekTime = runCatching { lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                // Keep the player PLAYING after the seek so the decoder actually decodes up to the
                // target. The very first display(s) after a seek can be stale PRE-seek pictures —
                // get_time already reports the target (so the gate passes) but the pixels are still
                // an old frame; this is especially true on network seeks, which must re-request
                // fragments before the real target frame appears. Every subsequent display overwrites
                // `captured`, so wait for the first gated display, then a short settle window, then
                // consume the LATEST captured frame (the true target frame).
                if (!seekLatch.await(2, TimeUnit.SECONDS)) {
                    logger.warn("Frame extraction: seek frame timeout for $url@$offsetNanos")
                    return null
                }
                try {
                    Thread.sleep(SCRUB_SETTLE_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                val frameTime = runCatching { lib.libvlc_media_player_get_time(mp) }.getOrDefault(-1L)
                logger.info(
                    "SCRUB-DEBUG target={}ms preSeek={}ms postSeek={}ms frameTime={}ms displays={}",
                    targetMs, preSeekTime, postSeekTime, frameTime, grab.displaysSinceClear
                )
                // Pause the player so it stops consuming resources while we encode the frame.
                runCatching { LibVlc.lib.libvlc_media_player_set_pause(mp, 1) }
                grab.acceptAfterMs = -1L
                grab.timeProvider = null
            }

            val frame = grab.consumeFrame()
                ?: run { logger.warn("Frame extraction: no frame data for $url@$offsetNanos"); return null }

            // Diagnostic: hash the captured pixels so we can tell whether different hover positions
            // produce different frame content (extraction working) or the same content every time
            // (the vout is handing back a stale pre-seek picture despite reporting the target time).
            if (offsetNanos > 0) {
                val crc = java.util.zip.CRC32()
                frame.duplicate().rewind().let { src ->
                    val arr = ByteArray(1024)
                    while (src.hasRemaining()) {
                        val n = minOf(1024, src.remaining())
                        src.get(arr, 0, n)
                        crc.update(arr, 0, n)
                    }
                }
                logger.info("SCRUB-CRC target={}ms crc={} bytes={}", offsetNanos / 1_000_000L, crc.value, frame.remaining())
            }

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
     * Polls [libvlc_media_player_get_state] until it reports Paused, so a subsequent seek is applied
     * to a settled, paused vout (deterministic frame render). Bounded because set_pause is async and
     * the state may briefly stay Buffering/Playing; never fails the extraction.
     */
    private fun awaitPaused(lib: LibVlc.LibVlcNative, mp: Pointer) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            try {
                if (lib.libvlc_media_player_get_state(mp) == LibVlc.LIBVLC_STATE_PAUSED) return
            } catch (_: Throwable) {
                return
            }
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /**
     * Polls [libvlc_media_player_get_time] until it reaches within [toleranceMs] of [targetMs].
     * Returns true when the position actually reached the target region (a successful seek), or
     * false when it timed out (seek was ignored / failed — the caller must not grab a frame).
     */
    private fun awaitPosition(lib: LibVlc.LibVlcNative, mp: Pointer, targetMs: Long): Boolean {
        val tolerance = 600L
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            try {
                val t = lib.libvlc_media_player_get_time(mp)
                if (t >= targetMs - tolerance) return true
            } catch (_: Throwable) {
                return false
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /**
     * Single-frame I420 grabber: the setup callback reports dimensions and the lock callback hands
     * libvlc a direct buffer; the display callback copies the YUV planes into a private buffer and
     * latches. All callbacks are held as strong fields for the lifetime of the grab (no vlcj).
     *
     * libvlc's vmem runs lock/unlock/display on different threads with several pictures in flight,
     * so each lock is given its own buffer from a small pool and the lock token is echoed back to
     * display so it copies the buffer that THIS picture was actually decoded into — never a buffer
     * reused by a later lock.
     */
    private class FrameGrab {
        companion object {
            private const val POOL_SIZE = 4
        }

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
        private val yPlanes = arrayOfNulls<ByteBuffer>(POOL_SIZE)
        private var nextBuffer = 0
        private var captured: ByteBuffer? = null

        val setupLatch = CountDownLatch(1)
        val firstFrameLatch = CountDownLatch(1)
        @Volatile var frameSeen = false
        @Volatile var onFrame: () -> Unit = {}

        /** Total display callbacks since the last [clearCaptured] (diagnostic: how many frames the vout rendered after a seek). */
        @Volatile var displaysSinceClear = 0L

        /**
         * When >= 0, only a display whose reported position is at/after this time (ms) is accepted
         * into [captured] / [onFrame]. Set to the seek target before [libvlc_media_player_set_time]
         * so the latch cannot be satisfied by a stale PRE-seek (opening) frame still queued in the
         * vout — the reason every on-demand scrub frame came out as the opening frame.
         */
        @Volatile var acceptAfterMs = -1L

        /** Reports the current media position (ms) — used by [display] to gate [acceptAfterMs]. */
        @Volatile var timeProvider: (() -> Long)? = null

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
            val index = nextBuffer
            nextBuffer = (nextBuffer + 1) % POOL_SIZE
            val buf = yPlanes[index]
                ?: ByteBuffer.allocateDirect(total).order(ByteOrder.nativeOrder()).also { yPlanes[index] = it }
            // Reallocate if a later format changed the frame size larger.
            if (buf.capacity() < total) {
                val bigger = ByteBuffer.allocateDirect(total).order(ByteOrder.nativeOrder())
                yPlanes[index] = bigger
                val ptr = Native.getDirectBufferPointer(bigger)
                planes.setPointer(0, ptr)
                planes.setPointer(Native.POINTER_SIZE.toLong(), ptr.share(ySize.toLong()))
                planes.setPointer((2 * Native.POINTER_SIZE).toLong(), ptr.share((ySize + uvSize).toLong()))
                return Pointer.createConstant((index + 1).toLong())
            }
            val ptr = Native.getDirectBufferPointer(buf)
            planes.setPointer(0, ptr)
            planes.setPointer(Native.POINTER_SIZE.toLong(), ptr.share(ySize.toLong()))
            planes.setPointer((2 * Native.POINTER_SIZE).toLong(), ptr.share((ySize + uvSize).toLong()))
            return Pointer.createConstant((index + 1).toLong())
        }

        private fun display(picture: Pointer?) {
            if (picture == null) return
            val token = Pointer.nativeValue(picture)
            if (token <= 0 || token > POOL_SIZE) return
            val index = (token - 1).toInt()
            val w = frameW
            val h = frameH
            if (w <= 0 || h <= 0) return
            displaysSinceClear++
            val ySize = w * h
            val uvSize = ((w + 1) / 2) * ((h + 1) / 2)
            val total = ySize + 2 * uvSize
            val base = yPlanes[index] ?: return
            if (base.capacity() < total) return
            val copy = ByteBuffer.allocateDirect(total).order(ByteOrder.nativeOrder())
            base.duplicate().rewind().let { src -> for (i in 0 until total) copy.put(src.get()) }
            copy.flip()
            // Drop frames that are still before the seek target (a stale pre-seek picture queued in
            // the vout would otherwise satisfy the seek latch with the opening frame). Only accept
            // once the reported position is at/after the target.
            val gate = acceptAfterMs
            if (gate >= 0) {
                val now = timeProvider?.invoke() ?: gate
                if (now < gate) return
            }
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

        /** Discards any frame captured so far (used right after a seek). */
        fun clearCaptured() {
            captured = null
            frameSeen = false
            displaysSinceClear = 0L
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
