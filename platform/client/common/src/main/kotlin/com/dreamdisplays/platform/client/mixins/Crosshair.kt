package com.dreamdisplays.platform.client.mixins

import com.dreamdisplays.core.runtime.getOrNull
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.overlay.CrosshairPolicy
//? if >=1.21.1 {
import net.minecraft.client.DeltaTracker
//?}
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor

//?} else
/*
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics
//?}
//? if <1.20 {
import com.dreamdisplays.platform.client.ui.compat.GuiGraphics
//?}
*/
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/** Mixin that suppresses crosshair rendering while the player is looking at a display screen. */
@Suppress("NonJavaMixin")
@Pseudo
//? if >=26.2 {
@Mixin(targets = ["net.minecraft.client.gui.Hud"])
//?} else
/*@Mixin(targets = ["net.minecraft.client.gui.Gui"])*/
open class Crosshair {
    /** Cancels crosshair extraction when the player is targeting a display surface. */
    //? if >=26 {
    @Inject(method = ["extractCrosshair"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractCrosshair(
        guiGraphics: GuiGraphicsExtractor,
        deltaTracker: DeltaTracker,
        ci: CallbackInfo
    ) {
        //?} else
        /*@Inject(method = ["renderCrosshair"], at = [At("HEAD")], cancellable = true, require = 0)
        open fun extractCrosshair(
            //? if >=1.20 {
            guiGraphics: GuiGraphics,
            //?}
            //? if <1.20 {
            poseStack: com.mojang.blaze3d.vertex.PoseStack,
            //?}
            //? if >=1.21.1 {
            deltaTracker: DeltaTracker,
            //?}
            ci: CallbackInfo
        ) {*/
        val suppress = DreamServices.registry.getOrNull<CrosshairPolicy>()
            ?.shouldSuppressCrosshair()
            ?: ClientStateManager.isOnScreen
        if (suppress) {
            ci.cancel()
        }
    }
}
