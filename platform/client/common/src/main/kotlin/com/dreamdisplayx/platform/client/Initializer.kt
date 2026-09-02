package com.dreamdisplayx.platform.client

import com.dreamdisplayx.api.runtime.registry.service.getOrNull
import com.dreamdisplayx.core.protocol.common.packets.DreamPacket
import com.dreamdisplayx.platform.client.core.ClientApplication
import com.dreamdisplayx.platform.client.core.ClientLifecycleEvent
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.platform.client.managers.*
import com.dreamdisplayx.platform.client.net.LegacyAdapter
import com.dreamdisplayx.platform.client.net.ProtocolRouter
import com.dreamdisplayx.platform.client.overlay.OverlayManager
import com.dreamdisplayx.platform.client.ui.FullscreenOverlayManager
import com.dreamdisplayx.platform.client.ui.MinecraftOverlayRenderContext
import com.dreamdisplayx.platform.client.utils.MinecraftScreenUtil
import com.dreamdisplayx.util.GeneralUtil
import com.dreamdisplayx.util.OsInfo
import com.dreamdisplayx.util.natives.NativesDownloader
import net.minecraft.client.Minecraft
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor

//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.slf4j.LoggerFactory

/** Main mod initializer. */
object Initializer {
    /** The mod identifier, used for channels, resources, and registration. */
    const val MOD_ID: String = "dreamdisplayx"

    /** Logger for startup and lifecycle messages. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Called once during mod startup; initializes config, disk cache, and the focuser thread. */
    fun onModInit(dreamDisplaysMod: Mod) {
        // Android (PojavLauncher / FCL / Zalith): supported. The pipeline is pure libvlc via
        // JNA (dlopen, no subprocess), the natives cache moves to exec-friendly app-internal
        // storage (see AndroidPaths), audio runs through libvlc's OpenSL ES output, and the
        // AWT headless override below is skipped since the JVM there ships no java.desktop.
        // On macOS, VideoPopoutWindow uses GLFW (not AWT), so no AWT setup is needed.
        // On Windows / Linux, AWT is used: override java.awt.headless so a JFrame can open.
        // Must run before any AWT class initializes the Toolkit.
        if (!OsInfo.isMac && !OsInfo.isAndroid) {
            System.setProperty("java.awt.headless", "false")
        }
        ClientPacketManager.bind(dreamDisplaysMod)

        // Fetch the native LibVLC runtime into ./dreamdisplayx/natives/ before
        // anything tries to use it. No-ops when already cached.
        NativesDownloader.ensure()

        // Patch only the selected Complementary r5.8.1 pack into a disposable copy before the
        // shader loader initializes. BSL and unknown packs are intentionally left untouched.
        com.dreamdisplayx.platform.client.render.ComplementaryShaderPatcher.patchAtStartup()

        logger.info("Dream DisplaysX v${GeneralUtil.getPrettyModVersion()} (${GeneralUtil.getCommitId()}) starting...")
        ClientStartupManager.start()
    }

    /**
     * Called by the platform entrypoint after joining a server: records [serverId] in the client
     * state, restores saved screens, and emits [ClientLifecycleEvent.ServerJoined].
     */
    fun onServerJoined(serverId: String) {
        ClientStateManager.connectedServerId = serverId
        DisplayRegistry.loadScreensForServer(serverId)
        DreamServices.registry.getOrNull<ClientApplication>()
            ?.emit(ClientLifecycleEvent.ServerJoined(serverId))
    }

    /**
     * Called by the platform entrypoint on disconnect: persists and unloads all screens, resets
     * the per-server flags, and emits [ClientLifecycleEvent.ServerLeft].
     */
    fun onServerLeft() {
        val serverId = ClientStateManager.connectedServerId
        DisplayRegistry.saveAllScreens()
        DisplayRegistry.unloadAll()
        ClientStateManager.isPremium = false
        ClientStateManager.isAdmin = false
        ClientStateManager.connectedServerId = null
        ProtocolRouter.reset()
        ClientPacketManager.reset()
        if (serverId != null) {
            DreamServices.registry.getOrNull<ClientApplication>()
                ?.emit(ClientLifecycleEvent.ServerLeft(serverId))
        }
    }

    /** Lifts an incoming frozen-v1 [payload] into its v2 packet and dispatches it. */
    fun onLegacyPacket(payload: CustomPacketPayload) {
        ProtocolRouter.onLegacyReceived(LegacyAdapter.fromLegacy(payload))
    }

    /** Decodes and dispatches v2 envelope [bytes] from the `dreamdisplayx:v2` channel. */
    fun onV2Packet(bytes: ByteArray) {
        ProtocolRouter.onV2Received(bytes)
    }

    /**
     * Main client tick handler. Detects level changes, manages render-distance unloading / restoring,
     * handles the right-click shortcut, and applies focus-mode blindness.
     */
    fun onEndTick(minecraft: Minecraft) {
        ClientTickManager.tick(minecraft)
    }

    /** Renders the fullscreen and PiP overlays on the HUD when the player is in-world and no screen is open. */
    //? if >=26 {
    fun onRenderHud(mc: Minecraft, graphics: GuiGraphicsExtractor, partialTick: Float) {
        //?} else
        /*fun onRenderHud(mc: Minecraft, graphics: GuiGraphics, partialTick: Float) {*/
        if (mc.level == null || mc.player == null) return
        if (!com.dreamdisplayx.platform.client.render.FlashbackCompat.shouldRenderHud) return
        if (MinecraftScreenUtil.currentScreen(mc) != null) return
        //? if >=1.21.11 {
        graphics.nextStratum()
        //?}
        FullscreenOverlayManager.renderAll(mc, graphics, partialTick)
        DreamServices.registry.getOrNull<OverlayManager>()
            ?.renderAll(MinecraftOverlayRenderContext(mc, graphics, -1, -1, false, partialTick))
    }

    /** Routes an outgoing [packet] through protocol negotiation (v2 when available, else v1). */
    fun sendPacket(packet: DreamPacket) {
        ProtocolRouter.send(packet)
    }

    /** Saves screen data to disk, stops all players, and interrupts background threads on mod shutdown. */
    fun onStop() {
        ClientShutdownManager.stop()
    }
}
