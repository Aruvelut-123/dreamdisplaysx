package com.dreamdisplays.platform.client

//? if >=1.21.11 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
//?} else
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback*/
//? if >=26 {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
//?} else
/*
//? if ==1.21.11 {
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
//?}
//? if <1.21.11 {
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
//?}
*/
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplays.core.services.keys.PlatformServices
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.net.LegacyProbe
import com.dreamdisplays.platform.client.net.V2Payload
import com.dreamdisplays.platform.client.platform.FabricPlatformIntegrationProvider
import com.dreamdisplays.platform.client.render.ScreenRenderer
import com.dreamdisplays.platform.client.render.UnshadedDisplayPass
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.api.ClientModInitializer
import com.dreamdisplays.platform.client.input.DisplayMenuInputHandler
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.world.InteractionResult
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
//? if <1.19.4 {
import com.dreamdisplays.core.runtime.getOrNull
import com.dreamdisplays.platform.client.input.MouseButtons
import com.dreamdisplays.platform.client.overlay.OverlayManager
import com.dreamdisplays.platform.client.ui.FullscreenOverlayManager
import com.dreamdisplays.platform.client.ui.MinecraftOverlayRenderContext
import com.dreamdisplays.platform.client.ui.compat.GuiGraphics
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
//?}
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
//? if >=1.20.5 {
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//?} else
/*import com.dreamdisplays.platform.client.net.compat.CompatPayloads
import com.dreamdisplays.platform.client.net.compat.CustomPacketPayload*/
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy

class Client : ClientModInitializer, Mod {
    private var customGeometryUnavailable = false

    override fun onInitializeClient() {
        // The Platform must be in the registry before onModInit so ClientStartupManager
        // can host the ClientApplication on top of it during bootstrap.
        DreamServices.registry.register(PlatformServices.PLATFORM, FabricPlatformIntegrationProvider.create())
        Initializer.onModInit(this)

        // Note: PayloadTypeRegistry registrations are done in platform/server/ (it's a main entrypoint)
        // which runs on both integrated and dedicated servers, before the client entrypoint.

        //? if >=1.20.5 {
        // Protocol v2: every packet arrives as one opaque envelope payload
        ClientPlayNetworking.registerGlobalReceiver(V2Payload.TYPE) { payload, _ ->
            Initializer.onV2Packet(payload.bytes)
        }

        // Protocol v1 is unsupported; a v1 reply only tells us the server is outdated
        ClientPlayNetworking.registerGlobalReceiver(LegacyProbe.Premium.PACKET_ID) { _, _ ->
            Initializer.onLegacyServerDetected()
        }
        //?} else
        /*
        ClientPlayNetworking.registerGlobalReceiver(V2Payload.TYPE.id()) { client, _, buf, _ ->
            val bytes = ByteArray(buf.readableBytes()).also(buf::readBytes)
            client.execute { Initializer.onV2Packet(bytes) }
        }
        ClientPlayNetworking.registerGlobalReceiver(LegacyProbe.Premium.PACKET_ID.id()) { client, _, _, _ ->
            client.execute { Initializer.onLegacyServerDetected() }
        }*/

        //? if >=26 {
        LevelRenderEvents.BEFORE_GIZMOS.register { context ->
            val mc = Minecraft.getInstance()
            if (mc.level != null && mc.player != null) {
                renderSubmittedScreens(context, mc)
            }
        }

        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register { context ->
            val mc = Minecraft.getInstance()
            if (mc.level != null && mc.player != null) {
                renderBufferedScreens(context, mc)
            }
        }

        LevelRenderEvents.END_MAIN.register { _ ->
            val mc = Minecraft.getInstance()
            if (mc.level != null && mc.player != null) {
                // Render popout windows after all Minecraft / mod rendering is submitted,
                // so any GL-context switch (macOS GLFW backend) does not disturb in-flight commands.
                DisplayRegistry.getScreens().forEach { it.renderPopout() }
            }
        }

        //?} else
        /*WorldRenderEvents.AFTER_ENTITIES.register { context ->
            val mc = Minecraft.getInstance()
            if (mc.level != null && mc.player != null) {
                val stack = worldPoseStack(context)
                val camera = mainCamera(mc)
                UnshadedDisplayPass.capture(stack, camera)
                ScreenRenderer.render(stack, camera)
                DisplayRegistry.getScreens().forEach { it.renderPopout() }
            }
        }*/

        //? if >=26 {
        LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register { _, state -> !isDisplayBlock(state.pos()) }
        //?} else
        /*
        //? if ==1.21.11 {
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register { _, state -> !isDisplayBlock(state.pos()) }
        //?}
        //? if <1.21.11 {
        WorldRenderEvents.BLOCK_OUTLINE.register { _, context -> !isDisplayBlock(context.blockPos()) }
        //?}
        */

        //? if >=1.21.11 {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "pip_overlay")
        ) { graphics, deltaTracker ->
            Initializer.onRenderHud(
                Minecraft.getInstance(), graphics,
                deltaTracker.getGameTimeDeltaPartialTick(false)
            )
        }
        //?} else
        /*HudRenderCallback.EVENT.register { graphics, deltaTracker ->
            Initializer.onRenderHud(
                Minecraft.getInstance(),
                //? if >=1.20 {
                graphics,
                //?}
                //? if <1.20 {
                com.dreamdisplays.platform.client.ui.compat.GuiGraphics(graphics),
                //?}
                //? if >=1.21.1 {
                deltaTracker.getGameTimeDeltaPartialTick(false)
                //?}
                //? if <1.21.1 {
                deltaTracker
                //?}
            )
        }*/

