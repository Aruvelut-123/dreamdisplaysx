package com.dreamdisplayx.platform.client.render

import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.UUID

/** Optional Flashback bridge. Reflection keeps Flashback and Sinytra Connector optional. */
object FlashbackCompat {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/FlashbackCompat")
    private const val FLASHBACK = "com.moulberry.flashback.Flashback"
    private const val MARKER = "dreamdisplayx"

    private fun type(): Class<*>? = runCatching { Class.forName(FLASHBACK) }.getOrNull()
    private fun static(name: String): Any? = runCatching {
        val type = type() ?: return null
        type.getMethod(name).invoke(null)
    }.getOrNull()

    val isInstalled: Boolean get() = type() != null
    val isReplayActive: Boolean get() = (static("isInReplay") as? Boolean) == true
    val isExporting: Boolean get() = (static("isExporting") as? Boolean) == true

    /** Flashback exposes a native visual timeline at 20 ticks per second. */
    val supportsVisualTimeline: Boolean get() = isReplayActive || isExporting

    /** Optional render switches for Flashback sessions; defaults preserve normal rendering. */
    val renderDisplays: Boolean
        get() = System.getProperty("dreamdisplayx.flashback.renderDisplays")?.toBooleanStrictOrNull() ?: true
    val renderHud: Boolean
        get() = System.getProperty("dreamdisplayx.flashback.renderHud")?.toBooleanStrictOrNull() ?: true

    /** Returns the current Flashback replay tick, retaining fractional export ticks. */
    fun currentReplayTick(): Double? {
        if (!supportsVisualTimeline) return null
        val export = type()?.getField("EXPORT_JOB")?.get(null)
        if (export != null) return runCatching {
            (export.javaClass.getMethod("getCurrentTickDouble").invoke(export) as Number).toDouble()
        }.getOrNull()
        val server = static("getReplayServer")
        return if (server != null) runCatching {
            (server.javaClass.getMethod("getPartialReplayTick").invoke(server) as Number).toDouble()
        }.getOrNull() else null
    }

    fun currentTimelineMs(): Long? {
        if (!isReplayActive && !isExporting) return null
        // ExportJob owns the authoritative fractional tick during offline rendering. Flashback's
        // getVisualMillis() falls back to wall-clock time when no replay server exists, which would
        // reintroduce export-speed drift for displays.
        currentReplayTick()?.let { return (it * 50.0).toLong().coerceAtLeast(0L) }
        return (static("getVisualMillis") as? Number)?.toLong()
    }

    /** Flashback's replay server exposes no stable paused accessor; a zero desired tick rate is frozen. */
    val isReplayPaused: Boolean
        get() {
            if (!isReplayActive) return false
            val server = static("getReplayServer") ?: return true
            return runCatching {
                val rate = server.javaClass.getMethod("getDesiredTickRate", Boolean::class.javaPrimitiveType)
                    .invoke(server, false) as Number
                rate.toDouble() <= 0.0
            }.getOrDefault(true)
        }

    fun recordAction(action: String, displayId: UUID, payload: String): Boolean {
        if (!isInstalled || isReplayActive || isExporting) return false
        return runCatching {
            val recorder = type()?.getField("RECORDER")?.get(null) ?: return false
            val ready = recorder.javaClass.getMethod("readyToWrite").invoke(recorder) as? Boolean ?: false
            if (!ready) return false
            val description = "$MARKER:$action:$displayId:${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))}"
            val markerType = Class.forName("com.moulberry.flashback.record.ReplayMarker")
            val marker = markerType.constructors.firstOrNull { it.parameterTypes.size == 3 }
                ?.newInstance(0x55AAFF, null, description) ?: return false
            recorder.javaClass.getMethod("addMarker", markerType).invoke(recorder, marker)
            true
        }.onFailure { error -> logger.debug("Flashback marker recording unavailable: {}", error.message) }.getOrDefault(false)
    }

    fun consumeActions(lastTimeMs: Long, currentTimeMs: Long, callback: (String, UUID, String) -> Unit) {
        if (!isReplayActive || currentTimeMs < lastTimeMs) return
        runCatching {
            val server = static("getReplayServer") ?: return
            val metadata = server.javaClass.getMethod("getMetadata").invoke(server) ?: return
            val markers = metadata.javaClass.getField("replayMarkers").get(metadata) as? Map<*, *> ?: return
            for ((key, marker) in markers) {
                val time = (key as? Number)?.toLong()?.times(50L) ?: continue
                if (time <= lastTimeMs || time > currentTimeMs) continue
                val description = marker?.javaClass?.getMethod("description")?.invoke(marker) as? String ?: continue
                val parts = description.split(':', limit = 4)
                if (parts.size != 4 || parts[0] != MARKER) continue
                val payload = String(Base64.getUrlDecoder().decode(parts[3]), Charsets.UTF_8)
                callback(parts[1], UUID.fromString(parts[2]), payload)
            }
        }.onFailure { error -> logger.debug("Flashback marker playback unavailable: {}", error.message) }
    }
}
