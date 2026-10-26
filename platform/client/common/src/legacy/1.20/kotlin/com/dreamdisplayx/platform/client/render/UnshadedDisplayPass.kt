package com.dreamdisplayx.platform.client.render

import com.dreamdisplayx.platform.client.managers.ClientStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import org.joml.Matrix4f

/**
 * Repeats the display draw once the level pass - and with it a shader pack's whole composite chain -
 * is over, so the video itself is never graded by the pack.
 *
 * Displays are always drawn inside the level pass as well, right before translucent terrain: that is
 * what keeps them behind blocks and underneath water and glass. This pass only paints the untouched
 * picture back over the part of the quad that nothing translucent covers, so it is pointless without
 * a shader pack - there the level-pass draw already runs our own program - and is skipped.
 *
 * 1.20.1 difference: `RenderSystem.getModelViewStack()` returns a mutable [PoseStack] (1.21.1+
 * returns a bare `Matrix4f` with `pushMatrix`/`popMatrix`/`set`). So the captured model-view matrix
 * is replayed with `pushPose` + `mulPoseMatrix` + `popPose`, and `applyModelViewMatrix()` flushes it.
 */
object UnshadedDisplayPass {
    @Volatile
    private var tailHookAlive = false

    private var pendingCamera: Camera? = null

    /** Model-view matrix captured at display-draw time, replayed after the level pass. */
    private val pendingView = Matrix4f()

    /** Scratch matrix so [capture] can compose without mutating the caller's transform. */
    private val scratch = Matrix4f()

    private var pending = false

    /** Kept as a kill switch; every display always draws in the level pass regardless. */
    private val enabled: Boolean; get() = runCatching { ClientStateManager.config.displaysEnabled }.getOrDefault(true)

    val active: Boolean get() = tailHookAlive && enabled && ShaderPackCompat.isShaderPackActive

    fun capture(stack: PoseStack, camera: Camera) {
        if (!active) {
            pending = false
            return
        }
        pendingCamera = camera
        // 1.20.1: getModelViewStack() is a PoseStack, so its top entry is the current model-view matrix.
        scratch.set(RenderSystem.getModelViewStack().last().pose()).mul(stack.last().pose())
        pendingView.set(scratch)
        pending = true
    }

    fun drawPending() {
        tailHookAlive = true
        if (!pending) return
        pending = false
        val camera = pendingCamera ?: return
        if (Minecraft.getInstance().level == null) return

        val modelView = RenderSystem.getModelViewStack()
        modelView.pushPose()
        modelView.mulPoseMatrix(pendingView)
        RenderSystem.applyModelViewMatrix()
        try {
            ScreenRenderer.render(PoseStack(), camera, replay = true)
        } finally {
            modelView.popPose()
            RenderSystem.applyModelViewMatrix()
        }
    }
}