        ClientTickEvents.END_CLIENT_TICK.register { Initializer.onEndTick(it) }

        UseBlockCallback.EVENT.register { _, world, _, _ ->
            if (!world.isClientSide) InteractionResult.PASS
            else if (DisplayMenuInputHandler.tryOpenFromWorld()) InteractionResult.FAIL
            else InteractionResult.PASS
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            if (client.level != null && client.player != null) {
                val serverId = if (client.isLocalServer) "singleplayer"
                else client.currentServer?.ip ?: "unknown"
                Initializer.onServerJoined(serverId)
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            Initializer.onServerLeft()
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register { Initializer.onStop() }

        //? if <1.19.4 {
        registerLegacyScreenOverlays()
        //?}
    }

    override fun sendPacket(packet: CustomPacketPayload) {
        //? if >=1.20.5 {
        ClientPlayNetworking.send(packet)
        //?} else
        /*ClientPlayNetworking.send(packet.type().id(), CompatPayloads.encode(packet))*/
    }

    //? if <1.19.4 {
    private fun registerLegacyScreenOverlays() {
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.BEFORE_INIT.register { mc, screen, _, _ ->
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.beforeRender(screen).register { _, pose, _, _, tickDelta ->
                if (FullscreenOverlayManager.isEmpty) return@register
                FullscreenOverlayManager.onClientTick(mc)
                if (FullscreenOverlayManager.isEmpty) return@register
                if (MinecraftScreenUtil.isTransientLoadingScreen(MinecraftScreenUtil.currentScreen(mc))) return@register
                FullscreenOverlayManager.renderAll(mc, GuiGraphics(pose), tickDelta)
            }
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen).register { _, pose, mouseX, mouseY, tickDelta ->
                val graphics = GuiGraphics(pose)
                if (!FullscreenOverlayManager.isEmpty &&
                    MinecraftScreenUtil.isTransientLoadingScreen(MinecraftScreenUtil.currentScreen(mc))
                ) {
                    FullscreenOverlayManager.renderAll(mc, graphics, tickDelta)
                }
                val overlays = DreamServices.registry.getOrNull<OverlayManager>() ?: return@register
                if (overlays.isEmpty) return@register
                overlays.renderAll(
                    MinecraftOverlayRenderContext(mc, graphics, mouseX, mouseY, MouseButtons.hardwareLeftDown(), tickDelta)
                )
            }
        }
    }
    //?}

    private fun isDisplayBlock(pos: BlockPos): Boolean =
        DisplayRegistry.getScreens().any { it.isInScreen(pos) }

    //? if >=26 {
    private val customGeometryRendererClass: Class<*> by lazy {
        Class.forName("net.minecraft.client.renderer.SubmitNodeCollector\$CustomGeometryRenderer")
    }

    private var submitNodeCollectorMethod: java.lang.reflect.Method? = null
    private var submitCustomGeometryMethod: java.lang.reflect.Method? = null
    private var hasBufferSourceCache: Boolean? = null

