package com.dreamdisplayx.platform.client.danmaku

import com.dreamdisplayx.api.display.model.property.DisplayId
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Global singleton managing Bilibili live-room danmaku subscriptions.
 *
 * Each subscribed [DisplayId] has its own bounded message queue. The render pass
 * consumes messages from the head and frees them after a short lifetime.
 */
object DanmakuManager {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/DanmakuManager")
    private val subscribers = mutableMapOf<DisplayId, Channel>()

    /** Starts a danmaku subscription for a live display identified by [displayId] and [roomId]. */
    fun subscribe(displayId: DisplayId, roomId: Long) {
        subscribers[displayId]?.let { return }
        val channel = Channel(displayId)
        channel.start(roomId)
        synchronized(subscribers) { subscribers[displayId] = channel }
        logger.info("Danmaku subscribed display={} room={}", displayId.uuid, roomId)
    }

    /** Stops danmaku for [displayId]. */
    fun unsubscribe(displayId: DisplayId) {
        synchronized(subscribers) { subscribers.remove(displayId) }?.stop()
    }

    /** Returns the current danmaku queue for [displayId], or null if none. */
    fun queue(displayId: DisplayId): List<DanmakuMessage>? = subscribers[displayId]?.messages

    /** Returns live status text for [displayId], or null. */
    fun status(displayId: DisplayId): String? = subscribers[displayId]?.status

    /** Call when a display is removed. */
    fun onDisplayRemoved(displayId: DisplayId) {
        unsubscribe(displayId)
    }

    /** Shut down every channel (client shutdown). */
    fun shutdown() {
        synchronized(subscribers) {
            subscribers.values.forEach { it.stop() }
            subscribers.clear()
        }
    }

    private class Channel(val displayId: DisplayId) {
        val messages: MutableList<DanmakuMessage> = CopyOnWriteArrayList()
        @Volatile
        var status: String = ""
        private val client = AtomicReference<BilibiliDanmakuClient?>(
            BilibiliDanmakuClient(
                roomId = 0,
                onMessage = { text, sender, color -> enqueue(text, sender, color) },
                onStatusChanged = { status = it },
            )
        )
        private val maxMessages = 80

        fun start(roomId: Long) {
            val next = BilibiliDanmakuClient(
                roomId = roomId,
                onMessage = { text, sender, color -> enqueue(text, sender, color) },
                onStatusChanged = { status = it },
            )
            val prev = client.getAndSet(next)
            prev?.disconnect()
            next.connect()
        }

        fun stop() {
            client.getAndSet(null)?.disconnect()
        }

        private fun enqueue(text: String, sender: String, color: Int) {
            val msg = DanmakuMessage(
                id = DanmakuMessage.nextId(),
                displayId = displayId,
                text = text,
                sender = sender,
                color = color,
            )
            messages += msg
            if (messages.size > maxMessages) messages.removeAt(0)
        }
    }
}