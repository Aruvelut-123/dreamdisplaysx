package com.dreamdisplayx.platform.client.danmaku

import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.media.source.bilibili.BilibiliApi
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Global singleton managing Bilibili danmaku subscriptions.
 *
 * Two data sources are supported:
 * - **Live rooms**: a WebSocket channel delivers real-time danmaku.
 * - **VOD / bangumi**: the full timed danmaku list is fetched once and consumed as playback advances.
 *
 * Each subscribed [DisplayId] has its own bounded message queue. The render pass consumes messages
 * from the head and frees them after a short lifetime.
 */
object DanmakuManager {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/DanmakuManager")
    private val subscribers = mutableMapOf<DisplayId, Channel>()

    /** Starts a live-room danmaku subscription for [displayId] and [roomId]. */
    fun subscribeLive(displayId: DisplayId, roomId: Long) {
        synchronized(subscribers) {
            val existing = subscribers[displayId]
            if (existing != null) {
                existing.switchToLive(roomId)
                return
            }
            val channel = Channel(displayId)
            subscribers[displayId] = channel
            channel.switchToLive(roomId)
        }
        logger.info("Danmaku live subscribed display={} room={}", displayId.uuid, roomId)
    }

    /**
     * Starts a VOD / bangumi danmaku subscription for [displayId]: fetches the timed danmaku list
     * for [cid] (async on an IO thread) and returns true when the list was queued.
     */
    fun subscribeVideo(displayId: DisplayId, cid: Long) {
        synchronized(subscribers) {
            val existing = subscribers[displayId]
            if (existing != null) {
                existing.switchToVideo(cid)
                return
            }
            val channel = Channel(displayId)
            subscribers[displayId] = channel
            channel.switchToVideo(cid)
        }
        logger.info("Danmaku video subscribed display={} cid={}", displayId.uuid, cid)
    }

    /** Stops danmaku for [displayId]. */
    fun unsubscribe(displayId: DisplayId) {
        synchronized(subscribers) { subscribers.remove(displayId) }?.stop()
    }

    /** Returns the current danmaku queue for [displayId], or null if none. */
    fun queue(displayId: DisplayId): List<DanmakuMessage>? = subscribers[displayId]?.messages

    /**
     * Consumes timed (VOD / bangumi) danmaku whose timestamp has been reached by playback position
     * [positionSec]; returns the newly due entries. No-op for live channels.
     */
    fun consumeTimed(displayId: DisplayId, positionSec: Double): List<DanmakuMessage> =
        subscribers[displayId]?.consumeTimed(positionSec) ?: emptyList()

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
        private val client = AtomicReference<BilibiliDanmakuClient?>(null)
        private val maxMessages = 80

        /** VOD / bangumi timed entries, ascending by [BilibiliApi.DanmakuEntry.timeSec]. */
        private var timedEntries: List<BilibiliApi.DanmakuEntry> = emptyList()
        private var timedIndex = 0
        private var isVideo = false

        fun switchToLive(roomId: Long) {
            isVideo = false
            timedEntries = emptyList()
            timedIndex = 0
            val next = BilibiliDanmakuClient(
                roomId = roomId,
                onMessage = { text, sender, color -> enqueue(text, sender, color) },
                onStatusChanged = { status = it },
            )
            val prev = client.getAndSet(next)
            prev?.disconnect()
            next.connect()
        }

        fun switchToVideo(cid: Long) {
            isVideo = true
            val prev = client.getAndSet(null)
            prev?.disconnect()
            status = "正在加载弹幕…"
            Thread {
                val entries = BilibiliApi.fetchDanmaku(cid)
                synchronized(this) {
                    timedEntries = entries
                    timedIndex = 0
                    status = if (entries.isEmpty()) "暂无弹幕" else "已加载 ${entries.size} 条弹幕"
                }
                logger.info("Danmaku video loaded display={} cid={} count={}", displayId.uuid, cid, entries.size)
            }.apply { isDaemon = true }.start()
        }

        /**
         * Consumes timed danmaku whose timestamp has been reached by playback position [positionSec].
         * Returns the newly due entries. Call on the client tick thread.
         */
        fun consumeTimed(positionSec: Double): List<DanmakuMessage> {
            if (!isVideo || timedEntries.isEmpty()) return emptyList()
            val due = ArrayList<DanmakuMessage>()
            synchronized(this) {
                while (timedIndex < timedEntries.size && timedEntries[timedIndex].timeSec <= positionSec) {
                    val entry = timedEntries[timedIndex]
                    timedIndex++
                    due += DanmakuMessage(
                        id = DanmakuMessage.nextId(),
                        displayId = displayId,
                        text = entry.text,
                        sender = "",
                        color = entry.color,
                    )
                }
            }
            due.forEach { enqueue(it) }
            return due
        }

        fun stop() {
            client.getAndSet(null)?.disconnect()
        }

        private fun enqueue(text: String, sender: String, color: Int) {
            enqueue(
                DanmakuMessage(
                    id = DanmakuMessage.nextId(),
                    displayId = displayId,
                    text = text,
                    sender = sender,
                    color = color,
                )
            )
        }

        private fun enqueue(msg: DanmakuMessage) {
            messages += msg
            if (messages.size > maxMessages) messages.removeAt(0)
        }
    }
}