    private fun renderSubmittedScreens(context: LevelRenderContext, mc: Minecraft) {
        val camera = mainCamera(mc)
        if (hasBufferSource(context)) {
            return
        }

        UnshadedDisplayPass.capture(context.poseStack(), camera)

        val submitNodeCollector = runCatching {
            val method = submitNodeCollectorMethod
                ?.takeIf { it.declaringClass.isAssignableFrom(context.javaClass) }
                ?: context.javaClass.getMethod("submitNodeCollector").also { submitNodeCollectorMethod = it }
            method.invoke(context)
        }.getOrNull()

        if (submitNodeCollector == null || customGeometryUnavailable) {
            ScreenRenderer.render(context.poseStack(), camera)
            return
        }

        runCatching {
            ScreenRenderer.render(context.poseStack(), camera) { type, appendVertices ->
                submitCustomGeometry(context.poseStack(), submitNodeCollector, type, appendVertices)
            }
        }.onFailure { e ->
            customGeometryUnavailable = true
            logger.warn("Fabric custom geometry submission unavailable, falling back to immediate rendering: ${e.message}.")
            ScreenRenderer.render(context.poseStack(), camera)
        }
    }

    private fun renderBufferedScreens(context: LevelRenderContext, mc: Minecraft) {
        if (!hasBufferSource(context)) {
            return
        }
        val camera = mainCamera(mc)
        UnshadedDisplayPass.capture(context.poseStack(), camera)
        renderWithBufferSource(context, camera)
    }

    private fun hasBufferSource(context: LevelRenderContext): Boolean =
        hasBufferSourceCache
            ?: runCatching { context.javaClass.getMethod("bufferSource") }.isSuccess.also { hasBufferSourceCache = it }

    private var bufferSourceMethod: java.lang.reflect.Method? = null
    private var getBufferMethod: java.lang.reflect.Method? = null
    private var endBatchMethod: java.lang.reflect.Method? = null

    private fun renderWithBufferSource(context: LevelRenderContext, camera: Camera) {
        val bufferSource = runCatching {
            val method = bufferSourceMethod?.takeIf { it.declaringClass.isAssignableFrom(context.javaClass) }
                ?: context.javaClass.getMethod("bufferSource").also { bufferSourceMethod = it }
            method.invoke(context)
        }.getOrNull() ?: return
        val getBuffer = getBufferMethod?.takeIf { it.declaringClass.isAssignableFrom(bufferSource.javaClass) }
            ?: runCatching { bufferSource.javaClass.getMethod("getBuffer", RenderType::class.java) }
                .getOrNull()?.also { getBufferMethod = it } ?: return
        val endBatch = endBatchMethod?.takeIf { it.declaringClass.isAssignableFrom(bufferSource.javaClass) }
            ?: runCatching { bufferSource.javaClass.getMethod("endBatch") }
                .getOrNull()?.also { endBatchMethod = it } ?: return

        ScreenRenderer.render(context.poseStack(), camera) { type, appendVertices ->
            appendVertices(context.poseStack().last(), getBuffer.invoke(bufferSource, type) as VertexConsumer)
        }
        endBatch.invoke(bufferSource)
    }

    private fun submitCustomGeometry(
        stack: PoseStack,
        submitNodeCollector: Any,
        type: RenderType,
        appendVertices: (PoseStack.Pose, VertexConsumer) -> Unit,
    ) {
        val rendererClass = customGeometryRendererClass
        val renderer = Proxy.newProxyInstance(rendererClass.classLoader, arrayOf(rendererClass)) { _, method, args ->
            if (method.name == "render" && args != null && args.size == 2) {
                appendVertices(args[0] as PoseStack.Pose, args[1] as VertexConsumer)
            }
            null
        }
        val method = submitCustomGeometryMethod
            ?.takeIf { it.declaringClass.isAssignableFrom(submitNodeCollector.javaClass) }
            ?: submitNodeCollector.javaClass
                .getMethod("submitCustomGeometry", PoseStack::class.java, RenderType::class.java, rendererClass)
                .also { submitCustomGeometryMethod = it }
        method.invoke(submitNodeCollector, stack, type, renderer)
    }
    //?}

    private fun mainCamera(mc: Minecraft): Camera {
        //? if >=26.2 {
        return mc.gameRenderer.mainCamera()
        //?} else
        /*return mc.gameRenderer.getMainCamera()*/
    }

    private fun worldPoseStack(context: Any): PoseStack =
        runCatching { context.javaClass.getMethod("matrixStack") }
            .getOrElse { context.javaClass.getMethod("matrices") }
            .invoke(context) as PoseStack

    private companion object {
        private val logger = LoggerFactory.getLogger(javaClass)
    }
}
