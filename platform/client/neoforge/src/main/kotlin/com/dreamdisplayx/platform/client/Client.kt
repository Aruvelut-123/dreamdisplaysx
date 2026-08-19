package com.dreamdisplayx.platform.client

import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.platform.client.login.PlatformLoginScreen
import com.dreamdisplayx.platform.client.platform.NeoForgePlatformIntegrationProvider
import com.dreamdisplayx.api.platform.service.keys.PlatformServices
import com.dreamdisplayx.platform.client.render.ScreenRenderer
import com.dreamdisplayx.platform.client.screenshare.ScreenShareCommand
import com.dreamdisplayx.platform.client.Mod as DreamMod
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.commands.Commands
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
//? if >=1.21.11 {
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent
//?}
import net.neoforged.neoforge.common.NeoForge

@Mod(value = Initializer.MOD_ID, dist = [Dist.CLIENT])
class Client(modEventBus: IEventBus) : DreamMod {
    init {
        // The Platform must be in the registry before onModInit, so ClientStartupManager
        // can host the ClientApplication on top of it during bootstrap.
        DreamServices.registry.register(PlatformServices.PLATFORM, NeoForgePlatformIntegrationProvider.create())
        Initializer.onModInit(this)

        // Payload registration lives entirely in NeoForgeServer.registerPayloads (see
        // platform/server/.../Main.kt): that class loads unconditionally on every dist, unlike this
        // one (dist = [Dist.CLIENT]), and NeoForge rejects registering the same payload id twice,
        // so there can only be one registrar per mod, not one per @Mod class.

        // RegisterClientCommandsEvent is a game-bus (NeoForge.EVENT_BUS) event, not an
        // IModBusEvent, so it must not go through modEventBus.
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands)
        NeoForge.EVENT_BUS.register(this)
    }

    /** Registers the client-side screen-sharing commands (`/share start` / `/share stop`). */
    private fun onRegisterClientCommands(event: RegisterClientCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("share")
                .then(
                    Commands.literal("start")
                        .executes {
                            ScreenShareCommand.feedback(ScreenShareCommand.start())
                            1
                        }
                )
                .then(
                    Commands.literal("stop")
                        .executes {
                            ScreenShareCommand.feedback(ScreenShareCommand.stop())
                            1
                        }
                )
        )
        // Opens the Bilibili login screen (QR code / phone + password).
        event.dispatcher.register(
            Commands.literal("dlogin")
                .executes {
                    //? if >=26.2 {
                    Minecraft.getInstance().setScreenAndShow(PlatformLoginScreen())
                    //?} else
                    /*Minecraft.getInstance().setScreen(PlatformLoginScreen())*/
                    1
                }
        )
    }

    /** On server join / leave events. */
    @SubscribeEvent
    fun onLogin(event: ClientPlayerNetworkEvent.LoggingIn) {
        val mc = Minecraft.getInstance()
        if (mc.level != null && mc.player != null) {
            val serverId = if (mc.hasSingleplayerServer()) "singleplayer"
            else mc.currentServer?.ip ?: "unknown"
            Initializer.onServerJoined(serverId)
        }
    }

    /** On server join / leave events. */
    @SubscribeEvent
    fun onDisconnect(event: ClientPlayerNetworkEvent.LoggingOut) {
        Initializer.onServerLeft()
    }

    //? if >=1.21.11 {
    /** On client shutdown. */
    @SubscribeEvent
    fun onClientStopping(event: ClientStoppingEvent) {
        Initializer.onStop()
    }
    //?}

    //? if >=1.21.11 {
    /** On render events. */
    @SubscribeEvent
    fun onRenderAfterLevel(event: RenderLevelStageEvent.AfterLevel) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        try {
            modelViewStack.mul(event.modelViewMatrix)
            ScreenRenderer.render(event.poseStack, mainCamera(mc))
        } finally {
            modelViewStack.popMatrix()
        }
    }
    //?} else
    /*
    // On render events.
    @SubscribeEvent fun onRenderAfterLevel(event: RenderLevelStageEvent) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return
        ScreenRenderer.render(event.poseStack, event.camera)
    }*/

    /** Main camera accessor. */
    private fun mainCamera(mc: Minecraft): Camera {
        //? if >=26.2 {
        return mc.gameRenderer.mainCamera()
        //?} else
        /*return mc.gameRenderer.getMainCamera()*/
    }

    /** On tick events. */
    @SubscribeEvent
    fun onEndTick(event: ClientTickEvent.Post) {
        Initializer.onEndTick(Minecraft.getInstance())
    }

    /** On render events. */
    @SubscribeEvent
    fun onRenderGui(event: RenderGuiEvent.Post) {
        Initializer.onRenderHud(
            Minecraft.getInstance(),
            event.guiGraphics,
            event.partialTick.getGameTimeDeltaPartialTick(false)
        )
        // Render popout windows after all Minecraft/mod rendering is submitted,
        // so any GL-context switch (macOS GLFW backend) does not disturb in-flight commands.
        DisplayRegistry.getScreens().forEach { it.renderPopout() }
    }

    override fun sendPacket(packet: CustomPacketPayload) {
        /** Packet sender. */
        Minecraft.getInstance().connection?.send(packet)
    }
}
