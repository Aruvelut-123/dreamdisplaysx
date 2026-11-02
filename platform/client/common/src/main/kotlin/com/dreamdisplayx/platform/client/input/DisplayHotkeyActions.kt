package com.dreamdisplayx.platform.client.input

import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.api.playback.service.keys.PlaybackServices
import com.dreamdisplayx.api.runtime.registry.service.getOrNull
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import com.dreamdisplayx.api.playback.model.FullscreenMode
import com.dreamdisplayx.platform.client.popout.PopoutManager
import com.dreamdisplayx.platform.client.ui.DisplayMenu
import com.dreamdisplayx.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft

/**
 * Executes the *world* hotkeys declared by [DisplayHotkeys] against the live client state.
 *
 * Kept apart from [DisplayMenuInputHandler] because the two answer different questions: that handler
 * decides whether a *mouse click* opens the menu, this one runs an action once a `KeyMapping` reports
 * a press. Mixing them would force the tick handler to fake click coordinates for keyboard input.
 *
 * Every entry point is written to fail quietly. A hotkey firing in the wrong context (no world, no
 * display under the crosshair, a server-authoritative display the player may not control) must be a
 * no-op, never an exception on the client tick thread.
 */
object DisplayHotkeyActions {
    /**
     * Runs [actionId] if the current context allows it.
     *
     * @return `true` when the action did something; `false` when it was skipped. Callers use this only
     * to decide whether to swallow the press, so a skip must not log above debug.
     */
    fun run(actionId: String): Boolean = when (actionId) {
        DisplayHotkeys.OPEN_MENU_ID -> openMenu()
        DisplayHotkeys.TOGGLE_PLAYBACK_ID -> togglePlayback()
        DisplayHotkeys.TOGGLE_DISPLAYS_ID -> toggleDisplays()
        DisplayHotkeys.FULLSCREEN_ID -> openFullscreen()
        else -> false
    }

    /**
     * Opens the menu for the targeted display.
     *
     * Deliberately does **not** require sneak, unlike the historical right-click path: this binding is
     * a dedicated key, so making it contextual on top of being an explicit bind would just be a way to
     * fail silently. Targeting a display is still required.
     */
    private fun openMenu(): Boolean {
        val screen = targetedScreen() ?: return false
        DisplayMenu.open(screen)
        return true
    }

    /** Play/pause on the targeted display, honouring the same permission the in-menu Space key checks. */
    private fun togglePlayback(): Boolean {
        val screen = targetedScreen() ?: return false
        if (!screen.canControlPlayback) return false
        val playback = DreamServices.registry.getOrNull(PlaybackServices.PLAYBACK) ?: return false
        val id = DisplayId(screen.uuid)
        if (screen.isPaused) playback.play(id) else playback.pause(id)
        return true
    }

    /**
     * Flips [ClientStateManager.displaysEnabled] and persists it.
     *
     * This one works with no display targeted on purpose: it is the escape hatch for a player whose
     * displays are stuttering a scene, and requiring them to aim first would defeat that.
     */
    private fun toggleDisplays(): Boolean {
        val next = !ClientStateManager.displaysEnabled
        ClientStateManager.displaysEnabled = next
        // Persist through the config so the choice survives a restart, matching the settings toggle.
        runCatching {
            ClientStateManager.config.displaysEnabled = next
            ClientStateManager.config.save()
        }
        return true
    }

    /** Opens the targeted display fullscreen, mirroring the in-menu `F` shortcut's conditions. */
    private fun openFullscreen(): Boolean {
        val screen = targetedScreen() ?: return false
        if (!screen.isVideoStarted || !screen.canPopoutHere) return false
        val popout = DreamServices.registry.getOrNull<PopoutManager>() ?: return false
        popout.openFullscreen(DisplayId(screen.uuid), FullscreenMode.STANDARD)
        return true
    }

    /** The display under the crosshair, or `null` when none is loaded. */
    private fun targetedScreen(): DisplayScreen? {
        val target = DreamServices.registry.getOrNull<DisplayInteractionService>()
            ?.getCurrentTarget() ?: return null
        return DisplayRegistry.screens[target.displayId.uuid]
    }

    /** Whether a world hotkey may fire at all: a live level and no screen holding input. */
    fun canRunWorldHotkeys(minecraft: Minecraft): Boolean =
        minecraft.player != null &&
            minecraft.level != null &&
            MinecraftScreenUtil.currentScreen(minecraft) == null
}