package com.dreamdisplayx.platform.client.debug

//? if >=26 {
import com.dreamdisplayx.util.GeneralUtil
import net.minecraft.client.gui.components.debug.DebugEntryCategory
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer
import net.minecraft.client.gui.components.debug.DebugScreenEntries
import net.minecraft.client.gui.components.debug.DebugScreenEntry
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import org.bytedeco.ffmpeg.global.avutil

/**
 * 26.x native [DebugScreenEntry] that displays Dream DisplaysX runtime info
 * directly in the F3 debug overlay, using the official Minecraft debug entry API.
 */
object DreamDisplaysDebugEntry : DebugScreenEntry {
    private val ffmpegVersion: String by lazy {
        runCatching { avutil.av_version_info()?.getString()?.trim() ?: "unknown" }.getOrDefault("unknown")
    }

    private val modVersion: String by lazy { GeneralUtil.getPrettyModVersion() }

    override fun display(
        displayer: DebugScreenDisplayer,
        level: Level?,
        clientChunk: LevelChunk?,
        serverChunk: LevelChunk?,
    ) {
        displayer.addLine("§bDream DisplaysX §a$modVersion")
        displayer.addLine("§7FFmpeg: §f$ffmpegVersion")
        val screenCount = runCatching {
            com.dreamdisplayx.platform.client.displays.DisplayRegistry.getScreens().size
        }.getOrDefault(0)
        if (screenCount > 0) {
            val gpu = com.dreamdisplayx.media.player.MediaPlayer.framesToGpu.get()
            val dropped = com.dreamdisplayx.media.player.MediaPlayer.framesDropped.get()
            displayer.addLine("Displays: $screenCount active")
            displayer.addLine("Frames: $gpu GPU, $dropped dropped")
        }
    }

    override fun isAllowed(reducedDebug: Boolean): Boolean = true

    override fun category(): DebugEntryCategory = DebugEntryCategory.SCREEN_TEXT
}

/**
 * Registers [DreamDisplaysDebugEntry] into the F3 debug screen's entry map.
 * Safe to call from mod init (Minecraft classes are resolvable).
 */
object DebugScreenEntryRegistrar {
    private val DEBUG_ENTRY_ID = Identifier.fromNamespaceAndPath("dreamdisplayx", "debug")

    fun register() {
        val field = DebugScreenEntries::class.java.getDeclaredField("ENTRIES_BY_ID")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(null) as MutableMap<Identifier, DebugScreenEntry>
        map[DEBUG_ENTRY_ID] = DreamDisplaysDebugEntry
    }
}
//?}