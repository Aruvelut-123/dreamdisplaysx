package com.dreamdisplayx.platform.client

import com.dreamdisplayx.api.runtime.registry.service.getOrNull
import com.dreamdisplayx.core.protocol.common.packets.DreamPacket
import com.dreamdisplayx.platform.client.core.ClientApplication
import com.dreamdisplayx.platform.client.core.ClientLifecycleEvent
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.platform.client.input.MouseButtons
import com.dreamdisplayx.platform.client.managers.*
import com.dreamdisplayx.platform.client.net.ProtocolRouter
import com.dreamdisplayx.platform.client.overlay.OverlayManager
import com.dreamdisplayx.platform.client.render.DisplayYuvRenderTypes
import com.dreamdisplayx.platform.client.ui.FullscreenOverlayManager
import com.dreamdisplayx.platform.client.ui.MinecraftOverlayRenderContext
import com.dreamdisplayx.platform.client.utils.GameDirectory
import com.dreamdisplayx.platform.client.utils.MinecraftScreenUtil
import com.dreamdisplayx.util.GeneralUtil
import com.dreamdisplayx.util.OsInfo
import com.dreamdisplayx.util.log.DreamLog
import com.dreamdisplayx.util.natives.AndroidPaths
import com.dreamdisplayx.util.natives.NativesDownloader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor

//?} else
/*
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics
//?}
//? if <1.20 {
import com.dreamdisplayx.platform.client.ui.compat.GuiGraphics
//?}
*/

/** Main mod initializer. */
object Initializer {
    /** The mod identifier, used for channels, resources, and registration. */
    const val MOD_ID: String = "dreamdisplayx"

    /** Logger for startup and lifecycle messages. */
    private val logger = DreamLog.of("Initializer")

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

        // Resolve the real game directory first: it decides both where mirrored logs go and which
        // package the natives cache belongs to. `Minecraft.gameDirectory` is authoritative, but
        // `AndroidPaths` cannot read it (it is a pure-JVM module), so hand it over explicitly.
        val gameDirectory = GameDirectory.resolve()
        AndroidPaths.registerGameDirectory(gameDirectory)

        // Route this fork's logging through one entry point and mirror the diagnostic channels into
        // `<gameDir>/logs`, beside `latest.log`, rotated the way vanilla rotates its own logs. Must
        // run before the first subsystem logs anything and before LibVlc resolves its log path.
        val logsDirectory = GameDirectory.logs() ?: AndroidPaths.logsDirectory()
        DreamLog.configure(logsDirectory)
        DreamLog.mirrorToFile("LibVlc")

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
        // A JOIN is the only reliable save/world boundary: a dimension change swaps the level object
        // without emitting it, so anything still parked in unloadedScreens here belongs to the world
        // we just left. Restoring it would recreate that world's displays (and their native players,
        // and their AUDIO) inside the new one — reported as "world A's video keeps playing in world B,
        // then world A is silent when you go back" because both worlds share the "singleplayer" id and
        // the same overworld dimension key, so the distance census happily resurrected them.
        DisplayRegistry.discardUnloadedScreensForJoin(serverId)
        DisplayRegistry.loadScreensForServer(serverId)
        DreamServices.registry.getOrNull<ClientApplication>()
            ?.emit(ClientLifecycleEvent.ServerJoined(serverId))
    }

    /**
     * Called by the platform entrypoint on disconnect: persists and unloads all screens, resets
     * the per-server flags, and emits [ClientLifecycleEvent.ServerLeft].
     */
    fun onServerLeft() {
        // Fabric fires its disconnect event on the Netty IO thread
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) { // Not our problem
            mc.execute(::onServerLeft)
            return
        }

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

    /** A v1-only reply arrived: the server's Dream DisplaysX is too old to use. */
    fun onLegacyServerDetected() {
        ProtocolRouter.onLegacyServerDetected()
    }

    /** Decodes and dispatches v2 envelope [bytes] from the `dreamdisplayx:v2` channel. */
    fun onV2Packet(bytes: ByteArray) {
        ProtocolRouter.onV2Received(bytes)
    }

    /** Decodes and dispatches a v3 batch from the `dreamdisplayx:v3` channel. */
    fun onV3Packet(bytes: ByteArray) {
        ProtocolRouter.onV3Received(bytes)
    }

    /**
     * Screen queued by [openScreenNextTick], applied on the next client tick.
     *
     * `Minecraft.execute` runs the task *immediately* when the caller is already on the render
     * thread, so a command handler that used it to defer a screen still opened and closed that
     * screen inside the same command dispatch - which is why `/dlogin` looked like a no-op. This
     * field is drained by [onEndTick] instead, one full tick later.
     */
    private var pendingScreen: Screen? = null

    /** Queues [screen] to be opened on the next client tick instead of right now. */
    fun openScreenNextTick(screen: Screen) {
        pendingScreen = screen
    }

    /**
     * Main client tick handler. Detects level changes, manages render-distance unloading / restoring,
     * handles the right-click shortcut, and applies focus-mode blindness.
     */
    fun onEndTick(minecraft: Minecraft) {
        DisplayYuvRenderTypes.solidColorType()
        ClientTickManager.tick(minecraft)
        pendingScreen?.let { screen ->
            pendingScreen = null
            MinecraftScreenUtil.setScreen(minecraft, screen)
        }
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
        val window = mc.window
        val mouse = mc.mouseHandler
        DreamServices.registry.getOrNull<OverlayManager>()
            ?.renderAll(
                MinecraftOverlayRenderContext(
                    mc,
                    graphics,
                    //? if >=1.21.11 {
                    mouse.getScaledXPos(window).toInt(),
                    mouse.getScaledYPos(window).toInt(),
                    //?} else
                    /*(mouse.xpos() * window.guiScaledWidth / window.screenWidth).toInt(),
                    (mouse.ypos() * window.guiScaledHeight / window.screenHeight).toInt(),*/
                    MouseButtons.hardwareLeftDown(),
                    partialTick,
                )
            )
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
