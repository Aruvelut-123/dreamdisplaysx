package com.dreamdisplayx.platform.client.screenshare

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Chat-feedback + action helpers for the `/share` client command, so the Fabric and NeoForge
 * command registrars stay one-liners and never drift from each other.
 */
object ScreenShareCommand {
    /** Starts a screen share to [rtmpUrl]; returns the message to show the player. */
    fun start(rtmpUrl: String): String = try {
        ScreenShareManager.start(rtmpUrl)
        "Screen sharing started (push to $rtmpUrl). Use /share stop to end it."
    } catch (e: IllegalStateException) {
        "Screen sharing failed: ${e.message}."
    }

    /** Stops an in-progress screen share; returns the message to show the player. */
    fun stop(): String {
        val wasActive = ScreenShareManager.isActive
        ScreenShareManager.stop()
        return if (wasActive) "Screen sharing stopped." else "No screen share is running."
    }

    /** Sends [message] to the local player as a client-side chat line, when one is present. */
    fun feedback(message: String) {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal(message), false)
    }
}
