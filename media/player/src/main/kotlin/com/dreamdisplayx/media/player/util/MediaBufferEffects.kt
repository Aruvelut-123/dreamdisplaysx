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
    fun applyVolumeS16LE(buf: ByteArray, len: Int, volume: Double) {
        if (abs(volume - 1.0) < 1e-5) return
        var i = 0
        while (i + 1 < len) {
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
