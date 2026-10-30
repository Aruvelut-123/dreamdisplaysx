//? if >=1.19 {
package com.dreamdisplayx.platform.client

import com.dreamdisplayx.api.runtime.registry.service.getOrNull
import com.dreamdisplayx.api.platform.service.keys.PlatformServices
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.platform.client.input.DisplayMenuInputHandler
import com.dreamdisplayx.platform.client.input.MouseButtons
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import com.dreamdisplayx.platform.client.net.compat.CustomPacketPayload
import com.dreamdisplayx.platform.client.overlay.CrosshairPolicy
import com.dreamdisplayx.platform.client.overlay.OverlayManager
import com.dreamdisplayx.platform.client.platform.ForgePlatformIntegrationProvider
import com.dreamdisplayx.platform.client.render.ScreenRenderer
import com.dreamdisplayx.platform.client.render.UnshadedDisplayPass
import com.dreamdisplayx.platform.client.ui.FullscreenOverlayManager
import com.dreamdisplayx.platform.client.ui.MinecraftOverlayRenderContext
import com.dreamdisplayx.platform.client.ui.config.ClothConfigScreen
import com.dreamdisplayx.platform.client.utils.MinecraftScreenUtil
import com.dreamdisplayx.platform.client.net.compat.CompatPayloads
//? if <1.20 {
import com.dreamdisplayx.platform.client.ui.compat.GuiGraphics
//?}
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket
import net.minecraft.world.InteractionResult
import net.minecraftforge.client.ConfigScreenHandler
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RenderGuiEvent
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.client.event.RenderHighlightEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.event.ScreenEvent
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.GameShuttingDownEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.ModLoadingContext

/** `Forge` client side, brought up by `ForgeServer` on the client dist. */
object Client : Mod {
    private val hiddenHudOverlays = listOf(
        VanillaGuiOverlay.HOTBAR, VanillaGuiOverlay.ARMOR_LEVEL, VanillaGuiOverlay.FOOD_LEVEL,
        VanillaGuiOverlay.AIR_LEVEL, VanillaGuiOverlay.MOUNT_HEALTH, VanillaGuiOverlay.JUMP_BAR,
        VanillaGuiOverlay.EXPERIENCE_BAR, VanillaGuiOverlay.ITEM_NAME, VanillaGuiOverlay.PLAYER_HEALTH,
        VanillaGuiOverlay.CHAT_PANEL, VanillaGuiOverlay.SCOREBOARD, VanillaGuiOverlay.PLAYER_LIST,
        VanillaGuiOverlay.BOSS_EVENT_PROGRESS,
    ).map { it.id() }.toSet()

    /** Registers the client side. */
    fun init() {
        if (ModList.get().isLoaded("cloth_config")) {
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory::class.java) {
                ConfigScreenHandler.ConfigScreenFactory { _, parent -> ClothConfigScreen.create(parent) }
            }
        }

        DreamServices.registry.register(PlatformServices.PLATFORM, ForgePlatformIntegrationProvider.create())
        Initializer.onModInit(this)

