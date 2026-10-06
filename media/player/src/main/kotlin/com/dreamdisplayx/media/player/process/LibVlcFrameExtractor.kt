@file:Suppress("Since15")

package com.dreamdisplayx.media.player.process

import com.dreamdisplayx.media.player.managers.LibVlc
import com.dreamdisplayx.media.player.util.LibVlcMediaOptions
import com.dreamdisplayx.media.runtime.security.MediaHostGuard
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * In-process frame extraction for scrub-preview thumbnails, driven by the low-level libvlc binding
 * (no vlcj). Two usage modes:
 *
 *  - [ScrubSession]: a long-lived, video-only libvlc player (one per video) that is created once and
 *    then reused for every scrub frame of that video. Only destroyed/recreated when the video
 *    changes, so the expensive setup (player creation + first-frame decode) happens once instead of
 *    per hover. Each [ScrubSession.extractAt] is a fast seek→render→grab (~100-200ms).
 *
 *  - [extractJpeg]: a one-shot convenience wrapper that spins up a session, extracts a single frame,
 *    and tears it down. Kept for callers that extract rarely (e.g. a one-off thumbnail).
 *
 * This replaces the old vlcj-based extractor whose `CallbackVideoSurface` was rebuilt per sample
 * without a strong reference, so JNA garbage-collected the trampolines mid-playback and spammed
 * `JNA: callback object has been garbage collected` / `Invalid memory access`.
 */
object LibVlcFrameExtractor {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/LibVlcFrameExtractor")

    private const val SETUP_TIMEOUT_MS = 15_000L

    /** Android pause-only scrub sessions and their callback/buffer owners live until process exit. */
    private val ANDROID_KEEP_ALIVE = java.util.Collections.synchronizedList(mutableListOf<ScrubSession>())

    /**
     * How far past the seek target the player must advance before the frame is grabbed. Waiting for
     * position >= target + this value is the reliable signal that the decoder has decoded into the
     * target region and the vout has displayed the target frame (network seeks must load new
     * fragments first). ~300ms per extraction is fine because the player is reused across hovers.
     */
    private const val RENDER_FRAMES_MS = 300L

    /**
     * One-shot extraction: creates a short-lived player, extracts the frame at [offsetNanos], scales
     * it to [w]x[h] (maintaining aspect), returns JPEG bytes or null on any failure.
     */
    fun extractJpeg(url: String, offsetNanos: Long, w: Int, h: Int): ByteArray? {
        if (!LibVlc.ensureLoaded()) {
            logger.warn("LibVLC not available for frame extraction.")
            return null
        }
        val session = ScrubSession(url)
        try {
            if (!session.open()) {
                logger.warn("Frame extraction: could not open session for $url@$offsetNanos")
                return null
            }
            return session.extractAt(offsetNanos, w, h)
        } finally {
            session.close()
        }
    }

    /**
     * A long-lived, video-only libvlc player used to extract many scrub frames of one video.
     * [open] must succeed before any [extractAt]; [close] retires the native player. Public operations are synchronized:
     * a close cannot race a seek or a native callback.
     */
    class ScrubSession(private val url: String) {
        private val lib: LibVlc.LibVlcNative = LibVlc.lib
        private var mp: Pointer? = null
        private val grab = FrameGrab()
        private val opened = AtomicBoolean(false)

