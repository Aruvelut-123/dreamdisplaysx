package com.dreamdisplayx.platform.client.render

import com.dreamdisplayx.api.display.geometry.ConformQuad
import com.dreamdisplayx.api.display.geometry.ConformVertex
import com.dreamdisplayx.api.display.geometry.clipToUvRect
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
import java.util.WeakHashMap
import kotlin.math.sin

/**
 * Draws a wrapped screen: the same picture as the flat quad, cut to the slab and stair faces.
 * The loading bar is clipped to the same surface so it follows the steps.
 */
internal object ConformingScreenDraw {
    private const val OVERLAY_LIFT = 0.002f

    private class Clip(val source: List<ConformQuad>, val rect: FloatArray, val pieces: List<ConformQuad>)

    /**
     * Last UV clip per screen. A conforming display draws its picture, its backdrop and its loading bar
     * every frame, and each of those clips the (unchanged) mesh to the same UV rectangle over and over,
     * so the pieces are memoized under a weak key and invalidated when the source mesh is rebuilt.
     */
    private val clips = WeakHashMap<DisplayScreen, Clip>()

    private fun clipped(
        screen: DisplayScreen, quads: List<ConformQuad>, u0: Float, v0: Float, u1: Float, v1: Float,
    ): List<ConformQuad> {
        val hit = clips[screen]
        if (hit != null && hit.source === quads && hit.rect[0] == u0 && hit.rect[1] == v0 &&
            hit.rect[2] == u1 && hit.rect[3] == v1
        ) return hit.pieces
        val pieces = quads.flatMap { clipToUvRect(it, u0, v0, u1, v1) }
        clips[screen] = Clip(quads, floatArrayOf(u0, v0, u1, v1), pieces)
        return pieces
    }

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
            // Match the flat-screen renderer: vertex colour applies brightness to both texture
            // formats, while the vout callback remains a bulk copy for smooth playback.
            val base = screen.brightness.coerceIn(0f, 1f) * 255f
            val color = (base * appear).toInt().coerceIn(0, 255)
            draw(drawQuad, screen.renderType!!, quads, clearance, color, color, color)
        } else {
            drawPlaceholder(screen, quads, clearance, drawQuad)
        }
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
        val band = clipped(screen, quads, 0.06f, trackV0, 0.94f, trackV1)
        val track = band.map { it.mapUv { _, _ -> 0f to 0f } }
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
        val accent = band.flatMap { clipToUvRect(it, sx0, trackV0, sx1, trackV1) }
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
