package com.dreamdisplays.platform.client.render

//? if >=26 {
//?} else
/*import com.mojang.blaze3d.systems.RenderSystem*/
//? if >=26.2 {
//?} else
/*import com.mojang.blaze3d.vertex.Tesselator*/
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
import com.dreamdisplays.api.display.model.property.DisplayRotation
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.displays.PlacedDisplays
import org.joml.Quaternionf
import com.dreamdisplays.platform.client.render.ScreenRenderer.drawLayer
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
//? if <26.3 {
import com.mojang.blaze3d.vertex.VertexFormat
//?}
import net.minecraft.client.Camera
import kotlin.math.sin

/** Renders screens in the world. Better not to touch this shit. */
object ScreenRenderer {
    private typealias QuadAppender = (PoseStack.Pose, VertexConsumer) -> Unit
    private typealias QuadRenderer = (RenderType, QuadAppender) -> Unit

    /**
     * Iterates all registered screens and renders each one relative to [camera]. Pass `replay = true`
     * for [UnshadedDisplayPass]'s second draw of a frame already on screen.
     */
    fun render(stack: PoseStack, camera: Camera, replay: Boolean = false) {
        ImmediateRenderCompat.beginBatch()
        try {
            render(stack, camera, replay) { type, appendVertices ->
                drawImmediate(stack, type, appendVertices)
            }
        } finally {
            ImmediateRenderCompat.flushBatch()
        }
    }

    /** Iterates all registered screens and lets the caller submit quads through the active renderer. */
    fun render(stack: PoseStack, camera: Camera, replay: Boolean = false, drawQuad: QuadRenderer) {
        val cameraPos =
            //? if >=1.21.11 {
            camera.position()
        //?} else
        /*camera.getPosition()*/
        DisplayCulling.begin(camera, cameraPos)
        for (displayScreen in DisplayRegistry.getScreens()) {
            if (displayScreen.isDormant || !displayScreen.hasTexture) continue
            if (!DisplayCulling.isVisible(displayScreen)) {
                if (!replay) displayScreen.fitTextureWhileHidden()
                continue
            }

            stack.pushPose()

            val placement = PlacedDisplays.placementOf(displayScreen.uuid)
            if (placement != null) {
                val t = placement.transform(PlacedDisplays.partialTick())
                stack.translate(t.originX - cameraPos.x, t.originY - cameraPos.y, t.originZ - cameraPos.z)
                DisplayPoseStack.rotate(
                    stack, Quaternionf(t.rotationX.toFloat(), t.rotationY.toFloat(), t.rotationZ.toFloat(), t.rotationW.toFloat()),
                )
                stack.scale(t.scaleX.toFloat(), t.scaleY.toFloat(), t.scaleZ.toFloat())
            } else {
                val pos = displayScreen.pos
                stack.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z)
            }

            renderScreenTexture(displayScreen, stack, replay, drawQuad)

            stack.popPose()
        }

