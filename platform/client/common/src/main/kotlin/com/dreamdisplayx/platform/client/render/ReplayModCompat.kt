package com.dreamdisplayx.platform.client.render

import org.slf4j.LoggerFactory

/** Optional ReplayMod bridge. Reflection keeps ReplayMod out of the hard dependency graph. */
object ReplayModCompat {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ReplayModCompat")
    private val replayClassNames = listOf(
        "com.replaymod.replay.ReplayModReplay",
        "com.replaymod.replay.ReplayHandler",
    )

    /** True when ReplayMod is present and a replay handler is currently active. */
    val isReplayActive: Boolean
        get() = runCatching {
            val module = Class.forName(replayClassNames[0], false, javaClass.classLoader)
            val instance = module.getField("instance").get(null) ?: return false
            module.getMethod("getReplayHandler").invoke(instance) != null
        }.onFailure { error ->
            if (error !is ClassNotFoundException) logger.debug("ReplayMod state probe unavailable: {}", error.message)
        }.getOrDefault(false)

    /** True when ReplayMod is installed, regardless of whether a replay is playing. */
    val isInstalled: Boolean
        get() = replayClassNames.any { name -> runCatching { Class.forName(name); true }.getOrDefault(false) }
}
