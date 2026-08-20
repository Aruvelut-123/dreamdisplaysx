package com.dreamdisplayx.platform.client.managers

import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.display.service.DisplaySystem
import com.dreamdisplayx.api.runtime.registry.service.getOrNull
import com.dreamdisplayx.core.protocol.common.packets.*
import com.dreamdisplayx.core.services.DisplayStorage
import com.dreamdisplayx.media.source.bilibili.BilibiliApi
import com.dreamdisplayx.platform.client.Mod
import com.dreamdisplayx.platform.client.capabilities.CapabilityNegotiationService
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.platform.client.screenshare.ScreenShareCommand
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.slf4j.LoggerFactory

/**
 * Single client-side dispatcher for incoming [DreamPacket]s (v2 and legacy-lifted alike) and the
 * raw payload sender bound to the platform [Mod] implementation.
 */
object ClientPacketManager {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ClientPacketManager")

    /** The platform [Mod] used to send raw payloads; set via [bind]. */
    private lateinit var mod: Mod

    /** The latest applied [ServerHello]; legacy per-flag packets merge into this snapshot. */
    @Volatile
    var serverSnapshot: ServerHello = ServerHello(); private set

    /** Binds the platform [Mod] used to send packets. */
    fun bind(mod: Mod) {
        this.mod = mod
    }

    /** Sends a raw payload through the platform networking implementation. */
    fun send(packet: CustomPacketPayload) {
        mod.sendPacket(packet)
    }

    /** Applies an incoming packet to the client state; non-client-bound packets are ignored. */
    fun handle(packet: DreamPacket) {
        when (packet) {
            is ServerHello -> applyServerHello(packet)
            is SetDisplaysEnabled -> applyDisplaysEnabled(packet.enabled)
            is DisplayInfo -> DisplayLifecycleManager.handleInfoPacket(packet)
            is DisplaySync -> DisplayRegistry.screens[packet.id]?.let {
                it.updateData(packet)
                DisplayRegistry.recordScreen(it)
            }

            is WatchPartyState -> DisplayRegistry.screens[packet.id]?.let {
                it.updateWatchParty(packet)
                DisplayRegistry.recordScreen(it)
            }

            is FullscreenState -> FullscreenController.handle(packet)
            is RemotePlaybackToggle -> DisplayRegistry.screens[packet.id]?.setPaused(packet.paused)
            is ScreenShareAck -> ScreenShareCommand.onAck(packet.watchUrl)
            is PlatformCredentials -> {
                ClientStateManager.bilibiliSessdata = packet.bilibiliSessdata
                BilibiliApi.cookie = packet.bilibiliSessdata
                if (packet.bilibiliRefreshToken.isNotEmpty()) {
                    com.dreamdisplayx.media.source.bilibili.BilibiliAuth.refreshToken = packet.bilibiliRefreshToken
                }
                if (packet.bilibiliSessdata.isNotEmpty()) {
                    logger.info("Received Bilibili login credential from the server.")
                    // Refresh quality on all active displays — login may unlock higher-quality streams.
                    DisplayRegistry.screens.values.forEach { it.reloadQuality() }
                }
            }
            is DisplayDelete -> handleDelete(packet)
            is ClearCache -> handleClearCache(packet)
            else -> logger.debug("Ignoring non-client-bound packet {}.", packet::class.simpleName)
        }
    }

    /** Replaces the capability snapshot wholesale and mirrors the flags into the client state. */
    private fun applyServerHello(packet: ServerHello) {
        serverSnapshot = packet
        ClientStateManager.isPremium = packet.isPremium
        ClientStateManager.isAdmin = packet.isAdmin
        ClientStateManager.isReportingEnabled = packet.isReportingEnabled
        DreamServices.registry.getOrNull<CapabilityNegotiationService>()
            ?.onServerCapabilities(packet)
    }

    /** Server-forced display toggle (admin command), persisted like the legacy channel did. */
    private fun applyDisplaysEnabled(enabled: Boolean) {
        ClientStateManager.displaysEnabled = enabled
        ClientStateManager.config.displaysEnabled = enabled
        ClientStateManager.config.save()
    }

    /** Removes a deleted display from the registry and erases its saved data. */
    private fun handleDelete(packet: DisplayDelete) {
        DisplayRegistry.screens[packet.id]?.let { DisplayRegistry.unregisterScreen(it) }
        DisplayRegistry.unloadedScreens.remove(packet.id)
        DisplayStorage.removeDisplay(packet.id)
        logger.info("Display deleted and removed from saved data: ${packet.id}.")
    }

    /** Drops the listed displays from the registry (active or unloaded), display system, and saved data. */
    private fun handleClearCache(packet: ClearCache) {
        packet.ids.forEach { uuid ->
            DisplayRegistry.screens[uuid]?.let { DisplayRegistry.unregisterScreen(it) }
            DisplayRegistry.unloadedScreens.remove(uuid)
            DreamServices.registry.getOrNull<DisplaySystem>()?.removeDisplay(DisplayId(uuid))
            DisplayStorage.removeDisplay(uuid)
        }
    }

    /** Resets per-server negotiation state on disconnect. */
    fun reset() {
        serverSnapshot = ServerHello()
        FullscreenController.reset()
    }
}
