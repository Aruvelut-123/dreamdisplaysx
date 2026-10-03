package com.dreamdisplayx.platform.client.managers

import com.dreamdisplayx.api.runtime.registry.service.getOrNull
import com.dreamdisplayx.platform.client.Focuser
import com.dreamdisplayx.platform.client.core.ClientApplication
import com.dreamdisplayx.platform.client.core.DreamServices
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.media.player.MediaPlayer
import com.dreamdisplayx.util.DreamCoroutines

/**
 * Handles client shutdown cleanup.
 */
object ClientShutdownManager {
    /** Stops the application, saves and unloads screens, shuts down coroutines, and interrupts the focuser. */
    fun stop() {
        MediaPlayer.shutdownBackgroundWork()
        // Stop the native players synchronously BEFORE anything else can drop the async teardown: the
        // per-display stop queued by unregister() runs on executors that are shut down further below,
        // and a libvlc audio player left alive kept playing after the world was closed.
        DisplayRegistry.stopAllMediaBlocking()
        DreamServices.registry.getOrNull<ClientApplication>()?.stop()
        DisplayRegistry.saveAllScreens()
        ClientStartupManager.stop()
        DreamCoroutines.shutdown()
        DisplayRegistry.unloadAll()
        Focuser.instance?.interrupt()
    }
}