        MinecraftForge.EVENT_BUS.register(this)
    }

    /** On server join event. */
    @SubscribeEvent
    fun onLogin(event: ClientPlayerNetworkEvent.LoggingIn) {
        val mc = Minecraft.getInstance()
        if (mc.level != null && mc.player != null) {
            val serverId = if (mc.hasSingleplayerServer()) "singleplayer"
            else mc.currentServer?.ip ?: "unknown"
            Initializer.onServerJoined(serverId)
        }
    }

    /** On server leave event. */
    @SubscribeEvent
    fun onDisconnect(event: ClientPlayerNetworkEvent.LoggingOut) {
        Initializer.onServerLeft()
    }

    /** On client shutdown. */
    @SubscribeEvent
    fun onShutdown(event: GameShuttingDownEvent) {
        Initializer.onStop()
    }

    @SubscribeEvent
    fun onRenderLevel(event: RenderLevelStageEvent) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        when (event.stage) {
            RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES -> {
                UnshadedDisplayPass.capture(event.poseStack, event.camera)
                ScreenRenderer.render(event.poseStack, event.camera)
            }
            //? if >=1.20 {
            RenderLevelStageEvent.Stage.AFTER_LEVEL -> UnshadedDisplayPass.drawPending()
            //?} else
            /*RenderLevelStageEvent.Stage.AFTER_WEATHER -> UnshadedDisplayPass.drawPending()*/
            else -> Unit
        }
    }

    @SubscribeEvent
    fun onRenderBlockHighlight(event: RenderHighlightEvent.Block) {
        if (isDisplayBlock(event.target.blockPos)) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent
    fun onUseBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (!event.level.isClientSide) return
        if (DisplayMenuInputHandler.tryOpenFromWorld()) {
            event.cancellationResult = InteractionResult.FAIL
            event.isCanceled = true
        }
    }

    private fun isDisplayBlock(pos: BlockPos): Boolean =
        DisplayRegistry.getScreens().any { it.isInScreen(pos) }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.END) Initializer.onEndTick(Minecraft.getInstance())
    }

    @SubscribeEvent
    fun onRenderGui(event: RenderGuiEvent.Post) {
        //? if >=1.20 {
        Initializer.onRenderHud(Minecraft.getInstance(), event.guiGraphics, event.partialTick)
        //?} else
        /*Initializer.onRenderHud(Minecraft.getInstance(), GuiGraphics(event.poseStack), event.partialTick)*/
        DisplayRegistry.getScreens().forEach { it.renderPopout() }
    }

    @SubscribeEvent
    fun onRenderOverlay(event: RenderGuiOverlayEvent.Pre) {
        val id = event.overlay.id()
        if (id == VanillaGuiOverlay.CROSSHAIR.id()) {
            val suppress = DreamServices.registry.getOrNull<CrosshairPolicy>()
                ?.shouldSuppressCrosshair()
                ?: ClientStateManager.isOnScreen
            if (suppress) event.isCanceled = true
        } else if (id in hiddenHudOverlays && FullscreenOverlayManager.shouldHideVanillaHud) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent
    fun onScreenRenderPre(event: ScreenEvent.Render.Pre) {
        if (FullscreenOverlayManager.isEmpty) return
        val mc = Minecraft.getInstance()
        FullscreenOverlayManager.onClientTick(mc)
        if (FullscreenOverlayManager.isEmpty) return
        if (MinecraftScreenUtil.isTransientLoadingScreen(MinecraftScreenUtil.currentScreen(mc))) return
        //? if >=1.20 {
        FullscreenOverlayManager.renderAll(mc, event.guiGraphics, event.partialTick)
        //?} else
        /*FullscreenOverlayManager.renderAll(mc, GuiGraphics(event.poseStack), event.partialTick)*/
    }

    @SubscribeEvent
    fun onScreenRenderPost(event: ScreenEvent.Render.Post) {
        val mc = Minecraft.getInstance()
        //? if >=1.20 {
        val graphics = event.guiGraphics
        //?} else
        /*val graphics = GuiGraphics(event.poseStack)*/
        if (!FullscreenOverlayManager.isEmpty &&
            MinecraftScreenUtil.isTransientLoadingScreen(MinecraftScreenUtil.currentScreen(mc))
        ) {
            FullscreenOverlayManager.renderAll(mc, graphics, event.partialTick)
        }
        val overlays = DreamServices.registry.getOrNull<OverlayManager>() ?: return
        if (overlays.isEmpty) return
        overlays.renderAll(
            MinecraftOverlayRenderContext(
                mc, graphics, event.mouseX, event.mouseY, MouseButtons.hardwareLeftDown(), event.partialTick,
            )
        )
    }

    override fun sendPacket(packet: CustomPacketPayload) {
        Minecraft.getInstance().connection?.send(ServerboundCustomPayloadPacket(packet.type().id(), CompatPayloads.encode(packet)))
    }
}
//?}
