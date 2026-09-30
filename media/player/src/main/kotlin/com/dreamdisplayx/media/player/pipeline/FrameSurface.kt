package com.dreamdisplayx.media.player.pipeline

import com.dreamdisplayx.api.media.model.FramePixelFormat
import com.dreamdisplayx.api.media.player.FrameUploader
import com.dreamdisplayx.api.media.player.FrameUploaderFactory
import com.dreamdisplayx.api.media.player.GpuTextureRef
import com.dreamdisplayx.media.player.MediaPlayer
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Render-facing half of a frame pipe, shared by the libvlc video pipe: the reusable direct-buffer
 * pool, the display-time frame queue and the GPU upload plumbing.
 */
internal class FrameSurface(
    private val debugLabel: String,
    uploaderFactory: FrameUploaderFactory,
    private val pixelFormat: FramePixelFormat = FramePixelFormat.RGB24,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/FrameSurface")

    /** Platform GPU upload sink for this channel; holds persistent upload state (e.g. a PBO ring). */
    private val uploader: FrameUploader = uploaderFactory.create()

    companion object {
        private const val MAX_REUSABLE_FRAME_BUFFERS = 4
        private const val MAX_DISPLAY_TOLERANCE_NS = 20_000_000L

        /** Presentation timestamp meaning "due at once" (publish without a media timestamp). */
        const val SHOW_NOW = Long.MIN_VALUE

        /** How many published-but-not-yet-drawn frames may be held before the oldest is recycled. */
        const val MAX_DISPLAY_QUEUE = 4
    }

    /** Pool retention cap; raised by the prebuffer so its in-flight buffers are reused, not churned. */
    @Volatile
    private var maxReusableBuffers = MAX_REUSABLE_FRAME_BUFFERS

    /** Raises the reusable-buffer pool size (used when a [FramePrebuffer] keeps many frames in flight). */
    fun setMaxReusableBuffers(n: Int) {
        maxReusableBuffers = n.coerceAtLeast(MAX_REUSABLE_FRAME_BUFFERS)
    }

    private class Queued(@JvmField val buf: ByteBuffer, @JvmField val pts: Long)

    private val displayQueue = ArrayDeque<Queued>()

    /** Render-thread clock used for display-time picking; null until [enableDisplaySync] is called. */
    @Volatile
    private var displayClock: (() -> Long)? = null

    @Volatile
    private var displayToleranceNs = 0L

    private val textureReady = AtomicBoolean(false)
    private val readyDrops = AtomicLong()
    private val reusableFrameBuffers = ConcurrentLinkedQueue<ByteBuffer>()

    /** O(1) mirror of [reusableFrameBuffers] size; [ConcurrentLinkedQueue.size] is a traversal. */
    private val reusableCount = AtomicInteger()

    private var uploadTotalNs = 0L
    private var uploadMinNs = Long.MAX_VALUE
    private var uploadMaxNs = 0L
    private var uploadCount = 0
    private var skippedUploads = 0L

    /** Returns true once a frame is available for upload or has already been uploaded to the GPU texture. */
    fun textureFilled(): Boolean = textureReady.get() || synchronized(displayQueue) { displayQueue.isNotEmpty() }

    /**
     * Switches the render thread to display-time frame selection: each draw shows the queued frame nearest to
     * [clock] (within half of [frameNs]) rather than the last one published. Decoupling the pick from the pacing
     * thread's wake-ups is what keeps the cadence even when video and game frame rates beat against each other.
     */
    fun enableDisplaySync(clock: () -> Long, frameNs: Long) {
        displayToleranceNs = (frameNs / 2).coerceIn(0L, MAX_DISPLAY_TOLERANCE_NS)
        displayClock = clock
    }

    /**
     * Drops frames queued for display but not yet shown, keeping the uploaded picture on screen. Called when a seek
     * flushes the pipe, so a pre-seek frame can't surface after it.
     */
    fun dropQueued() {
        synchronized(displayQueue) { drainQueueLocked(countDrops = false) }
    }

    /**
     * Uploads the ready frame to [target] if one is available. [actualW] / [actualH] must match [expectedW] / [expectedH]
     * or the frame is dropped.
     */
    fun updateFrame(target: GpuTextureRef, actualW: Int, actualH: Int, expectedW: Int, expectedH: Int): Boolean {
        val buf = takeDueFrame() ?: return false
        if (actualW != expectedW || actualH != expectedH || !uploader.canUpload()) {
            if (MediaPlayer.DEBUG) skippedUploads++
            recycleFrameBuffer(buf)
            return false
        }
        buf.rewind()
        val start = System.nanoTime()
        try {
            val uploaded = uploader.uploadInterleaved(target, buf, pixelFormat)
            if (uploaded) {
                textureReady.set(true)
                MediaPlayer.framesToGpu.incrementAndGet()
                if (MediaPlayer.DEBUG) recordUpload(System.nanoTime() - start, "Upload", actualW, actualH)
            }
            return uploaded
        } finally {
            recycleFrameBuffer(buf)
        }
    }

    /**
     * Uploads the ready I420 frame (Y, then U, then V planes) into the three plane textures.
     * [actualW] / [actualH] must match [expectedW] / [expectedH] like in [updateFrame].
     */
    fun updateFramePlanar(
        y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef,
        actualW: Int, actualH: Int, expectedW: Int, expectedH: Int,
    ): Boolean {
        val buf = takeDueFrame() ?: return false
        if (actualW != expectedW || actualH != expectedH || !uploader.canUpload()) {
            if (MediaPlayer.DEBUG) skippedUploads++
            recycleFrameBuffer(buf)
            return false
        }
        buf.rewind()
        val start = System.nanoTime()
        try {
            val uploaded = uploader.uploadPlanar(y, u, v, buf)
            if (uploaded) {
                textureReady.set(true)
                MediaPlayer.framesToGpu.incrementAndGet()
                if (MediaPlayer.DEBUG) recordUpload(System.nanoTime() - start, "Planar upload", actualW, actualH)
            }
            return uploaded
        } finally {
            recycleFrameBuffer(buf)
        }
    }

    /** Discards the current ready frame. Call when stopping or seeking. */
    fun clear() {
        textureReady.set(false)
        dropQueued()
        while (reusableFrameBuffers.poll() != null) reusableCount.decrementAndGet()
        readyDrops.set(0)
    }

    /**
     * Releases the GPU upload resources. Must be called from the render thread when this surface is
     * permanently discarded (i.e., the owning player is being stopped for good).
     */
    fun cleanup() {
        clear()
        uploader.cleanup()
    }

    /**
     * Hands [frame] (already paced by the caller) to the render thread and returns a fresh spare buffer
     * of at least [nextSize] bytes for the reader to fill next. [pts] is the frame's media timestamp in
     * content nanos, or [SHOW_NOW] when the producer has none to offer.
     */
    fun publish(frame: ByteBuffer, nextSize: Int, pts: Long = SHOW_NOW): ByteBuffer {
        enqueue(frame, pts)
        return takeReusableFrameBuffer(nextSize) ?: allocateFrameBuffer(nextSize)
    }

    /**
     * Consumer-side present (used by [FramePrebuffer]): queues [frame] for the render thread, due at [pts]
     * ([SHOW_NOW] for a preview). Unlike [publish] it returns no spare — the prebuffer's producer owns spares.
     */
    fun present(frame: ByteBuffer, pts: Long = SHOW_NOW) {
        enqueue(frame, pts)
    }

    /** Returns a pooled or freshly allocated direct buffer of at least [size] bytes. */
    fun takeOrAllocate(size: Int): ByteBuffer = takeReusableFrameBuffer(size) ?: allocateFrameBuffer(size)

    /** Allocate a new direct `ByteBuffer` of at least [size] bytes. The caller is responsible for recycling it when done. */
    fun allocateFrameBuffer(size: Int): ByteBuffer =
        ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())

    /**
     * Takes and returns a reusable frame buffer of at least [requiredSize] bytes, or null if none are available.
     * The caller is responsible for clearing and recycling the returned buffer when done.
     */
    fun takeReusableFrameBuffer(requiredSize: Int): ByteBuffer? {
        while (true) {
            val buffer = reusableFrameBuffers.poll() ?: return null
            reusableCount.decrementAndGet()
            if (buffer.capacity() >= requiredSize) {
                buffer.clear()
                return buffer
            }
        }
    }

    /**
     * Recycles [buffer] for future reuse. The buffer will be cleared before reuse, but the caller must ensure it's not
     * currently in use (e.g., by the render thread).
     */
    fun recycleFrameBuffer(buffer: ByteBuffer) {
        buffer.clear()
        if (reusableCount.incrementAndGet() <= maxReusableBuffers) {
            reusableFrameBuffers.offer(buffer)
        } else {
            reusableCount.decrementAndGet()
        }
    }

    /**
     * Picks the frame the render thread should show now: the newest queued frame that is already due, i.e. whose
     * timestamp is inside the tolerance window around the display clock. Frames that are still ahead of the window
     * stay queued; frames skipped over by a newer one are recycled and counted as drops.
     */
    private fun takeDueFrame(): ByteBuffer? {
        val clock = displayClock?.invoke() ?: -1L
        val horizon = if (clock >= 0L) clock + displayToleranceNs else Long.MAX_VALUE
        var chosen: ByteBuffer? = null
        synchronized(displayQueue) {
            while (true) {
                val head = displayQueue.firstOrNull() ?: break
                if (head.pts != SHOW_NOW && head.pts > horizon) break
                displayQueue.removeFirst()
                chosen?.let {
                    readyDrops.incrementAndGet()
                    MediaPlayer.framesDropped.incrementAndGet()
                    recycleFrameBuffer(it)
                }
                chosen = head.buf
            }
        }
        return chosen
    }

    private fun enqueue(frame: ByteBuffer, pts: Long) {
        synchronized(displayQueue) {
            val last = displayQueue.lastOrNull()
            if (last != null && (pts == SHOW_NOW || (last.pts != SHOW_NOW && pts < last.pts))) {
                drainQueueLocked(countDrops = true)
            }
            displayQueue.addLast(Queued(frame, pts))
            while (displayQueue.size > MAX_DISPLAY_QUEUE) {
                readyDrops.incrementAndGet()
                MediaPlayer.framesDropped.incrementAndGet()
                recycleFrameBuffer(displayQueue.removeFirst().buf)
            }
        }
    }

    private fun drainQueueLocked(countDrops: Boolean) {
        while (true) {
            val q = displayQueue.removeFirstOrNull() ?: break
            if (countDrops) {
                readyDrops.incrementAndGet()
                MediaPlayer.framesDropped.incrementAndGet()
            }
            recycleFrameBuffer(q.buf)
        }
    }

    private fun recordUpload(elapsedNs: Long, label: String, w: Int, h: Int) {
        uploadTotalNs += elapsedNs
        uploadMinNs = minOf(uploadMinNs, elapsedNs)
        uploadMaxNs = maxOf(uploadMaxNs, elapsedNs)
        if (++uploadCount >= 60) {
            val avgMs = uploadTotalNs / uploadCount / 1_000_000.0
            val minMs = uploadMinNs / 1_000_000.0
            val maxMs = uploadMaxNs / 1_000_000.0
            val drops = readyDrops.getAndSet(0)
            val skipped = skippedUploads
            logger.debug(
                "$debugLabel $label ${w}x$h avg=${"%.3f".format(avgMs)}ms " +
                        "min=${"%.3f".format(minMs)}ms max=${"%.3f".format(maxMs)}ms " +
                        "readyDrops=$drops skipped=$skipped pool=${reusableCount.get()}",
            )
            uploadTotalNs = 0L
            uploadMinNs = Long.MAX_VALUE
            uploadMaxNs = 0L
            uploadCount = 0
            skippedUploads = 0L
        }
    }
}
