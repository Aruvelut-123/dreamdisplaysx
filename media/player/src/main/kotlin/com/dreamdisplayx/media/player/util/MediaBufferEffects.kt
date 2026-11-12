package com.dreamdisplayx.media.player.util

import kotlin.math.abs
import java.nio.ByteBuffer

/** Media buffer effects for decoded audio and video data. */
object MediaBufferEffects {
    /**
     * Apply a brightness multiplier to an interleaved RGBA buffer in place.
     *
     * The method deliberately uses absolute reads/writes and never changes the buffer's
     * position or limit.  Video callbacks commonly fill a buffer and then flip it for the
     * next pipeline stage; changing its position here would turn the caller's subsequent
     * flip into an empty frame.
     */
    fun applyBrightnessRgba(buf: ByteBuffer, size: Int, brightness: Double) {
        val factor = brightness.coerceIn(0.0, 2.0)
        if (factor == 1.0) return
        val end = size.coerceIn(0, buf.limit())
        var i = 0
        while (i + 3 < end) {
            val r = ((buf.get(i).toInt() and 0xFF) * factor).toInt().coerceIn(0, 255)
            val g = ((buf.get(i + 1).toInt() and 0xFF) * factor).toInt().coerceIn(0, 255)
            val b = ((buf.get(i + 2).toInt() and 0xFF) * factor).toInt().coerceIn(0, 255)
            buf.put(i, r.toByte())
            buf.put(i + 1, g.toByte())
            buf.put(i + 2, b.toByte())
            i += 4
        }
    }

    /**
     * Apply volume in place to an interleaved S16LE buffer. `len` is the number of valid bytes (must be a multiple of 2).
     */
    fun applyVolumeS16LE(buf: ByteArray, len: Int, volume: Double) =
        applyVolumeS16LE(buf, 0, len, volume)

    /**
     * Apply volume in place to a window of an interleaved S16LE buffer.
     *
     * `offset`/`len` exist because a sink that is handed one large block may only
     * be able to accept part of it, so the gain has to be applied to exactly the
     * bytes that are about to be written — scaling the whole buffer up front
     * would apply the gain twice to whatever gets retried.
     *
     * @param offset first valid byte, must be even (an odd offset splits a sample)
     * @param len number of valid bytes from [offset], must be even
     */
    fun applyVolumeS16LE(buf: ByteArray, offset: Int, len: Int, volume: Double) {
        if (abs(volume - 1.0) < 1e-5) return
        if (offset < 0 || len <= 0) return
        // Clamp instead of trusting callers: an over-long window used to throw
        // ArrayIndexOutOfBounds from inside an audio callback, where the
        // exception was swallowed and the block silently dropped.
        val end = minOf(buf.size, offset + len).let { if ((it - offset) % 2 != 0) it - 1 else it }
        var i = offset
        while (i + 1 < end) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val s = (hi shl 8) or lo
            val scaled = (s * volume).toInt().coerceIn(-32768, 32767)
            buf[i] = (scaled and 0xFF).toByte()
            buf[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }
}
