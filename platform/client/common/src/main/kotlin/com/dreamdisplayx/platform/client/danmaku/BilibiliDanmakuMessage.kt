package com.dreamdisplayx.platform.client.danmaku

import com.dreamdisplayx.api.display.model.property.DisplayId
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * One Bilibili live-room danmaku (bullet comment) message.
 */
data class DanmakuMessage(
    val id: Long,
    val displayId: DisplayId,
    val text: String,
    val sender: String,
    val color: Int = 0xFFFFFF,
    val timestamp: Instant = Instant.now(),
) {
    companion object {
        private val SEQ = AtomicLong(0)
        fun nextId(): Long = SEQ.incrementAndGet()
    }
}