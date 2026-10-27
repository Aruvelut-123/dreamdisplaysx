package com.dreamdisplayx.platform.client.mixins

import com.dreamdisplayx.platform.client.ui.FullscreenOverlayManager
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.scores.Objective
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Mixin that cancels individual vanilla HUD pieces.
 *
 * 1.20.1's `Gui` has a different HUD layout than 1.21+: there is no `DeltaTracker`, `renderHotbar`
 * takes `(float, GuiGraphics)` in that order, and chat / tab list / boss bar live in their own
 * component classes (`ChatComponent`, `PlayerTabOverlay`, `BossHealthOverlay`) rather than on `Gui`.
 * Only the pieces that actually exist on `Gui` are hooked here.
 */
@Suppress("NonJavaMixin")
@Pseudo
@Mixin(targets = ["net.minecraft.client.gui.Gui"])
open class HudSuppress {
    @Inject(method = ["renderHotbar"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun renderHotbar(partialTick: Float, g: GuiGraphics, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["renderPlayerHealth"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun renderPlayerHealth(g: GuiGraphics, ci: CallbackInfo) = suppress(ci)

    // 1.20.1 also passes the sidebar's `Objective` through: `Gui#displayScoreboardSidebar(GuiGraphics,
    // Objective)`. Without it Mixin rejects the injection with `Invalid descriptor ... Expected
    // (Lclass_332;Lclass_266;CallbackInfo)V but found (Lclass_332;CallbackInfo)V`.
    @Inject(method = ["displayScoreboardSidebar"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun displayScoreboardSidebar(g: GuiGraphics, objective: Objective, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["renderEffects"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun renderEffects(g: GuiGraphics, ci: CallbackInfo) = suppress(ci)

    private fun suppress(ci: CallbackInfo) {
        if (FullscreenOverlayManager.shouldHideVanillaHud) ci.cancel()
    }
}