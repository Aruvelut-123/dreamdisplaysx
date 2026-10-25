package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.shared.playback.FullscreenAckAction
import com.dreamdisplays.shared.playback.FullscreenMode
import com.dreamdisplays.core.protocol.common.packets.FullscreenAck
import com.dreamdisplays.core.protocol.common.packets.FullscreenState
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.net.ProtocolRouter
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies incoming [FullscreenState] snapshots to the matching [DisplayScreen], opening or closing the fullscreen overlay
 * as sessions start and stop.
 */
object FullscreenController {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val PENDING_TIMEOUT_MS = 5_000L
    private const val READY_GRACE_MS = 3_000L

    private class Pending(val state: FullscreenState, val queuedAtMs: Long, var deadlineMs: Long = 0L)

    private val pending = ConcurrentHashMap<UUID, Pending>()

    /** Applies [state]: opens the fullscreen overlay when active, tears it down otherwise. */
    fun handle(state: FullscreenState) {
        if (!state.active) {
            pending.remove(state.displayId)
            DisplayRegistry.screens[state.displayId]?.let {
                it.lastFullscreenState = null
                it.deactivateFullscreen()
            }
            return
        }
        val screen = DisplayRegistry.screens[state.displayId]
        if (screen == null || !isClientReady()) {
            pending[state.displayId] = Pending(state, System.currentTimeMillis())
            return
        }
        apply(screen, state)
    }

    /** Retries pending states whose display has since loaded, and gives up on ones that timed out. Called once per client tick. */
    fun onClientTick() {
        if (pending.isEmpty()) return
        val ready = isClientReady()
        val now = System.currentTimeMillis()
        for ((displayId, entry) in pending.entries.toList()) {
            val screen = DisplayRegistry.screens[displayId]
            val waitedOut = now - entry.queuedAtMs >= READY_GRACE_MS
            if (screen != null && (ready || waitedOut)) {
                pending.remove(displayId)
                if (!ready) logger.debug("Showing fullscreen for {} before the world settled.", displayId)
                apply(screen, entry.state)
                continue
            }
            if (!ready && !waitedOut) {
                entry.deadlineMs = 0L
            } else if (entry.deadlineMs == 0L) {
                entry.deadlineMs = now + PENDING_TIMEOUT_MS
            } else if (entry.deadlineMs < now) {
                pending.remove(displayId)
                logger.debug("Dropping fullscreen state for {}: display never loaded.", displayId)
            }
        }
    }

    private fun isClientReady(): Boolean = runCatching {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return@runCatching false
        val level = mc.level ?: return@runCatching false
        level.isLoaded(player.blockPosition())
    }.getOrDefault(false)

    private fun apply(screen: DisplayScreen, state: FullscreenState) {
        if (screen.lastFullscreenState == state) {
            val action = if (state.minimized) FullscreenAckAction.MINIMIZED else FullscreenAckAction.SHOWN
            ProtocolRouter.send(FullscreenAck(state.sessionId, action.wire))
            return
        }
        screen.lastFullscreenState = state
        screen.activateFullscreenMode(FullscreenMode.fromWire(state.mode), state.forced, state.sessionId, state.loop)
        if (state.volume >= 0f) screen.volume = state.volume.coerceIn(0f, 1f)
        if (state.quality.isNotEmpty()) screen.quality = VideoQuality.parse(state.quality)
        if (state.minimized) screen.minimizeFullscreenToPip()
        val action = if (state.minimized) FullscreenAckAction.MINIMIZED else FullscreenAckAction.SHOWN
        ProtocolRouter.send(FullscreenAck(state.sessionId, action.wire))
    }

    /** Drops pending state on disconnect. */
    fun reset() {
        pending.clear()
    }
}
