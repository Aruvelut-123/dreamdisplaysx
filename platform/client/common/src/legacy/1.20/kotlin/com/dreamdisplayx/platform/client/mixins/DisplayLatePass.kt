package com.dreamdisplayx.platform.client.mixins

import com.dreamdisplayx.platform.client.render.UnshadedDisplayPass
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.GameRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Draws the displays once the level pass is over, past every shader-pack program and composite
 * stage, while the main render target still carries the world depth buffer. See [UnshadedDisplayPass].
 *
 * 1.20.1 difference: `GameRenderer.renderLevel` still takes `(float partialTicks, long finishTimeNano,
 * PoseStack poseStack)`. 1.21.1 switched it to `DeltaTracker` and 1.21.11 kept that shape, so the
 * shared file's `(float)` / `(DeltaTracker)` handlers both miss here and Mixin rejects the injection
 * with `Invalid descriptor ... Expected (FJLnet/minecraft/class_4587;...)V but found (F...)V`.
 * The hook itself is unchanged: it only needs to fire after the level pass and never reads the args.
 */
@Suppress("NonJavaMixin")
@Mixin(GameRenderer::class)
open class DisplayLatePass {
    @Inject(
        method = ["renderLevel"],
        at = [At(value = "CONSTANT", args = ["stringValue=hand"])],
        require = 0,
    )
    open fun onLevelRendered(partialTicks: Float, finishTimeNano: Long, poseStack: PoseStack, ci: CallbackInfo) {
        UnshadedDisplayPass.drawPending()
    }
}