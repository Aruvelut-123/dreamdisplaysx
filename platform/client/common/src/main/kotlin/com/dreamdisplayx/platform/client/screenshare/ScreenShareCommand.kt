package com.dreamdisplayx.platform.client.screenshare

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Chat-feedback + action helpers for the `/share` client command, so the Fabric and NeoForge
 * command registrars stay one-liners and never drift from each other.
 */
object ScreenShareCommand {
    /** Starts a screen share to the connected modded server; returns the message to show the player. */
    fun start(): String = try {
        ScreenShareManager.start()
        "Screen sharing started. The server will provide a watch URL shortly."
    } catch (e: IllegalStateException) {
        "Screen sharing failed: ${e.message}."
    }

    /** Stops an in-progress screen share; returns the message to show the player. */
    fun stop(): String {
        val wasActive = ScreenShareManager.isActive
        ScreenShareManager.stop()
        return if (wasActive) "Screen sharing stopped." else "No screen share is running."
    }

    /** Handles the server's [com.dreamdisplayx.core.protocol.common.packets.ScreenShareAck] reply. */
    fun onAck(watchUrl: String) {
        val message = if (watchUrl.isNotEmpty()) {
            "Screen share is live! Viewers can watch: $watchUrl"
        } else {
            "Screen sharing is not available on this server (needs the mod on the server side)."
        }
        feedback(message)
    }

    /** Sends [message] to the local player as a client-side chat line, when one is present. */
    fun feedback(message: String) {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal(message), false)
    }
}
