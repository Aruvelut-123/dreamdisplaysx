package com.dreamdisplayx.platform.client.debug

import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.util.GeneralUtil
import net.minecraft.client.resources.language.I18n
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects runtime debug information for the F3 debug overlay.
 * Thread-safe; called from the render thread (or any thread via [getLines]).
 */
object DebugStats {
    /** Cached LibVLC version string, read once. */
    val libvlcVersion: String by lazy {
        runCatching {
            val f = uk.co.caprica.vlcj.factory.MediaPlayerFactory()
            try {
                f.application().version() ?: "unknown"
            } finally {
                f.release()
            }
        }.getOrDefault("unknown")
    }

    /** Cached mod version. */
    val modVersion: String by lazy { GeneralUtil.getPrettyModVersion() }

    /** Cached git commit id. */
    val commitId: String by lazy { I18n.get("dreamdisplayx.debug.commit") }

    /** Whether a NeoForge-native entry is already registered (to avoid double-adding in mixin). */
    val neoForgeEntryRegistered = AtomicBoolean(false)

    /**
     * Returns the current debug lines to display on the F3 overlay.
     * Safe to call from any thread.
     */
    fun getLines(): List<String> = buildList {
        add("§bDream DisplaysX §a$modVersion")
        add("§7Commit: §f$commitId")
        add("§7LibVLC: §f$libvlcVersion")
        val screenCount = runCatching { DisplayRegistry.getScreens().size }.getOrDefault(0)
        val gpu = MediaPlayer.framesToGpu.get()
        val dropped = MediaPlayer.framesDropped.get()
        val decoder = MediaPlayer.currentDecoder.get()
        add("Displays: $screenCount active")
        add("Frames: $gpu GPU, $dropped dropped")
        add("Decoder: $decoder")
    }
}