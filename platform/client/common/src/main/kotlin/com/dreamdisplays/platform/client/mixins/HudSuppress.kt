package com.dreamdisplays.platform.client.mixins

import com.dreamdisplays.platform.client.ui.FullscreenOverlayManager
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
//? if <1.21.1 {
import org.spongepowered.asm.mixin.injection.Redirect
//?}
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/** Mixin that cancels individual vanilla HUD pieces. */
@Suppress("NonJavaMixin")
@Pseudo
//? if >=26.2 {
@Mixin(targets = ["net.minecraft.client.gui.Hud"])
//?} else
/*@Mixin(targets = ["net.minecraft.client.gui.Gui"])*/
open class HudSuppress {
    //? if >=26 {
    @Inject(method = ["extractHotbarAndDecorations"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractHotbar(g: GuiGraphicsExtractor, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["extractPlayerHealth"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractHealth(g: GuiGraphicsExtractor, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["extractChat"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractChat(g: GuiGraphicsExtractor, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["extractScoreboardSidebar"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractScoreboard(g: GuiGraphicsExtractor, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["extractTabList"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractTabList(g: GuiGraphicsExtractor, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["extractBossOverlay"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractBossOverlay(g: GuiGraphicsExtractor, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)
    //?} else
    /*
    //? if >=1.21.1 {
    @Inject(method = ["renderHotbarAndDecorations"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractHotbar(g: GuiGraphics, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["renderPlayerHealth"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractHealth(g: GuiGraphics, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["renderChat"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractChat(g: GuiGraphics, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["renderScoreboardSidebar"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractScoreboard(g: GuiGraphics, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["renderTabList"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractTabList(g: GuiGraphics, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["renderBossOverlay"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractBossOverlay(g: GuiGraphics, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)
    //?}
    //? if <1.21.1 {
    @Inject(
        method = [
            "renderHotbar", "renderExperienceBar", "renderJumpMeter", "renderSelectedItemName",
            "renderVehicleHealth", "renderPlayerHealth", "displayScoreboardSidebar",
        ],
        at = [At("HEAD")], cancellable = true, require = 0,
    )
    open fun extractHotbarAndDecorations(ci: CallbackInfo) = suppress(ci)
    //?}
    //? if >=1.20 <1.21.1 {
    @Redirect(
        method = ["render"],
        at = At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lnet/minecraft/client/gui/GuiGraphics;III)V"),
        require = 0,
    )
    open fun extractChat(chat: net.minecraft.client.gui.components.ChatComponent, g: GuiGraphics, tick: Int, mouseX: Int, mouseY: Int) {
        if (!FullscreenOverlayManager.shouldHideVanillaHud) chat.render(g, tick, mouseX, mouseY)
    }

    @Redirect(
        method = ["render"],
        at = At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;render(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V",
        ),
        require = 0,
    )
    open fun extractTabList(
        tabList: net.minecraft.client.gui.components.PlayerTabOverlay, g: GuiGraphics, width: Int,
        scoreboard: net.minecraft.world.scores.Scoreboard, objective: net.minecraft.world.scores.Objective?,
    ) {
        if (!FullscreenOverlayManager.shouldHideVanillaHud) tabList.render(g, width, scoreboard, objective)
    }

    @Redirect(
        method = ["render"],
        at = At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;render(Lnet/minecraft/client/gui/GuiGraphics;)V"),
        require = 0,
    )
    open fun extractBossOverlay(bossOverlay: net.minecraft.client.gui.components.BossHealthOverlay, g: GuiGraphics) {
        if (!FullscreenOverlayManager.shouldHideVanillaHud) bossOverlay.render(g)
    }
    //?}
    //? if <1.20 {
    @Redirect(
        method = ["render"],
        //? if >=1.19 {
        at = At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lcom/mojang/blaze3d/vertex/PoseStack;III)V"),
        //?}
        //? if <1.19 {
        at = At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V"),
        //?}
        require = 0,
    )
    //? if >=1.19 {
    open fun extractChat(
        chat: net.minecraft.client.gui.components.ChatComponent, pose: com.mojang.blaze3d.vertex.PoseStack,
        tick: Int, mouseX: Int, mouseY: Int,
    ) {
        if (!FullscreenOverlayManager.shouldHideVanillaHud) chat.render(pose, tick, mouseX, mouseY)
    }
    //?}
    //? if <1.19 {
    open fun extractChat(chat: net.minecraft.client.gui.components.ChatComponent, pose: com.mojang.blaze3d.vertex.PoseStack, tick: Int) {
        if (!FullscreenOverlayManager.shouldHideVanillaHud) chat.render(pose, tick)
    }
    //?}

    @Redirect(
        method = ["render"],
        at = At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;render(Lcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V",
        ),
        require = 0,
    )
    open fun extractTabList(
        tabList: net.minecraft.client.gui.components.PlayerTabOverlay, pose: com.mojang.blaze3d.vertex.PoseStack, width: Int,
        scoreboard: net.minecraft.world.scores.Scoreboard, objective: net.minecraft.world.scores.Objective?,
    ) {
        if (!FullscreenOverlayManager.shouldHideVanillaHud) tabList.render(pose, width, scoreboard, objective)
    }

    @Redirect(
        method = ["render"],
        at = At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;render(Lcom/mojang/blaze3d/vertex/PoseStack;)V"),
        require = 0,
    )
    open fun extractBossOverlay(
        bossOverlay: net.minecraft.client.gui.components.BossHealthOverlay, pose: com.mojang.blaze3d.vertex.PoseStack,
    ) {
        if (!FullscreenOverlayManager.shouldHideVanillaHud) bossOverlay.render(pose)
    }
    //?}
    */

    private fun suppress(ci: CallbackInfo) {
        if (FullscreenOverlayManager.shouldHideVanillaHud) ci.cancel()
    }
}
