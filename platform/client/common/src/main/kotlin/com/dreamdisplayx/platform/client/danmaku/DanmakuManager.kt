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

    /** Rewinds the timed index for [displayId] to just before [positionSec]. Used after a seek or replay. */
    fun rewindTimed(displayId: DisplayId, positionSec: Double) {
        subscribers[displayId]?.rewindTo(positionSec)
    }

    /** Returns the current danmaku queue for [displayId], or null if none. */
    fun queue(displayId: DisplayId): List<DanmakuMessage>? = subscribers[displayId]?.messages

    /**
     * Consumes timed (VOD / bangumi) danmaku whose timestamp has been reached by playback position
     * [positionSec]; returns the newly due entries. No-op for live channels.
     */
    fun consumeTimed(displayId: DisplayId, positionSec: Double): List<DanmakuMessage> =
        subscribers[displayId]?.consumeTimed(positionSec) ?: emptyList()

    /** Drains and clears the live danmaku queue for [displayId]; empty when no subscription exists. */
    fun drainLive(displayId: DisplayId): List<DanmakuMessage> = synchronized(subscribers) {
        subscribers[displayId]?.drainMessages() ?: emptyList()
    }

    /** Returns the last danmaku entry time (seconds) for [displayId], or null. */
    fun lastEntryTimeSec(displayId: DisplayId): Double? =
        subscribers[displayId]?.lastEntryTimeSec()

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

        /** Max danmaku to release around a seek point when rewinding or fast-forwarding. */
        private companion object {
            const val RENDER_WINDOW = 120
        }

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
            logger.info("Danmaku switchToVideo display={} cid={}", displayId.uuid, cid)
            Thread {
                val started = System.currentTimeMillis()
                val entries = BilibiliApi.fetchDanmaku(cid)
                val elapsed = System.currentTimeMillis() - started
                synchronized(this) {
                    timedEntries = entries
                    timedIndex = 0
                    status = if (entries.isEmpty()) "暂无弹幕" else "已加载 ${entries.size} 条弹幕"
                }
                logger.info(
                    "Danmaku video loaded display={} cid={} count={} elapsedMs={}",
                    displayId.uuid, cid, entries.size, elapsed,
                )
            }.apply { isDaemon = true }.start()
        }

        /** Rewinds [timedIndex] so [consumeTimed] will re-emit danmaku around [positionSec]. */
        fun rewindTo(positionSec: Double) {
            synchronized(this) {
                if (!isVideo || timedEntries.isEmpty()) return
                var lo = 0
                var hi = timedEntries.size
                while (lo < hi) {
                    val mid = (lo + hi) / 2
                    if (timedEntries[mid].timeSec <= positionSec) {
                        lo = mid + 1
                    } else {
                        hi = mid
                    }
                }
                val target = lo
                timedIndex = Math.max(0, target - RENDER_WINDOW)
            }
        }

        /** Returns the last danmaku entry time in seconds, or null if empty. */
        fun lastEntryTimeSec(): Double? = timedEntries.lastOrNull()?.timeSec

        /**
         * Consumes timed danmaku whose timestamp has been reached by playback position [positionSec].
         *
         * Handles both forward playback and seeking:
         * - On forward playback, returns entries strictly after the previously consumed position up to
         *   [positionSec], so danmaku stream out naturally frame by frame.
         * - On a backward seek, rewinds and returns the bounded window around the seek point.
         * - On a forward seek past many entries, rewinds greedily and returns only a bounded window so
         *   the screen isn't flooded with a huge burst.
         */
        fun consumeTimed(positionSec: Double): List<DanmakuMessage> {
            if (!isVideo || timedEntries.isEmpty()) {
                return emptyList()
            }
            synchronized(this) {
                if (timedIndex < 0 || timedEntries.isEmpty()) {
                    timedIndex = 0
                }
                // Find the index of the first entry whose time is strictly greater than positionSec
                // (i.e., the upper bound for timeSec <= positionSec).
                var lo = 0
                var hi = timedEntries.size
                while (lo < hi) {
                    val mid = (lo + hi) / 2
                    if (timedEntries[mid].timeSec <= positionSec) {
                        lo = mid + 1
                    } else {
                        hi = mid
                    }
                }
                val target = lo
                val windowStart = Math.max(0, target - RENDER_WINDOW)
                if (timedIndex > target) {
                    // Backward seek: rewind and re-render the window around the seek point.
                    timedIndex = windowStart
                } else if (timedIndex < windowStart) {
                    // Forward seek past a gap: jump to the window start to avoid flooding.
                    timedIndex = windowStart
                }
                val due = ArrayList<DanmakuMessage>()
                while (timedIndex < target) {
                    val entry = timedEntries.getOrNull(timedIndex) ?: break
                    timedIndex++
                    due += DanmakuMessage(
                        id = DanmakuMessage.nextId(),
                        displayId = displayId,
                        text = entry.text,
                        sender = "",
                        color = entry.color,
                        mode = entry.mode,
                    )
                }
                if (due.isNotEmpty()) {
                    logger.debug(
                        "Danmaku consumed display={} at={}s due={} total={} idx={}",
                        displayId.uuid, "%.1f".format(positionSec), due.size, timedEntries.size, timedIndex,
                    )
                }
                return due
            }
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

        /** Drains and clears the live message queue; called from [DanmakuManager.drainLive]. */
        fun drainMessages(): List<DanmakuMessage> {
            val msgs = messages.toList()
            messages.clear()
            return msgs
        }
    }
}