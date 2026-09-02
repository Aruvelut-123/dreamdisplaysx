package com.dreamdisplayx.platform.client.render

import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.Base64

/** Optional ReplayMod bridge. Reflection keeps ReplayMod out of the hard dependency graph. */
object ReplayModCompat {
    @Volatile
    private var renderingFrame = false

    /** True while Dream DisplaysX is rendering a ReplayMod video frame. */
    val isReplayRendering: Boolean get() = renderingFrame

    fun beginRenderFrame() { renderingFrame = true }
    fun endRenderFrame() { renderingFrame = false }

    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ReplayModCompat")
    private val replayClassNames = listOf(
        "com.replaymod.replay.ReplayModReplay",
        "com.replaymod.replay.ReplayHandler",
    )

    /** True when ReplayMod or Flashback owns the current replay timeline. */
    val isReplayActive: Boolean
        get() = FlashbackCompat.isReplayActive || currentTimelineMs() != null

    /** Current replay timeline in milliseconds, preferring Flashback when it is active. */
    fun currentTimelineMs(): Long? {
        FlashbackCompat.currentTimelineMs()?.let { return it }
        return runCatching {
            val module = Class.forName(replayClassNames[0])
            val instance = module.getField("instance").get(null) ?: return null
            val handler = module.getMethod("getReplayHandler").invoke(instance) ?: return null
            val sender = handler.javaClass.getMethod("getReplaySender").invoke(handler) ?: return null
            sender.javaClass.getMethod("currentTimeStamp").invoke(sender).let { (it as Number).toLong() }
        }.onFailure { error ->
            if (error !is ClassNotFoundException) logger.debug("ReplayMod timeline probe unavailable: {}", error.message)
        }.getOrNull()
    }

    /** True when the active replay timeline is paused. */
    val isReplayPaused: Boolean
        get() {
            if (FlashbackCompat.isReplayActive) return FlashbackCompat.isReplayPaused
            return runCatching {
                val module = Class.forName(replayClassNames[0])
                val instance = module.getField("instance").get(null) ?: return false
                val handler = module.getMethod("getReplayHandler").invoke(instance) ?: return false
                val sender = handler.javaClass.getMethod("getReplaySender").invoke(handler) ?: return false
                sender.javaClass.getMethod("paused").invoke(sender) as? Boolean ?: false
            }.getOrDefault(false)
        }

    /** True when ReplayMod is installed, regardless of whether a replay is playing. */
    val isInstalled: Boolean
        get() = replayClassNames.any { name -> runCatching { Class.forName(name); true }.getOrDefault(false) }

    /** Adds a persistent marker to the active replay recorder, preferring Flashback. */
    fun recordAction(action: String, displayId: UUID, value: Long = 0L) {
        recordActionPayload(action, displayId, value.toString())
    }

    /** Adds an action marker with a URL-safe textual payload. */
    fun recordActionPayload(action: String, displayId: UUID, payload: String) {
        if (FlashbackCompat.recordAction(action, displayId, payload)) return
        runCatching {
            val recording = Class.forName("com.replaymod.recording.ReplayModRecording")
            val instance = recording.getField("instance").get(null) ?: return
            val connection = recording.getMethod("getConnectionEventHandler").invoke(instance) ?: return
            val listener = connection.javaClass.getMethod("getPacketListener").invoke(connection) ?: return
            val marker = "dreamdisplayx:$action:$displayId:${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))}"
            listener.javaClass.getMethod("addMarker", String::class.java).invoke(listener, marker)
        }.onFailure { error ->
            if (error !is ClassNotFoundException) logger.debug("ReplayMod action marker unavailable: {}", error.message)
        }
    }

    /** Replays custom action markers whose timestamps have just been crossed by the replay cursor. */
    fun consumeActions(lastTimeMs: Long, currentTimeMs: Long, callback: (String, UUID, String) -> Unit) {
        if (FlashbackCompat.isReplayActive) {
            FlashbackCompat.consumeActions(lastTimeMs, currentTimeMs, callback)
            return
        }
        if (currentTimeMs < lastTimeMs) return
        runCatching {
            val module = Class.forName(replayClassNames[0])
            val instance = module.getField("instance").get(null)
            val handler = instance?.let { module.getMethod("getReplayHandler").invoke(it) }
            val file = handler?.let { handler.javaClass.getMethod("getReplayFile").invoke(it) }
            val markers = file?.let { file.javaClass.getMethod("getMarkers").invoke(it) }
            val values = markers?.let {
                it.javaClass.getMethod("or", java.util.function.Supplier::class.java)
                    .invoke(it, java.util.function.Supplier { emptySet<Any>() }) as? Set<*>
            } ?: emptySet<Any>()
            values.forEach { marker ->
                val name = marker?.let { it.javaClass.getMethod("getName").invoke(it) as? String } ?: return@forEach
                if (!name.startsWith("dreamdisplayx:")) return@forEach
                val time = (marker.javaClass.getMethod("getTime").invoke(marker) as Number).toLong()
                if (time <= lastTimeMs || time > currentTimeMs) return@forEach
                val parts = name.split(':')
                if (parts.size >= 4) runCatching {
                    val decoded = String(Base64.getUrlDecoder().decode(parts[3]), Charsets.UTF_8)
                    callback(parts[1], UUID.fromString(parts[2]), decoded)
                }
            }
        }.onFailure { error ->
            if (error !is ClassNotFoundException) logger.debug("ReplayMod action playback unavailable: {}", error.message)
        }
    }
}