        ImmediateRenderCompat.flushBatch()
    }

    private const val REPLAY_LIFT = 0.01f

    private fun renderScreenTexture(
        displayScreen: DisplayScreen, stack: PoseStack, replay: Boolean, drawQuad: QuadRenderer,
    ) {
        if (!replay) displayScreen.fitTexture()

        if (displayScreen.conforming) {
            val wrapped = ConformingWorldMesh.quads(displayScreen)
            if (wrapped != null) {
                ConformingScreenDraw.render(displayScreen, wrapped, if (replay) REPLAY_LIFT else 0f, drawQuad)
                return
            }
        }

        val facing = displayScreen.facing
        val w = displayScreen.width
        val h = displayScreen.height
        val lift = if (replay) REPLAY_LIFT else 0f

        if (displayScreen.isVideoStarted && displayScreen.hasTexture && displayScreen.renderType != null) {
            drawLayer(stack, facing, w, h, lift) {
                renderGpuTexture(drawQuad, displayScreen)
            }
        } else {
            renderPlaceholder(
                stack,
                drawQuad,
                DisplayYuvRenderTypes.solidColorType(),
                facing,
                w,
                h,
                displayScreen.errored,
                lift,
                displayScreen.rotation,
            )
        }

        renderSubtitleOverlay(displayScreen, stack, facing, w, h, lift, drawQuad)
    }

    private const val SUBTITLE_MAX_HEIGHT_FRAC = 0.16f
    private const val SUBTITLE_MAX_WIDTH_FRAC = 0.86f
    private const val SUBTITLE_BOTTOM_MARGIN_FRAC = 0.05f

    private fun renderSubtitleOverlay(
        displayScreen: DisplayScreen, stack: PoseStack, facing: DisplayFacing, w: Int, h: Int, lift: Float, drawQuad: QuadRenderer,
    ) {
        val overlay = displayScreen.subtitleOverlayTexture()
        overlay.update(if (displayScreen.subtitlesEnabled) displayScreen.currentSubtitleText else null)
        val type = overlay.renderType() ?: return

        var unitH = SUBTITLE_MAX_HEIGHT_FRAC
        var unitW = unitH * (h.toFloat() / w.toFloat()) * overlay.aspectRatio
        if (unitW > SUBTITLE_MAX_WIDTH_FRAC) {
            unitW = SUBTITLE_MAX_WIDTH_FRAC
            unitH = unitW * (w.toFloat() / h.toFloat()) / overlay.aspectRatio
        }
        val x0 = 0.5f - unitW / 2f
        val x1 = 0.5f + unitW / 2f
        val y0 = SUBTITLE_BOTTOM_MARGIN_FRAC
        val y1 = y0 + unitH

        drawLayer(stack, facing, w, h, lift + OVERLAY_LIFT) {
            drawQuad(type) { pose, vb -> appendTexturedRect(pose, vb, x0, y0, x1, y1) }
        }
    }

    private fun appendTexturedRect(
        pose: PoseStack.Pose, builder: VertexConsumer, x0: Float, y0: Float, x1: Float, y1: Float,
    ) {
        addVertex(pose, builder, x0, y0, 0f, 255, 255, 255, 0f, 1f)
        addVertex(pose, builder, x1, y0, 0f, 255, 255, 255, 1f, 1f)
        addVertex(pose, builder, x1, y1, 0f, 255, 255, 255, 1f, 0f)
        addVertex(pose, builder, x0, y1, 0f, 255, 255, 255, 0f, 0f)
    }

    private fun renderGpuTexture(drawQuad: QuadRenderer, displayScreen: DisplayScreen) {
        val appear = displayScreen.appearProgress()
        val base = if (displayScreen.isYuvTexture) displayScreen.brightness.coerceIn(0f, 1f) * 255f else 255f
        val c = (base * appear).toInt().coerceIn(0, 255)
        drawQuad(displayScreen.renderType!!) { pose, builder ->
            appendQuad(pose, builder, c, c, c, displayScreen.rotation)
        }
    }

    private const val OVERLAY_LIFT = 0.01f

    private fun renderPlaceholder(
        stack: PoseStack, drawQuad: QuadRenderer, type: RenderType,
        facing: DisplayFacing, w: Int, h: Int, error: Boolean, lift: Float, rotation: DisplayRotation,
    ) {
        // Backdrop on the screen plane
        drawLayer(stack, facing, w, h, lift) {
            val (r, g, b) = if (error) {
                Triple(28, 6, 6)
            } else {
                val breathe = (sin(System.nanoTime() / 2_000_000_000.0 * 2.0 * Math.PI).toFloat() + 1f) * 0.5f
                val v = (8 + breathe * 6f).toInt()
                Triple(v, v, v)
            }
            drawQuad(type) { pose, vb -> appendRect(pose, vb, 0f, 0f, 1f, 1f, r, g, b) }
        }

        val y0 = 0.045f
        val y1 = 0.075f
        val x0 = 0.06f
        val x1 = 0.94f

        // Bar track, lifted off the backdrop.
        drawLayer(stack, facing, w, h, lift + OVERLAY_LIFT) {
            val (r, g, b) = if (error) Triple(120, 30, 30) else Triple(22, 24, 34)
            drawQuad(type) { pose, vb -> appendRect(pose, vb, x0, y0, x1, y1, r, g, b, rotation) }
        }
        if (error) return

        // Accent segment sweeping across the track, clipped at the ends so it grows in and shrinks out,
        // lifted again so it never fights the track.
        val period = 1_300_000_000L
        val phase = (System.nanoTime() % period).toFloat() / period
        val segW = 0.28f
        val travel = (x1 - x0) + segW
        val segStart = x0 - segW + travel * phase
        val sx0 = segStart.coerceIn(x0, x1)
        val sx1 = (segStart + segW).coerceIn(x0, x1)
        if (sx1 > sx0) drawLayer(stack, facing, w, h, lift + OVERLAY_LIFT * 2f) {
            drawQuad(type) { pose, vb -> appendRect(pose, vb, sx0, y0, sx1, y1, 40, 110, 255, rotation) }
        }
    }

    private inline fun drawLayer(
        stack: PoseStack, facing: DisplayFacing, w: Int, h: Int, lift: Float, body: () -> Unit,
    ) {
        stack.pushPose()
        if (lift != 0f) DisplayPoseStack.liftTowardViewer(stack, facing, lift)
        DisplayPoseStack.applyScreenTransform(stack, facing, w, h)
        body()
        stack.popPose()
    }

    private fun appendRect(
        pose: PoseStack.Pose, builder: VertexConsumer,
        x0: Float, y0: Float, x1: Float, y1: Float, r: Int, g: Int, b: Int,
        rotation: DisplayRotation = DisplayRotation.NONE,
    ) {
        val turns = rotation.quarterTurns % 4
        fun v(cx: Float, cy: Float) {
            var x = cx
            var y = cy
            repeat(turns) {
                val nx = y
                y = 1f - x
                x = nx
            }
            addVertex(pose, builder, x, y, 0f, r, g, b, 0f, 0f)
        }
        v(x0, y0)
        v(x1, y0)
        v(x1, y1)
        v(x0, y1)
    }

    private val baseU = floatArrayOf(0f, 1f, 1f, 0f)
    private val baseV = floatArrayOf(1f, 1f, 0f, 0f)

    private fun appendQuad(
        pose: PoseStack.Pose,
        builder: VertexConsumer,
        r: Int,
        g: Int,
        b: Int,
        rotation: DisplayRotation,
    ) {
        val rot = rotation.quarterTurns
        addVertex(pose, builder, 0f, 0f, 0f, r, g, b, baseU[rot % 4], baseV[rot % 4])
        addVertex(pose, builder, 1f, 0f, 0f, r, g, b, baseU[(1 + rot) % 4], baseV[(1 + rot) % 4])
        addVertex(pose, builder, 1f, 1f, 0f, r, g, b, baseU[(2 + rot) % 4], baseV[(2 + rot) % 4])
        addVertex(pose, builder, 0f, 1f, 0f, r, g, b, baseU[(3 + rot) % 4], baseV[(3 + rot) % 4])
    }

    private fun addVertex(
        pose: PoseStack.Pose,
        builder: VertexConsumer,
        x: Float,
        y: Float,
        z: Float,
        r: Int,
        g: Int,
        b: Int,
        u: Float,
        v: Float,
    ) {
        //? if >=1.21.1 {
        builder.addVertex(pose, x, y, z).setUv(u, v).setColor(r, g, b, 255)
        //?} else
        /*builder.vertex(pose.pose(), x, y, z).uv(u, v).color(r, g, b, 255).endVertex()*/
    }

    private fun drawImmediate(stack: PoseStack, type: RenderType, appendVertices: QuadAppender) {
        ImmediateRenderCompat.draw(stack, type, appendVertices)
    }

    private object ImmediateRenderCompat {
        fun beginBatch() {
            //? if >=26.2 {
            batching = true
            //?}
        }

        fun flushBatch() {
            //? if >=26.2 {
            flush262()
            //?}
        }

        fun draw(stack: PoseStack, type: RenderType, appendVertices: QuadAppender) {
            //? if >=26.2 {
            draw262(stack, type, appendVertices)
            //?} else
            /*run {
                //? if <1.21.11 {
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
                //?}
                try {
                    //? if >=1.21.1 {
                    val builder = Tesselator.getInstance().begin(type.mode(), type.format())
                    appendVertices(stack.last(), builder)
                    type.draw(builder.buildOrThrow())
                    //?}
                    //? if <1.21.1 {
                    val builder = Tesselator.getInstance().builder
                    builder.begin(type.mode(), type.format())
                    appendVertices(stack.last(), builder)
                    //? if >=1.20 {
                    type.end(builder, RenderSystem.getVertexSorting())
                    //?}
                    //? if <1.20 {
                    type.end(builder, 0, 0, 0)
                    //?}
                    //?}
                } finally {
                    //? if <1.21.11 {
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
                    //?}
                }
            }*/
        }

        //? if >=26.2 {
        private val stagedClass: Class<*> by lazy { Class.forName("net.minecraft.client.renderer.StagedVertexBuffer") }
        private val stagedCtor by lazy {
            stagedClass.getConstructor(java.util.function.Supplier::class.java, Int::class.javaPrimitiveType)
        }

        private val methodCache = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method>()

        private fun method(owner: Class<*>, name: String, vararg params: Class<*>): java.lang.reflect.Method =
            methodCache.computeIfAbsent("${owner.name}#$name") { owner.getMethod(name, *params) }

        private var staged: Any? = null
        private var batching = false
        private val batchTypes = ArrayList<RenderType>()
        private val batchDraws = ArrayList<Any>()

        private fun stagedBuffer(): Any =
            staged ?: stagedCtor.newInstance(java.util.function.Supplier { "dream-displays-immediate" }, 16_384)
                .also { staged = it }

        private fun draw262(stack: PoseStack, type: RenderType, appendVertices: QuadAppender) {
            val buffer = stagedBuffer()
            val primitiveTopology = method(type.javaClass, "primitiveTopology").invoke(type)
            val draw = method(stagedClass, "appendDraw", VertexFormat::class.java, primitiveTopology.javaClass)
                .invoke(buffer, type.format(), primitiveTopology)
            batchTypes += type
            batchDraws += draw
            val builder = method(stagedClass, "getVertexBuilder", draw.javaClass)
                .invoke(buffer, draw) as VertexConsumer
            appendVertices(stack.last(), builder)
            if (!batching) flush262()
        }

        private fun flush262() {
            batching = false
            val buffer = staged ?: return
            if (batchDraws.isEmpty()) return
            try {
                method(stagedClass, "upload").invoke(buffer)
                for (i in batchDraws.indices) {
                    val draw = batchDraws[i]
                    val executeInfo = method(stagedClass, "getExecuteInfo", draw.javaClass).invoke(buffer, draw) ?: continue
                    val type = batchTypes[i]
                    val prepared = method(type.javaClass, "prepare").invoke(type)
                    method(prepared.javaClass, "drawFromBuffer", executeInfo.javaClass).invoke(prepared, executeInfo)
                }
            } finally {
                batchTypes.clear()
                batchDraws.clear()
                runCatching { method(stagedClass, "endFrame").invoke(buffer) }.onFailure {
                    runCatching { (buffer as AutoCloseable).close() }
                    staged = null
                }
            }
        }
        //?}
    }
}
