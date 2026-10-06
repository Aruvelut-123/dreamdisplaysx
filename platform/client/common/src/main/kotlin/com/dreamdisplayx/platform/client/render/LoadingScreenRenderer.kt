package com.dreamdisplayx.platform.client.render

import com.dreamdisplayx.api.display.geometry.ConformQuad
import com.dreamdisplayx.api.display.geometry.ConformVertex
import com.dreamdisplayx.api.display.geometry.clipToUvRect
import com.dreamdisplayx.platform.client.Initializer
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import com.dreamdisplayx.platform.client.render.LoadingScreenGeometry.CONTENT_LIFT
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation as Identifier*/
import java.util.WeakHashMap

/** Optional branded placeholder. Static textures and batched quads work without AWT on Android. */
internal object LoadingScreenRenderer {
    private typealias Draw = (RenderType, (PoseStack.Pose, VertexConsumer) -> Unit) -> Unit

    private class State {
        val clock = LoadingScreenClock()
        var source: List<ConformQuad>? = null
        var rotation = -1
        var physical: List<ConformQuad> = emptyList()
    }

    // Weak keys also release off-screen/deleted displays without introducing a new lifecycle owner.
    private val states = WeakHashMap<DisplayScreen, State>()
    private val iconType by lazy { texture("icon.png", "loading-icon") }
    private val titleType by lazy { texture("textures/gui/loading_wordmark.png", "loading-title") }
    private val brushType by lazy { texture("textures/gui/loading_dot.png", "loading-ring") }

    private fun texture(path: String, name: String): RenderType = DisplayUnlitRenderTypes.create(
        "dream-displays-$name", Identifier.fromNamespaceAndPath(Initializer.MOD_ID, path), translucent = true,
    )

    /** Called before the flat/curved split; the real first frame always wins over the intro. */
    fun frame(screen: DisplayScreen, replay: Boolean): LoadingScreenAnimation.Frame? {
        val enabled = ClientStateManager.config.animatedLoadingScreen
        val ready = screen.isVideoStarted && screen.hasTexture && screen.renderType != null
        if (!enabled || ready || screen.errored) {
            states.remove(screen)
            return null
        }
        val state = states.getOrPut(screen) { State() }
        val elapsed = state.clock.elapsed(enabled, ready, screen.errored, screen.mediaGeneration, System.nanoTime(), replay) ?: return null
        return LoadingScreenAnimation.frame(elapsed, screen.width, screen.height)
    }

