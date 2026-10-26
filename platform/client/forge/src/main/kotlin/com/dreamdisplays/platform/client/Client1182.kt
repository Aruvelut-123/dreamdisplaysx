//? if <1.19 {
package com.dreamdisplays.platform.client

import com.dreamdisplays.core.runtime.getOrNull
import com.dreamdisplays.core.services.keys.PlatformServices
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.input.DisplayMenuInputHandler
import com.dreamdisplays.platform.client.input.MouseButtons
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.net.compat.CompatPayloads
import com.dreamdisplays.platform.client.net.compat.CustomPacketPayload
import com.dreamdisplays.platform.client.overlay.CrosshairPolicy
import com.dreamdisplays.platform.client.overlay.OverlayManager
import com.dreamdisplays.platform.client.platform.ForgePlatformIntegrationProvider
import com.dreamdisplays.platform.client.render.ScreenRenderer
import com.dreamdisplays.platform.client.ui.FullscreenOverlayManager
import com.dreamdisplays.platform.client.ui.MinecraftOverlayRenderContext
import com.dreamdisplays.platform.client.ui.compat.GuiGraphics
import com.dreamdisplays.platform.client.ui.config.ClothConfigScreen
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket
import net.minecraft.world.InteractionResult
import net.minecraftforge.client.ConfigGuiHandler
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.DrawSelectionEvent
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.event.ScreenEvent
import net.minecraftforge.client.gui.ForgeIngameGui
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.ModLoadingContext

/** `Forge` 1.18.2 client side. */
object Client : Mod {
    private val hiddenHudOverlays = setOf(
        ForgeIngameGui.HOTBAR_ELEMENT, ForgeIngameGui.ARMOR_LEVEL_ELEMENT, ForgeIngameGui.FOOD_LEVEL_ELEMENT,
        ForgeIngameGui.AIR_LEVEL_ELEMENT, ForgeIngameGui.MOUNT_HEALTH_ELEMENT, ForgeIngameGui.JUMP_BAR_ELEMENT,
        ForgeIngameGui.EXPERIENCE_BAR_ELEMENT, ForgeIngameGui.ITEM_NAME_ELEMENT, ForgeIngameGui.PLAYER_HEALTH_ELEMENT,
        ForgeIngameGui.CHAT_PANEL_ELEMENT, ForgeIngameGui.SCOREBOARD_ELEMENT, ForgeIngameGui.PLAYER_LIST_ELEMENT,
        ForgeIngameGui.BOSS_HEALTH_ELEMENT,
    )

    fun init() {
        if (ModList.get().isLoaded("cloth_config")) {
            ModLoadingContext.get().registerExtensionPoint(ConfigGuiHandler.ConfigGuiFactory::class.java) {
                ConfigGuiHandler.ConfigGuiFactory { _, parent -> ClothConfigScreen.create(parent) }
            }
        }

        DreamServices.registry.register(PlatformServices.PLATFORM, ForgePlatformIntegrationProvider.create())
        Initializer.onModInit(this)

        MinecraftForge.EVENT_BUS.register(this)
    }

    @SubscribeEvent
    fun onLogin(event: ClientPlayerNetworkEvent.LoggedInEvent) {
        val mc = Minecraft.getInstance()
        if (mc.level != null && mc.player != null) {
            val serverId = if (mc.hasSingleplayerServer()) "singleplayer"
            else mc.currentServer?.ip ?: "unknown"
            Initializer.onServerJoined(serverId)
        }
    }

    @SubscribeEvent
    fun onDisconnect(event: ClientPlayerNetworkEvent.LoggedOutEvent) {
        Initializer.onServerLeft()
    }

    @SubscribeEvent
    fun onRenderLevel(event: RenderLevelStageEvent) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        if (event.stage == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            ScreenRenderer.render(event.poseStack, event.camera)
        }
    }

    @SubscribeEvent
    fun onRenderBlockHighlight(event: DrawSelectionEvent.HighlightBlock) {
        if (isDisplayBlock(event.target.blockPos)) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent
    fun onUseBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (!event.world.isClientSide) return
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
    fun onRenderGui(event: RenderGameOverlayEvent.Post) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return
        Initializer.onRenderHud(Minecraft.getInstance(), GuiGraphics(event.matrixStack), event.partialTicks)
        DisplayRegistry.getScreens().forEach { it.renderPopout() }
    }

    @SubscribeEvent
    fun onRenderOverlay(event: RenderGameOverlayEvent.PreLayer) {
        val overlay = event.overlay
        if (overlay === ForgeIngameGui.CROSSHAIR_ELEMENT) {
            val suppress = DreamServices.registry.getOrNull<CrosshairPolicy>()
                ?.shouldSuppressCrosshair()
                ?: ClientStateManager.isOnScreen
            if (suppress) event.isCanceled = true
        } else if (overlay in hiddenHudOverlays && FullscreenOverlayManager.shouldHideVanillaHud) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent
    fun onScreenRenderPre(event: ScreenEvent.DrawScreenEvent.Pre) {
        if (FullscreenOverlayManager.isEmpty) return
        val mc = Minecraft.getInstance()
        FullscreenOverlayManager.onClientTick(mc)
        if (FullscreenOverlayManager.isEmpty) return
        if (MinecraftScreenUtil.isTransientLoadingScreen(MinecraftScreenUtil.currentScreen(mc))) return
        FullscreenOverlayManager.renderAll(mc, GuiGraphics(event.poseStack), event.partialTicks)
    }

    @SubscribeEvent
    fun onScreenRenderPost(event: ScreenEvent.DrawScreenEvent.Post) {
        val mc = Minecraft.getInstance()
        val graphics = GuiGraphics(event.poseStack)
        if (!FullscreenOverlayManager.isEmpty &&
            MinecraftScreenUtil.isTransientLoadingScreen(MinecraftScreenUtil.currentScreen(mc))
        ) {
            FullscreenOverlayManager.renderAll(mc, graphics, event.partialTicks)
        }
        val overlays = DreamServices.registry.getOrNull<OverlayManager>() ?: return
        if (overlays.isEmpty) return
        overlays.renderAll(
            MinecraftOverlayRenderContext(
                mc, graphics, event.mouseX, event.mouseY, MouseButtons.hardwareLeftDown(), event.partialTicks,
            )
        )
    }

    override fun sendPacket(packet: CustomPacketPayload) {
        Minecraft.getInstance().connection?.send(ServerboundCustomPayloadPacket(packet.type().id(), CompatPayloads.encode(packet)))
    }
}
//?}
