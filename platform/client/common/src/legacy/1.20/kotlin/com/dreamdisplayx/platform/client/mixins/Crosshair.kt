package com.dreamdisplayx.platform.client.mixins

import com.dreamdisplayx.api.runtime.registry.service.getOrNull
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import com.dreamdisplayx.platform.client.overlay.CrosshairPolicy
import net.minecraft.client.gui.GuiGraphics
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Mixin that suppresses crosshair rendering while the player is looking at a display screen.
 *
 * 1.20.1's `Gui.renderCrosshair` takes only the `GuiGraphics` argument - the `DeltaTracker` parameter
 * was added later, so this legacy copy mirrors the older signature.
 */
@Suppress("NonJavaMixin")
@Pseudo
@Mixin(targets = ["net.minecraft.client.gui.Gui"])
open class Crosshair {
    /** Cancels crosshair extraction when the player is targeting a display surface. */
    @Inject(method = ["renderCrosshair"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractCrosshair(
        guiGraphics: GuiGraphics,
        ci: CallbackInfo,
    ) {
        val suppress = DreamServices.registry.getOrNull<CrosshairPolicy>()
            ?.shouldSuppressCrosshair()
            ?: ClientStateManager.isOnScreen
        if (suppress) {
            ci.cancel()
        }
    }
}