    private data class Sprite(
        val rect: LoadingScreenAnimation.Rect,
        val alpha: Float = 1f,
        val u0: Float = 0f,
        val u1: Float = 1f,
        val leftRgb: Int = 0xFFFFFF,
        val rightRgb: Int = leftRgb,
    ) {
        fun rgb(t: Float): Int {
            if (leftRgb == rightRgb) return leftRgb
            fun channel(shift: Int): Int {
                val left = (leftRgb shr shift) and 255
                return (left + (((rightRgb shr shift) and 255) - left) * t.coerceIn(0f, 1f)).toInt()
            }
            return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }

    /** At most four draw calls per display, including the antialiased, round-ended spinner. */
    private fun layers(frame: LoadingScreenAnimation.Frame, draw: (RenderType, List<Sprite>, Boolean) -> Unit) {
        draw(DisplayYuvRenderTypes.solidColorType(), listOf(Sprite(
            LoadingScreenAnimation.Rect(0f, 0f, 1f, 1f), leftRgb = LoadingScreenAnimation.BACKGROUND_RGB,
        )), true)
        if (frame.iconAlpha > 0f) draw(iconType, listOf(Sprite(frame.icon, frame.iconAlpha)), false)
        if (frame.titleAlpha > 0f) draw(titleType, frame.glyphs.map {
            Sprite(it.rect, frame.titleAlpha, it.u0, it.u1, it.leftRgb, it.rightRgb)
        }, false)
        if (frame.spinnerAlpha > 0f) draw(brushType, frame.ringBrushes.map {
            Sprite(it, frame.spinnerAlpha, leftRgb = 0xDFE9FF)
        }, false)
    }

    fun renderFlat(screen: DisplayScreen, stack: PoseStack, frame: LoadingScreenAnimation.Frame, lift: Float, replay: Boolean, draw: Draw) {
        // The opaque replay backdrop must clear even the frontmost level-pass sprite before blending again.
        val baseLift = LoadingScreenGeometry.backgroundLift(lift, replay)
        layers(frame) { type, sprites, background ->
            stack.pushPose()
            try {
                DisplayGeometry.liftTowardViewer(stack, screen.facing, baseLift + if (background) 0f else CONTENT_LIFT)
                DisplayGeometry.applyScreenTransform(stack, screen.facing, screen.width, screen.height)
                draw(type) { pose, builder ->
                    for (sprite in sprites) {
                        val r = sprite.rect
                        // World unit-quad Y grows upward; the choreography and image UVs grow downward.
                        vertex(pose, builder, r.x, 1f - r.bottom, 0f, sprite.u0, 1f, sprite.leftRgb, sprite.alpha)
                        vertex(pose, builder, r.right, 1f - r.bottom, 0f, sprite.u1, 1f, sprite.rightRgb, sprite.alpha)
                        vertex(pose, builder, r.right, 1f - r.y, 0f, sprite.u1, 0f, sprite.rightRgb, sprite.alpha)
                        vertex(pose, builder, r.x, 1f - r.y, 0f, sprite.u0, 0f, sprite.leftRgb, sprite.alpha)
                    }
                }
            } finally {
                stack.popPose()
            }
        }
    }

    fun renderConforming(screen: DisplayScreen, quads: List<ConformQuad>, frame: LoadingScreenAnimation.Frame, lift: Float, replay: Boolean, draw: Draw) {
        if (quads.isEmpty()) return
        val state = states[screen] ?: return
        if (state.source !== quads || state.rotation != screen.rotation.quarterTurns) {
            state.source = quads
            state.rotation = screen.rotation.quarterTurns
            // Undo the video's UV rotation: like the legacy bar, branding stays viewer-upright.
            state.physical = quads.map { quad -> quad.mapUv { u, v ->
                LoadingScreenGeometry.uprightUv(u, v, state.rotation)
            } }
        }
        val baseLift = LoadingScreenGeometry.backgroundLift(lift, replay)
        val physical = state.physical // Capture this submission's mesh, not a later cache replacement.
        layers(frame) { type, sprites, background ->
            val clearance = DisplayGeometry.surfaceClearance() + baseLift + if (background) 0f else CONTENT_LIFT
            draw(type) { pose, builder ->
                for (sprite in sprites) {
                    val r = sprite.rect
                    for (surface in physical) {
                        // The full-surface backdrop needs no clipping or intermediate polygons.
                        if (background) {
                            emitProjected(pose, builder, surface.v0, sprite, clearance)
                            emitProjected(pose, builder, surface.v1, sprite, clearance)
                            emitProjected(pose, builder, surface.v2, sprite, clearance)
                            emitProjected(pose, builder, surface.v3, sprite, clearance)
                            continue
                        }
                        // Reject distant faces before the clipping helper allocates any polygon pieces.
                        if (outside(surface, r)) continue
                        for (piece in clipToUvRect(surface, r.x, r.y, r.right, r.bottom)) {
                            emitProjected(pose, builder, piece.v0, sprite, clearance)
                            emitProjected(pose, builder, piece.v1, sprite, clearance)
                            emitProjected(pose, builder, piece.v2, sprite, clearance)
                            emitProjected(pose, builder, piece.v3, sprite, clearance)
                        }
                    }
                }
            }
        }
    }

    private fun outside(q: ConformQuad, r: LoadingScreenAnimation.Rect): Boolean =
        maxOf(q.v0.u, q.v1.u, q.v2.u, q.v3.u) <= r.x || minOf(q.v0.u, q.v1.u, q.v2.u, q.v3.u) >= r.right ||
            maxOf(q.v0.v, q.v1.v, q.v2.v, q.v3.v) <= r.y || minOf(q.v0.v, q.v1.v, q.v2.v, q.v3.v) >= r.bottom

    private fun emitProjected(pose: PoseStack.Pose, builder: VertexConsumer, v: ConformVertex, sprite: Sprite, lift: Float) {
        val t = ((v.u - sprite.rect.x) / sprite.rect.width).coerceIn(0f, 1f)
        val uvY = ((v.v - sprite.rect.y) / sprite.rect.height).coerceIn(0f, 1f)
        vertex(pose, builder, v.x + v.ox * lift, v.y + v.oy * lift, v.z + v.oz * lift,
            sprite.u0 + (sprite.u1 - sprite.u0) * t, uvY, sprite.rgb(t), sprite.alpha)
    }

    private fun vertex(pose: PoseStack.Pose, builder: VertexConsumer, x: Float, y: Float, z: Float, u: Float, v: Float, rgb: Int, alpha: Float) {
        builder.addVertex(pose, x, y, z).setUv(u, v).setColor(
            (rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255, (alpha.coerceIn(0f, 1f) * 255f).toInt(),
        )
    }
}
