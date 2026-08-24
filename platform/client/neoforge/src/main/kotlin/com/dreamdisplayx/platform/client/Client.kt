package com.dreamdisplayx.platform.client

import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.platform.client.login.PlatformLoginScreen
import com.dreamdisplayx.platform.client.platform.NeoForgePlatformIntegrationProvider
import com.dreamdisplayx.api.platform.service.keys.PlatformServices
import com.dreamdisplayx.platform.client.login.BilibiliLoginManager
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import com.dreamdisplayx.platform.client.render.ScreenRenderer
import com.dreamdisplayx.platform.client.ui.widgets.BilibiliAccountLabel
import com.dreamdisplayx.platform.client.Mod as DreamMod
import com.dreamdisplayx.media.source.bilibili.BilibiliApi
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
        // Register the Cloth Config screen as the in-game config UI (requires Cloth Config installed);
        // without Cloth there is no in-game editor — Config.toml remains the source of truth.
        registerConfigScreenFactory()

        //? if >=26 {
        modEventBus.addListener(::onRegisterDebugEntries)
        //?}

        // Payload registration lives entirely in NeoForgeServer.registerPayloads (see
        // platform/server/.../Main.kt): that class loads unconditionally on every dist, unlike this
        // one (dist = [Dist.CLIENT]), and NeoForge rejects registering the same payload id twice,
        // so there can only be one registrar per mod, not one per @Mod class.

        // RegisterClientCommandsEvent is a game-bus (NeoForge.EVENT_BUS) event, not an
        // IModBusEvent, so it must not go through modEventBus.
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands)
        NeoForge.EVENT_BUS.register(this)
    }

    /** Registers client-side commands. */
    private fun onRegisterClientCommands(event: RegisterClientCommandsEvent) {
        // Opens the Bilibili login screen (QR code / phone + password) — only usable when not
        // already logged in and when the player is OP on the server.
        event.dispatcher.register(
            Commands.literal("dlogin")
                .requires { ClientStateManager.isAdmin }
                .executes {
                    if (BilibiliApi.cookie.isNotBlank()) {
                        //? if >=26 {
                        Minecraft.getInstance().player?.sendSystemMessage(
                            net.minecraft.network.chat.Component.literal("§eAlready logged in. Use /dlogoff first."))
                        //?} else
                        /*Minecraft.getInstance().player?.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("§eAlready logged in. Use /dlogoff first."), false)*/
                        return@executes 1
                    }
                    //? if >=26.2 {
                    // Defer opening the screen one tick so the client command dispatch (which
                    // may close any screen it opened synchronously) has fully finished first.
                    Minecraft.getInstance().execute {
                        Minecraft.getInstance().setScreenAndShow(PlatformLoginScreen())
                    }
                    //?} else
                    /*Minecraft.getInstance().setScreen(PlatformLoginScreen())*/
                    1
                }
        )
        // Logs out of Bilibili — only usable when logged in and OP.
        event.dispatcher.register(
            Commands.literal("dlogoff")
                .requires { ClientStateManager.isAdmin }
                .executes {
                    if (BilibiliApi.cookie.isBlank()) {
                        //? if >=26 {
                        Minecraft.getInstance().player?.sendSystemMessage(
                            net.minecraft.network.chat.Component.literal("§eNot logged in."))
                        //?} else
                        /*Minecraft.getInstance().player?.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("§eNot logged in."), false)*/
                        return@executes 1
                    }
                    BilibiliLoginManager.logout()
                    BilibiliApi.cookie = ""
                    BilibiliAccountLabel.invalidate()
                    //? if >=26 {
                    Minecraft.getInstance().player?.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("§aLogged out of Bilibili."))
                    //?} else
                    /*Minecraft.getInstance().player?.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("§aLogged out of Bilibili."), false)*/
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

    /** Registers the Cloth Config screen factory when Cloth is present on the classpath. */
    private fun registerConfigScreenFactory() {
        val clothConfigClass = try {
            Class.forName("me.shedaniel.clothconfig2.api.ConfigBuilder")
            true
        } catch (_: Exception) {
            false
        }
        if (!clothConfigClass) return
        // IConfigScreenFactory is a functional interface: Screen createScreen(ModContainer, Screen)
        // Using reflection to avoid a compile dependency on the NeoForge client API.
        val factoryClass = try {
            Class.forName("net.neoforged.neoforge.client.gui.IConfigScreenFactory")
        } catch (_: Exception) {
            return
        }
        val container = net.neoforged.fml.ModLoadingContext.get().getActiveContainer()
        val registerMethod = container.javaClass.getMethod("registerExtensionPoint", Class::class.java, Any::class.java)
        val factory = java.lang.reflect.Proxy.newProxyInstance(
            factoryClass.classLoader, arrayOf(factoryClass),
        ) { _, _, args ->
            // The first argument is the ModContainer (ignored), the second is the parent Screen.
            val parent = args[1] as? net.minecraft.client.gui.screens.Screen
            com.dreamdisplayx.platform.client.config.NeoForgeClothConfigScreen.create(parent)
        }
        registerMethod.invoke(container, factoryClass, factory)
    }

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

    //? if >=26 {
    /** Registers the Dream DisplaysX debug entry for the F3 overlay. */
    private fun onRegisterDebugEntries(event: net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent) {
        event.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "debug"),
            com.dreamdisplayx.platform.client.debug.DreamDisplaysDebugEntry,
        )
    }
    //?}

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
