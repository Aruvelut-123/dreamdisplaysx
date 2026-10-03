package com.dreamdisplayx.platform.client

import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import net.minecraft.client.Minecraft

/** Background thread that temporarily mutes / unmutes screens based on window focus. */
class Focuser : Thread() {
    init {
        isDaemon = true
        instance = this
        name = "window-focus-mute-thread"
    }

    /** Polls window focus every 250 ms and mutes or unmutes all screens when `mute-on-alt-tab` is enabled. */
    override fun run() {
        while (true) {
            val mc: Minecraft? = runCatching { Minecraft.getInstance() }.getOrNull()
            if (mc != null) {
                // Drive the focus-mute state unconditionally, even when the feature is disabled:
                // previously the loop only ran while `muteOnAltTab` was on, so turning the option off
                // (or launching with it off) after a focus loss left every screen muted forever —
                // the video played but there was no sound.
                val muted = ClientStateManager.config.muteOnAltTab && !mc.isWindowActive
                for (screen in DisplayRegistry.getScreens()) {
                    screen.setFocusMuted(muted)
                }
            }
            try {
                sleep(250)
            } catch (_: InterruptedException) {
                currentThread().interrupt()
                break
            }
        }
    }

    companion object {
        /** The running focuser thread, or `null` before it is started. */
        @Volatile
        var instance: Focuser? = null
            private set
    }
}
