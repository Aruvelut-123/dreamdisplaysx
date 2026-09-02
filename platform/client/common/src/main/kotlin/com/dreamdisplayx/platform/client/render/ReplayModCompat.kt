package com.dreamdisplayx.platform.client.render

import org.slf4j.LoggerFactory

/** Optional ReplayMod bridge. Reflection keeps ReplayMod out of the hard dependency graph. */
object ReplayModCompat {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ReplayModCompat")
    private val replayClassNames = listOf(
        "com.replaymod.replay.ReplayModReplay",
        "com.replaymod.replay.ReplayHandler",
    )

    /** Current replay timeline in milliseconds, or null when ReplayMod is not playing a replay. */
    fun currentTimelineMs(): Long? = runCatching {
        val module = Class.forName(replayClassNames[0])
        val instance = module.getField("instance").get(null) ?: return null
        val handler = module.getMethod("getReplayHandler").invoke(instance) ?: return null
        val sender = handler.javaClass.getMethod("getReplaySender").invoke(handler) ?: return null
        sender.javaClass.getMethod("currentTimeStamp").invoke(sender).let { (it as Number).toLong() }
    }.onFailure { error ->
        if (error !is ClassNotFoundException) logger.debug("ReplayMod timeline probe unavailable: {}", error.message)
    }.getOrNull()

    /** True when ReplayMod is present and a replay handler is currently active. */
    val isReplayActive: Boolean get() = currentTimelineMs() != null

    /** True when the active replay timeline is paused. */
    val isReplayPaused: Boolean
        get() = runCatching {
            val module = Class.forName(replayClassNames[0])
            val instance = module.getField("instance").get(null) ?: return false
            val handler = module.getMethod("getReplayHandler").invoke(instance) ?: return false
            val sender = handler.javaClass.getMethod("getReplaySender").invoke(handler) ?: return false
            sender.javaClass.getMethod("paused").invoke(sender) as? Boolean ?: false
        }.getOrDefault(false)

    /** True when ReplayMod is installed, regardless of whether a replay is playing. */
    val isInstalled: Boolean
        get() = replayClassNames.any { name -> runCatching { Class.forName(name); true }.getOrDefault(false) }
}
