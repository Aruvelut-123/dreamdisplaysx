package com.dreamdisplayx.platform.client.debug

import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.util.GeneralUtil
import org.bytedeco.ffmpeg.global.avutil
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects runtime debug information for the F3 debug overlay.
 * Thread-safe; called from the render thread (or any thread via [getLines]).
 */
object DebugStats {
    /** Cached FFmpeg version string, read once. */
    val ffmpegVersion: String by lazy {
        runCatching { avutil.av_version_info()?.getString()?.trim() ?: "unknown" }
            .getOrDefault("unknown")
    }

    /** Cached mod version. */
    val modVersion: String by lazy { GeneralUtil.getPrettyModVersion() }

    /** Whether a NeoForge-native entry is already registered (to avoid double-adding in mixin). */
    val neoForgeEntryRegistered = AtomicBoolean(false)

    /**
     * Returns the current debug lines to display on the F3 overlay.
     * Safe to call from any thread.
     */
    fun getLines(): List<String> = buildList {
        add("Dream DisplaysX $modVersion")
        add("FFmpeg: $ffmpegVersion")
        val screenCount = runCatching { DisplayRegistry.getScreens().size }.getOrDefault(0)
        if (screenCount > 0) {
            val gpu = MediaPlayer.framesToGpu.get()
            val dropped = MediaPlayer.framesDropped.get()
            add("Displays: $screenCount active")
            add("Frames: $gpu GPU, $dropped dropped")
        }
    }
}