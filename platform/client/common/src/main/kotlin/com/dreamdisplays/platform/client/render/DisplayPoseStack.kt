package com.dreamdisplays.platform.client.render

//? if <1.19.3 {
import com.dreamdisplays.platform.client.render.compat.translate
import com.dreamdisplays.platform.client.render.compat.mulPose
//?}
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.mojang.blaze3d.vertex.PoseStack
import org.joml.Quaternionf

/**
 * Pose-stack math for positioning a display quad in the world: facing-dependent translation,
 * rotation, and scaling.
 *
 * Extracted from [ScreenRenderer] so the renderer only sequences draws.
 */
internal object DisplayPoseStack {
    private const val SURFACE_OFFSET = 0.008f // TODO: should be removed in future
    private const val SHADER_SURFACE_OFFSET = 0.016f

    /** Surface offset in effect right now: widened while a shader pack owns the depth buffer. */
    fun surfaceClearance(): Float =
        if (ShaderPackCompat.isShaderPackActive) SHADER_SURFACE_OFFSET else SURFACE_OFFSET

    /**
     * Applies the full transform for a screen of [width] x [height] blocks facing [facing]:
     * surface offset, per-facing corner correction, rotation, and scale. The stack is expected to
     * already be translated to the screen's anchor block.
     */
    fun applyScreenTransform(stack: PoseStack, facing: DisplayFacing, width: Int, height: Int) {
        moveForward(stack, facing, surfaceClearance())

        when (facing) {
            DisplayFacing.NORTH -> {
                moveHorizontal(stack, DisplayFacing.NORTH, -width.toFloat())
                moveForward(stack, DisplayFacing.NORTH, 1f)
            }

            DisplayFacing.SOUTH -> {
                moveHorizontal(stack, DisplayFacing.SOUTH, 1f)
                moveForward(stack, DisplayFacing.SOUTH, 1f)
            }

            DisplayFacing.EAST -> {
                moveHorizontal(stack, DisplayFacing.EAST, -(width - 1).toFloat())
                moveForward(stack, DisplayFacing.EAST, 2f)
            }

            DisplayFacing.WEST -> {}

            DisplayFacing.UP -> {
                stack.translate(0f, 1f, 0f)
                stack.translate(0f, 0f, height.toFloat())
            }

            DisplayFacing.DOWN -> {}
        }

        fixRotation(stack, facing)
        stack.scale(width.toFloat(), height.toFloat(), 0f)
    }

    /** Multiplies [stack] by [rotation]; the method was renamed in 26.3. */
    fun rotate(stack: PoseStack, rotation: Quaternionf) {
        //? if >=26.3 {
        stack.rotate(rotation)
        //?} else
        /*stack.mulPose(rotation)*/
    }

    /**
     * Nudges [stack] [amount] blocks toward the viewer along [facing]'s outward normal. Call this before [applyScale],
     * so the lift isn't itself scaled.
     */
    fun liftTowardViewer(stack: PoseStack, facing: DisplayFacing, amount: Float) = moveForward(stack, facing, amount)

    private fun fixRotation(stack: PoseStack, facing: DisplayFacing) {
        val rotation: Quaternionf = when (facing) {
            DisplayFacing.NORTH -> Quaternionf().rotationY(Math.toRadians(180.0).toFloat()).also {
                stack.translate(0f, 0f, 1f)
            }

            DisplayFacing.WEST -> Quaternionf().rotationY(Math.toRadians(-90.0).toFloat()).also {
                stack.translate(0f, 0f, 0f)
            }

            DisplayFacing.EAST -> Quaternionf().rotationY(Math.toRadians(90.0).toFloat()).also {
                stack.translate(-1f, 0f, 1f)
            }

            DisplayFacing.SOUTH -> Quaternionf().also { stack.translate(-1f, 0f, 0f) }

            DisplayFacing.UP -> Quaternionf().rotationX(Math.toRadians(-90.0).toFloat())

            DisplayFacing.DOWN -> Quaternionf().rotationX(Math.toRadians(90.0).toFloat())
        }
        rotate(stack, rotation)
    }

    private fun moveForward(stack: PoseStack, facing: DisplayFacing, amount: Float) {
        when (facing) {
            DisplayFacing.NORTH -> stack.translate(0f, 0f, -amount)
            DisplayFacing.WEST -> stack.translate(-amount, 0f, 0f)
            DisplayFacing.EAST -> stack.translate(amount, 0f, 0f)
            DisplayFacing.SOUTH -> stack.translate(0f, 0f, amount)
            DisplayFacing.UP -> stack.translate(0f, amount, 0f)
            DisplayFacing.DOWN -> stack.translate(0f, -amount, 0f)
        }
    }

    private fun moveHorizontal(stack: PoseStack, facing: DisplayFacing, amount: Float) {
        when (facing) {
            DisplayFacing.NORTH -> stack.translate(-amount, 0f, 0f)
            DisplayFacing.WEST -> stack.translate(0f, 0f, amount)
            DisplayFacing.EAST -> stack.translate(0f, 0f, -amount)
            DisplayFacing.SOUTH -> stack.translate(amount, 0f, 0f)
            DisplayFacing.UP, DisplayFacing.DOWN -> {}
        }
    }
}
