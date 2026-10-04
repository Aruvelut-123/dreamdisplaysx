package com.dreamdisplayx.media.player.util

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaBufferEffectsTest {
    @Test
    fun brightnessKeepsFilledBufferReadyForCallerFlip() {
        val buffer = ByteBuffer.allocate(8)
        buffer.put(byteArrayOf(10, 20, 30, 40, 250.toByte(), 200.toByte(), 100, 80))
        val position = buffer.position()
        val limit = buffer.limit()

        MediaBufferEffects.applyBrightnessRgba(buffer, 8, 2.0)

        assertEquals(position, buffer.position())
        assertEquals(limit, buffer.limit())
        assertEquals(20, buffer.get(0).toInt())
        assertEquals(40, buffer.get(1).toInt())
        assertEquals(60, buffer.get(2).toInt())
        assertEquals(40, buffer.get(3).toInt())
        assertEquals(255, buffer.get(4).toInt() and 0xFF)
        assertEquals(255, buffer.get(5).toInt() and 0xFF)
        assertEquals(200, buffer.get(6).toInt() and 0xFF)
        assertEquals(80, buffer.get(7).toInt() and 0xFF)
    }
}
