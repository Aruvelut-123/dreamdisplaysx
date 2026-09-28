package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.display.geometry.ConformQuad
import com.dreamdisplays.api.display.geometry.ConformVertex
import com.dreamdisplays.api.display.geometry.clipToUvRect
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
import kotlin.math.sin

/**
 * Draws a wrapped screen: the same picture as the flat quad, cut to the slab and stair faces.
 * Subtitles and the loading bar are clipped to the same surface so they follow the steps.
 */
internal object ConformingScreenDraw {
    private const val SUBTITLE_MAX_HEIGHT_FRAC = 0.16f
    private const val SUBTITLE_MAX_WIDTH_FRAC = 0.86f
    private const val SUBTITLE_BOTTOM_MARGIN_FRAC = 0.05f
    private const val OVERLAY_LIFT = 0.002f

    fun render(
        screen: DisplayScreen,
        quads: List<ConformQuad>,
        lift: Float,
        drawQuad: (RenderType, (PoseStack.Pose, VertexConsumer) -> Unit) -> Unit,
    ) {
        if (quads.isEmpty()) return
        val clearance = DisplayGeometry.surfaceClearance() + lift
        if (screen.isVideoStarted && screen.hasTexture && screen.renderType != null) {
            val appear = screen.appearProgress()
            val base = if (screen.isYuvTexture) screen.brightness.coerceIn(0f, 1f) * 255f else 255f
            val color = (base * appear).toInt().coerceIn(0, 255)
            draw(drawQuad, screen.renderType!!, quads, clearance, color, color, color)
            drawSubtitle(screen, quads, clearance, drawQuad)
        } else {
            drawPlaceholder(screen, quads, clearance, drawQuad)
        }
    }

    private fun drawSubtitle(
        screen: DisplayScreen,
        quads: List<ConformQuad>,
        clearance: Float,
        drawQuad: (RenderType, (PoseStack.Pose, VertexConsumer) -> Unit) -> Unit,
    ) {
        val overlay = screen.subtitleOverlayTexture()
        overlay.update(if (screen.subtitlesEnabled) screen.currentSubtitleText else null)
        val type = overlay.renderType() ?: return
        var unitH = SUBTITLE_MAX_HEIGHT_FRAC
        var unitW = unitH * (screen.height.toFloat() / screen.width.toFloat()) * overlay.aspectRatio
        if (unitW > SUBTITLE_MAX_WIDTH_FRAC) {
            unitW = SUBTITLE_MAX_WIDTH_FRAC
            unitH = unitW * (screen.width.toFloat() / screen.height.toFloat()) / overlay.aspectRatio
        }
        val u0 = 0.5f - unitW / 2f
        val u1 = 0.5f + unitW / 2f
        val texV1 = 1f - SUBTITLE_BOTTOM_MARGIN_FRAC
        val texV0 = 1f - (SUBTITLE_BOTTOM_MARGIN_FRAC + unitH)
        val pieces = quads.flatMap { clipToUvRect(it, u0, texV0, u1, texV1) }
            .map { quad ->
                quad.mapUv { u, v ->
                    val su = (u - u0) / (u1 - u0)
                    val sv = (v - texV0) / (texV1 - texV0)
                    su to sv
                }
            }
        draw(drawQuad, type, pieces, clearance + OVERLAY_LIFT, 255, 255, 255)
    }

    private fun drawPlaceholder(
        screen: DisplayScreen,
        quads: List<ConformQuad>,
        clearance: Float,
        drawQuad: (RenderType, (PoseStack.Pose, VertexConsumer) -> Unit) -> Unit,
    ) {
        val type = DisplayYuvRenderTypes.solidColorType()
        val (r, g, b) = if (screen.errored) {
            Triple(28, 6, 6)
        } else {
            val breathe = (sin(System.nanoTime() / 2_000_000_000.0 * 2.0 * Math.PI).toFloat() + 1f) * 0.5f
            val v = (8 + breathe * 6f).toInt()
            Triple(v, v, v)
        }
        draw(drawQuad, type, quads.map { it.mapUv { _, _ -> 0f to 0f } }, clearance, r, g, b)
        if (screen.errored) return

        val trackV0 = 1f - 0.075f
        val trackV1 = 1f - 0.045f
        val track = quads.flatMap { clipToUvRect(it, 0.06f, trackV0, 0.94f, trackV1) }
            .map { it.mapUv { _, _ -> 0f to 0f } }
        val (tr, tg, tb) = Triple(22, 24, 34)
        draw(drawQuad, type, track, clearance + OVERLAY_LIFT, tr, tg, tb)

        val period = 1_300_000_000L
        val phase = (System.nanoTime() % period).toFloat() / period
        val segW = 0.28f
        val x0 = 0.06f
        val x1 = 0.94f
        val travel = (x1 - x0) + segW
        val segStart = x0 - segW + travel * phase
        val sx0 = segStart.coerceIn(x0, x1)
        val sx1 = (segStart + segW).coerceIn(x0, x1)
        if (sx1 <= sx0) return
        val accent = quads.flatMap { clipToUvRect(it, sx0, trackV0, sx1, trackV1) }
            .map { it.mapUv { _, _ -> 0f to 0f } }
        draw(drawQuad, type, accent, clearance + OVERLAY_LIFT * 2f, 40, 110, 255)
    }

    private fun draw(
        drawQuad: (RenderType, (PoseStack.Pose, VertexConsumer) -> Unit) -> Unit,
        type: RenderType,
        quads: List<ConformQuad>,
        clearance: Float,
        r: Int,
        g: Int,
        b: Int,
    ) {
        if (quads.isEmpty()) return
        drawQuad(type) { pose, builder ->
            for (quad in quads) {
                emit(pose, builder, quad.v0, clearance, r, g, b)
                emit(pose, builder, quad.v1, clearance, r, g, b)
                emit(pose, builder, quad.v2, clearance, r, g, b)
                emit(pose, builder, quad.v3, clearance, r, g, b)
            }
        }
    }

    private fun emit(
        pose: PoseStack.Pose,
        builder: VertexConsumer,
        vertex: ConformVertex,
        clearance: Float,
        r: Int,
        g: Int,
        b: Int,
    ) {
        builder.addVertex(
            pose,
            vertex.x + vertex.ox * clearance,
            vertex.y + vertex.oy * clearance,
            vertex.z + vertex.oz * clearance,
        )
            .setUv(vertex.u, vertex.v)
            .setColor(r, g, b, 255)
    }
}