        /**
         * Creates the player, registers the low-level vmem callbacks, loads the media, and waits for
         * the first decoded frame. Returns true on success. This is the expensive one-time cost.
         */
        @Synchronized
        fun open(): Boolean {
            if (opened.get()) return true
            val safeUrl = runCatching { MediaHostGuard.resolveSafeUrl(url) }.getOrElse { error ->
                logger.warn("ScrubSession: rejected unsafe media URL {}: {}", url, error.message)
                return false
            }
            if (!LibVlc.ensureLoaded()) return false
            val player = lib.libvlc_media_player_new(LibVlc.libvlcInstance)
                ?: run { logger.warn("ScrubSession: libvlc_media_player_new failed: ${LibVlc.errmsg()}"); return false }
            mp = player
            try {
                // Register the low-level format + video callbacks once for this player.
                lib.libvlc_video_set_format_callbacks(player, grab.formatCb, grab.cleanupCb)
                lib.libvlc_video_set_callbacks(player, grab.lockCb, grab.unlockCb, grab.displayCb, null)

                // Video-only, and force SOFTWARE decoding: the global instance carries
                // --avcodec-hw=dxva2, and DXVA2 + vmem copy-back on libvlc 3.0 can hand back a stale
                // GPU surface after a seek (get_time reaches the target but the pixels are an old
                // frame). Software decode has no stale-surface path and is fast enough for a
                // thumbnail.
                //
                // network-caching=300ms: the libvlc default is ~1500ms, and after a seek the vout
                // only renders once the new fragments have buffered past that mark. On a slow CDN
                // (e.g. some Bilibili edge mirrors) that 1.5s + seek latency blows the 2s phase-2
                // latch, so every scrub times out ("frame timeout" every ~4-5s). A 300ms buffer is
                // plenty for a 360p thumb stream and renders almost immediately after the seek.
                val media = LibVlc.createMedia(
                    safeUrl,
                    LibVlcMediaOptions.forUrl(safeUrl) + arrayOf(
                        ":no-audio",
                        ":avcodec-hw=none",
                        ":network-caching=300"
                    )
                )
                lib.libvlc_media_player_set_media(player, media)
                lib.libvlc_media_release(media)
                lib.libvlc_media_player_play(player)

                if (!grab.setupLatch.await(SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    logger.warn("ScrubSession: no video setup for $url (err=${LibVlc.errmsg()})")
                    return false
                }
                if (!grab.firstFrameLatch.await(SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    logger.warn("ScrubSession: no first frame for $url (err=${LibVlc.errmsg()})")
                    return false
                }
                opened.set(true)
                return true
            } catch (t: Throwable) {
                logger.warn("ScrubSession: open failed for $url: ${t.message}")
                close()
                return false
            }
        }

        /**
         * Seeks the (already-open) player to [offsetNanos] and returns a JPEG scaled to [w]x[h], or
         * null on any failure. Fast: paused seek → brief play (renders the target frame) → pause →
         * grab.
         */
        @Synchronized
        fun extractAt(offsetNanos: Long, w: Int, h: Int): ByteArray? {
            val player = mp ?: return null
            if (!opened.get()) return null

            // Wait for seekability first — libvlc silently ignores set_time while the media is not
            // yet seekable (bounded, because some URLs never report it).
            pollSeekable(player)
            val targetMs = offsetNanos / 1_000_000L

            // Resume from the pause applied at the end of the previous extraction. A PAUSED seek
            // jumps get_time to the target but never advances past it, so awaitPast below would time
            // out on every subsequent hover ("seek did not reach target" + 8s stall). Seeking while
            // PLAYING lets the decoder advance into the target region and render it.
            runCatching { lib.libvlc_media_player_set_pause(player, 0) }
            grab.clearCaptured()
            // ARM the time gate: without a working timeProvider the display callback's gate collapses
            // to `now == gate` (never rejects), so STALE pre-seek frames — in particular frames from
            // a HIGHER position queued in the vout during a BACKWARD seek — pass through and get
            // cached under the new (lower) timestamp, which made the front half of the video show
            // the first frame of the back half, frozen.
            grab.timeProvider = { runCatching { lib.libvlc_media_player_get_time(player) }.getOrDefault(-1L) }
            grab.acceptAfterMs = (targetMs - 400L).coerceAtLeast(0L)
            // NOTE: do NOT seek at a high playback rate (set_rate) — accelerated playback makes the
            // vout skip display frames, so the phase-2 latch below never fires (or fires with a
            // position-jumped frame) and the long-video backward seek regresses. Seek at 1.0x and
            // let awaitPast wait for real decode progress.
            lib.libvlc_media_player_set_time(player, targetMs)
            // First: confirm the seek landed on (at least) the target.
            if (!awaitPast(player, targetMs, 3_000L)) {
                logger.warn("ScrubSession: seek did not land on target for $url@$offsetNanos")
            }
            // Then let playback advance PAST the target so the vout renders the true target frame
            // (the reliable signal that new fragments arrived and decoded). Near the end of the
            // video the settle target must be clamped, otherwise get_time can never reach it and
            // every late-position scrub would fail. A timeout here is best-effort, not fatal.
            val lengthMs = runCatching { lib.libvlc_media_player_get_length(player) }.getOrDefault(-1L)
            val settleMs = when {
                lengthMs > targetMs + RENDER_FRAMES_MS + 200L -> targetMs + RENDER_FRAMES_MS
                lengthMs > targetMs + 50L -> lengthMs - 50L
                else -> targetMs
            }
            if (settleMs > targetMs) {
                awaitPast(player, settleMs, 2_500L)
            }
            // PHASE 2 — the frame we ship must be the NEW position, not a stale frame flushed by the
            // vout during the seek settle (backward seeks are the worst: get_time jumps to target
            // instantly, so old higher-position frames that were already queued pass the gate and
            // would be cached at the wrong timestamp). Discard everything captured so far and only
            // accept a frame that displays AFTER we confirmed playback advanced past the settle
            // point — by then the vout queue has been flushed and the next display is a fresh decode
            // of the new position.
            grab.clearCaptured()
            val seekLatch = CountDownLatch(1)
            grab.onFrame = { if (seekLatch.count > 0) seekLatch.countDown() }
            if (!seekLatch.await(2_000L, TimeUnit.MILLISECONDS)) {
                // Never serve a stale (wrong-position) frame: return null so the cache keeps showing
                // the nearest valid thumbnail and the next hover retries.
                logger.warn("ScrubSession: frame timeout for $url@$offsetNanos")
                runCatching { lib.libvlc_media_player_set_pause(player, 1) }
                grab.acceptAfterMs = -1L
                grab.timeProvider = null
                return null
            }
            // Pause the player so it stops consuming resources while we encode the frame.
            runCatching { lib.libvlc_media_player_set_pause(player, 1) }
            grab.acceptAfterMs = -1L
            grab.timeProvider = null

            val frame = grab.consumeFrame()
                ?: run { logger.warn("ScrubSession: no frame data for $url@$offsetNanos"); return null }

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
        }

        /**
         * Stops and releases the native player; the session cannot be reused after this. On Android
         * the player is only PAUSED, never stopped or released: this libvlc-all AAR's
         * `libvlc_media_player_stop` force-tears input/vout/aout so VLC worker threads detach while
         * exiting and their TLS destructor (`jni_detach_thread` in modules/video_output/android/
         * utils.c) dereferences freed state -> SIGSEGV in pthread_key_clean_all (libvlc.so+0xef7418),
         * even when the player is never released. Pausing keeps every VLC thread alive, so the
         * destructor never runs with stale state, and the OS reclaims the player on process exit.
         */
        @Synchronized
        fun close() {
            val player = mp
            opened.set(false)
            grab.deactivate()
            grab.timeProvider = null
            grab.onFrame = {}
            grab.firstFrameLatch.countDown()
            if (player != null && com.dreamdisplayx.util.OsInfo.isAndroid) {
                // Android never stops/releases a VLC-Android player. Keep the complete Session,
                // callback trampolines and aligned plane owners rooted until process exit.
                runCatching { lib.libvlc_media_player_set_pause(player, 1) }
                synchronized(ANDROID_KEEP_ALIVE) {
                    if (!ANDROID_KEEP_ALIVE.contains(this)) ANDROID_KEEP_ALIVE += this
                }
            } else {
                player?.let {
                    runCatching { lib.libvlc_media_player_stop(it) }
                    runCatching { lib.libvlc_media_player_release(it) }
                }
            }
            mp = null
        }

        /**
         * Polls [libvlc_media_player_is_seekable] until true or a bounded timeout, so a subsequent
         * [libvlc_media_player_set_time] is not silently ignored.
         */
        private fun pollSeekable(mp: Pointer) {
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

        /** Polls [libvlc_media_player_get_state] until it reports Paused (bounded, never fails). */
        private fun awaitPaused(mp: Pointer) {
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

        /** Polls [libvlc_media_player_get_time] until it is at least [minMs] with NO tolerance. */
        private fun awaitPast(mp: Pointer, minMs: Long, timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                try {
                    val t = lib.libvlc_media_player_get_time(mp)
                    if (t >= minMs) return true
                } catch (_: Throwable) {
                    return false
                }
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return false
        }
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
        val cleanupCb = LibVlc.VideoCleanupCallback { clearFormat() }
        val lockCb = LibVlc.VideoLockCallback { _opaque, planes -> lock(planes) }
        val unlockCb = LibVlc.VideoUnlockCallback { _, _, _ -> }
        val displayCb = LibVlc.VideoDisplayCallback { _opaque, picture -> display(picture) }

        @Volatile var frameW = 0
        @Volatile var frameH = 0
        private val yPlanes = arrayOfNulls<ByteBuffer>(POOL_SIZE)
        private val retiredPlanes = ArrayList<ByteBuffer>()
        private val inUse = BooleanArray(POOL_SIZE)
        private var nextBuffer = 0
        private var formatGeneration = 0L
        private var yPitch = 0
        private var uvPitch = 0
        private var yLines = 0
        private var uvLines = 0
        private var formatReady = false
        private var dropPlane: ByteBuffer? = null
        private var dropPointer: Pointer? = null
        @Volatile private var active = true
        @Volatile private var captured: ByteBuffer? = null

        val setupLatch = CountDownLatch(1)
        val firstFrameLatch = CountDownLatch(1)
        @Volatile var frameSeen = false
        @Volatile var onFrame: () -> Unit = {}

        /** Total display callbacks since the last [clearCaptured] (diagnostic). */
        @Volatile var displaysSinceClear = 0L

        /**
         * When >= 0, only a display whose reported position is at/after this time (ms) is accepted
         * into [captured] / [onFrame]. Set to the seek target before [libvlc_media_player_set_time]
         * so the latch cannot be satisfied by a stale PRE-seek (opening) frame.
         */
        @Volatile var acceptAfterMs = -1L

        /** Reports the current media position (ms) — used by [display] to gate [acceptAfterMs]. */
        @Volatile var timeProvider: (() -> Long)? = null

        @Synchronized
        private fun setup(chroma: Pointer?, width: Pointer?, height: Pointer?, pitches: Pointer?, lines: Pointer?): Int {
            if (!active || width == null || height == null || chroma == null || pitches == null || lines == null) return 0
            val w = width.getInt(0)
            val h = height.getInt(0)
            if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return 0
            frameW = w
            frameH = h
            yPitch = align32(w)
            uvPitch = align32((w + 1) / 2)
            yLines = align32(h)
            uvLines = align32((h + 1) / 2)
            // I420 chroma with 32-byte aligned pitches and plane addresses.
            val i420 = byteArrayOf('I'.code.toByte(), '4'.code.toByte(), '2'.code.toByte(), '0'.code.toByte())
            chroma.write(0, i420, 0, i420.size)
            pitches.setInt(0, yPitch)
            pitches.setInt(4, uvPitch)
            pitches.setInt(8, uvPitch)
            lines.setInt(0, yLines)
            lines.setInt(4, uvLines)
            lines.setInt(8, uvLines)
            for (old in yPlanes) old?.let { retiredPlanes += it }
            dropPlane?.let { retiredPlanes += it }
            formatGeneration = (formatGeneration + 1L).coerceAtLeast(1L)
            for (i in 0 until POOL_SIZE) {
                yPlanes[i] = alignedBuffer(bufferBytes())
                inUse[i] = false
            }
            dropPlane = alignedBuffer(bufferBytes())
            dropPointer = Native.getDirectBufferPointer(dropPlane!!)
            nextBuffer = 0
            formatReady = true
            setupLatch.countDown()
            return 1
        }

        @Synchronized
        private fun clearFormat() {
            formatReady = false
        }

        @Synchronized
        fun deactivate() {
            active = false
            formatReady = false
        }

        @Synchronized
        private fun lock(planes: Pointer?): Pointer? {
            if (planes == null) return LibVlc.dropToken()
            if (!active || !formatReady) {
                dropPointer?.let { setPlanes(planes, it) }
                return LibVlc.dropToken()
            }
            val index = (0 until POOL_SIZE)
                .map { (nextBuffer + it) % POOL_SIZE }
                .firstOrNull { !inUse[it] }
                ?: run {
                    dropPointer?.let { setPlanes(planes, it) }
                    return LibVlc.dropToken()
                }
            nextBuffer = (index + 1) % POOL_SIZE
            val buf = yPlanes[index] ?: run {
                dropPointer?.let { setPlanes(planes, it) }
                return LibVlc.dropToken()
            }
            val ptr = Native.getDirectBufferPointer(buf)
            setPlanes(planes, ptr)
            inUse[index] = true
            return Pointer.createConstant(formatGeneration * POOL_SIZE + index + 1L)
        }

        private fun setPlanes(planes: Pointer, ptr: Pointer) {
            val uOffset = yPitch.toLong() * yLines
            val vOffset = uOffset + uvPitch.toLong() * uvLines
            planes.setPointer(0, ptr)
            planes.setPointer(Native.POINTER_SIZE.toLong(), ptr.share(uOffset))
            planes.setPointer((2 * Native.POINTER_SIZE).toLong(), ptr.share(vOffset))
        }

        @Synchronized
        private fun display(picture: Pointer?) {
            if (picture == null) return
            val token = Pointer.nativeValue(picture)
            if (token <= 0L) return
            val encoded = token - 1L
            val generation = encoded / POOL_SIZE
            val index = (encoded % POOL_SIZE).toInt()
            if (generation != formatGeneration || index !in 0 until POOL_SIZE) return
            try {
                if (!active || !formatReady) return
                val w = frameW
                val h = frameH
                if (w <= 0 || h <= 0) return
                displaysSinceClear++
                val uvW = (w + 1) / 2
                val uvH = (h + 1) / 2
                val total = w * h + 2 * uvW * uvH
                val base = yPlanes[index] ?: return
                val source = base.duplicate().order(ByteOrder.nativeOrder())
                val copy = ByteBuffer.allocateDirect(total).order(ByteOrder.nativeOrder())
                copyRows(source, copy, 0, yPitch, w, h)
                val uOffset = yPitch * yLines
                copyRows(source, copy, uOffset, uvPitch, uvW, uvH)
                val vOffset = uOffset + uvPitch * uvLines
                copyRows(source, copy, vOffset, uvPitch, uvW, uvH)
                copy.flip()
                // Drop frames that are still before the seek target (a stale pre-seek picture queued in
                // the vout would otherwise satisfy the seek latch with the opening frame).
                val gate = acceptAfterMs
                if (gate >= 0) {
                    val now = timeProvider?.invoke() ?: gate
                    if (now < gate) return
                }
                captured = copy
                frameSeen = true
                onFrame()
                firstFrameLatch.countDown()
            } finally {
                inUse[index] = false
            }
        }

        @Synchronized
        fun consumeFrame(): ByteBuffer? {
            val f = captured ?: return null
            captured = null
            return f
        }

        /** Discards any frame captured so far (used right after a seek). */
        @Synchronized
        fun clearCaptured() {
            captured = null
            frameSeen = false
            displaysSinceClear = 0L
        }

        private fun bufferBytes(): Int = yPitch * yLines + 2 * uvPitch * uvLines + 31

        private fun alignedBuffer(size: Int): ByteBuffer {
            val owner = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            val raw = Native.getDirectBufferPointer(owner)
            val address = Pointer.nativeValue(raw)
            require(address != 0L) { "scrub vmem buffer has no native address" }
            val aligned = (address + 31L) and -32L
            val offset = (aligned - address).toInt()
            val view = owner.duplicate().order(ByteOrder.nativeOrder())
            view.position(offset).limit(offset + size - 31)
            val slice = view.slice().order(ByteOrder.nativeOrder())
            require(Pointer.nativeValue(Native.getDirectBufferPointer(slice)) % 32L == 0L)
            return slice
        }

        private fun copyRows(source: ByteBuffer, target: ByteBuffer, offset: Int, pitch: Int, rowBytes: Int, rows: Int) {
            for (row in 0 until rows) {
                source.clear().position(offset + row * pitch).limit(offset + row * pitch + rowBytes)
                target.put(source)
            }
        }

        private fun align32(value: Int): Int = (value + 31) and -32
    }

    /** Converts packed I420 buffer to a BufferedImage (RGB). */
    private fun i420ToBufferedImage(i420: ByteBuffer, w: Int, h: Int): BufferedImage? {
        if (w <= 0 || h <= 0) return null
        val ySize = w * h
        val uvSize = ((w + 1) / 2) * ((h + 1) / 2)
        val needed = ySize + 2 * uvSize
        // HARD SAFETY: the buffer may be a STALE capture from an earlier (smaller) format after a
        // seek changed the video size — reading it with the current w/h would index out of bounds
        // (BufferUnderflowException at best, a native access violation at worst, which crashed the
        // game with 0xC0000005 when a long video was opened). Refuse to convert instead of crashing.
        if (i420.capacity() < needed) {
            logger.warn("Scrub frame size mismatch: buffer={} needed={} for {}x{} — dropping stale frame.", i420.capacity(), needed, w, h)
            return null
        }
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
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
