package com.dreamdisplayx.platform.client.mixins

import com.dreamdisplayx.platform.client.debug.DebugStats
import net.minecraft.client.gui.GuiGraphics
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Injects Dream DisplaysX debug info into the F3 debug screen.
 *
 * Targets [DebugScreenOverlay.renderLines] which exists in both 1.21.1 and 1.21.11
 * and accepts a mutable [List] that we can append to. The vanilla renderer will
 * then draw our lines at the correct position automatically.
 *
 * On 26.x this mixin class is still loaded (required by the shared mixin config)
 * but the injected method does not exist there, so it silently becomes a no-op;
 * 26.x uses the native [DebugScreenEntry] path instead.
 */
@Suppress("NonJavaMixin")
@Mixin(targets = ["net.minecraft.client.gui.components.DebugScreenOverlay"])
open class DebugScreenDebugInfo {
    @Inject(method = ["renderLines"], at = [At("HEAD")], require = 0)
    open fun onRenderLines(guiGraphics: GuiGraphics, lines: MutableList<String>, scaled: Boolean, ci: CallbackInfo) {
        // Skip when a NeoForge-native entry already handles it (1.21.11 NeoForge only)
        if (DebugStats.neoForgeEntryRegistered.get()) return
        lines.addAll(DebugStats.getLines())
    }
}