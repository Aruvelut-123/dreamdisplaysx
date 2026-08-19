package com.dreamdisplayx.platform.server.cast

import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Rolling buffer for one screen-sharing cast: keeps the most recent encoded bytes so a viewer that
 * joins mid-cast still gets something to start from, and streams them out to HTTP readers until
 * the cast closes.
 *
 * The reader is a single stream: [copyTo] consumes chunks as it writes them, so this is a
 * one-viewer relay. Multiple concurrent viewers would race on the shared cursor — fine for the
 * MVP, and a per-reader cursor is the natural follow-up.
 */
class CastBuffer(
    val castId: String,
    val width: Int,
    val height: Int,
) {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private var totalBytes = 0L
    private var closed = false
    private var nextSequence = 0

    /** Max bytes retained: roughly 8 seconds of a 1.5 Mbps cast. */
    private val maxBytes = 1_500_000L

    /** Appends one ordered chunk, dropping stale duplicates and trimming the oldest on overflow. */
    fun append(sequence: Int, payload: ByteArray) {
        if (payload.isEmpty()) return
        lock.withLock {
            // A retried or out-of-order chunk is dropped so the buffer stays ordered.
            if (sequence < nextSequence) return
            nextSequence = sequence + 1
            chunks.addLast(payload)
            totalBytes += payload.size
            while (chunks.size > 1 && totalBytes > maxBytes) {
                totalBytes -= chunks.removeFirst().size
            }
            cond.signalAll()
        }
    }

    /** Marks the cast finished; [copyTo] drains what is left and returns. */
    fun close() {
        lock.withLock {
            closed = true
            cond.signalAll()
        }
    }

    /**
     * Streams buffered + incoming bytes to [out] until the cast closes. Blocks while the writer has
     * no new data. The write happens under the buffer lock: acceptable while viewers are few.
     */
    fun copyTo(out: OutputStream) {
        lock.withLock {
            while (true) {
                while (chunks.isNotEmpty()) {
                    val chunk = chunks.removeFirst()
                    totalBytes -= chunk.size
                    out.write(chunk)
                }
                if (closed) return
                cond.await()
            }
        }
    }
}
