package com.dreamdisplayx.platform.server.playback

import com.dreamdisplayx.api.playback.model.DisplayPlaylist
import com.dreamdisplayx.api.playback.model.PlaylistCommandAction
import com.dreamdisplayx.api.playback.model.PlaylistEndBehavior
import com.dreamdisplayx.api.playback.model.PlaylistEnqueuePolicy
import com.dreamdisplayx.api.playback.model.PlaylistItemRecord
import com.dreamdisplayx.core.protocol.common.packets.PlaylistCommand
import com.dreamdisplayx.core.protocol.common.packets.PlaylistItem
import com.dreamdisplayx.core.protocol.common.packets.PlaylistState
import com.dreamdisplayx.platform.server.datatypes.display.DisplayData
import com.dreamdisplayx.platform.server.managers.DisplayManager
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the per-display playback queue. Items persist in the server database (SQLite / MySQL) via the
 * storage backend, and every mutation is echoed back as a [PlaylistState] snapshot so clients render
 * the queue directly from server truth.
 *
 * The queue drives the display through [TimelineManager.onVideoChanged] / [applyScheduled], so the
 * authoritative timeline, throttles, and same-content grouping all keep working unchanged.
 */
object PlaylistManager {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/PlaylistManager")

    /** Live platform transport, injected at startup. */
    private lateinit var transport: PlaybackTransport

    /** The current in-memory playlist for each display. */
    private val playlists = ConcurrentHashMap<UUID, DisplayPlaylist>()

    /** Wires the platform transport and seeds in-memory playlists from persisted rows. */
    fun init(transport: PlaybackTransport, persisted: List<DisplayPlaylist>) {
        this.transport = transport
        persisted.forEach { playlists[it.displayId] = it }
    }

    /** The live playlist for [displayId], or null when it has none. */
    fun playlistOf(displayId: UUID): DisplayPlaylist? = playlists[displayId]

    /** Drops the playlist for a deleted display, both in memory and from the database. */
    fun onDisplayRemoved(displayId: UUID) {
        playlists.remove(displayId)
        deletePersisted(displayId)
    }

    /**
     * Handles a client [PlaylistCommand]. Returns true when the queue changed (so callers may
     * persist); every accepted mutation broadcasts a fresh snapshot to nearby players.
     */
    fun onCommand(senderId: UUID, senderName: String, isSenderAdmin: Boolean, packet: PlaylistCommand): Boolean {
        val display = DisplayManager.getDisplayData(packet.displayId) ?: return false
        if (senderId !in transport.nearbyPlayerIds(display)) return false
        val playlist = playlists[display.id] ?: DisplayPlaylist(displayId = display.id)

        val isOwner = senderId == display.ownerId
        val action = PlaylistCommandAction.fromWire(packet.action) ?: return false

        // Policy gates: settings changes are owner/admin only; skip controls are owner/admin;
        // item mutations depend on the display's enqueue policy.
        when (action) {
            PlaylistCommandAction.SET_END_BEHAVIOR,
            PlaylistCommandAction.SET_ENQUEUE_POLICY,
            PlaylistCommandAction.SET_ENABLED,
            -> if (!isOwner && !isSenderAdmin) return false

            PlaylistCommandAction.SKIP_TO,
            PlaylistCommandAction.NEXT,
            -> if (!isOwner && !isSenderAdmin) return false

            PlaylistCommandAction.ADD -> {
                val policy = playlist.enqueuePolicy
                val mayDirect = isOwner || isSenderAdmin ||
                    policy == PlaylistEnqueuePolicy.EVERYONE
                if (!mayDirect && !(policy == PlaylistEnqueuePolicy.OWNER_APPROVAL)) return false
            }

            PlaylistCommandAction.APPROVE,
            PlaylistCommandAction.REJECT,
            -> if (!isOwner && !isSenderAdmin) return false

            PlaylistCommandAction.REMOVE,
            PlaylistCommandAction.MOVE,
            PlaylistCommandAction.CLEAR,
            -> if (!isOwner && !isSenderAdmin) {
                // Non-owners may only remove their own pending (unapproved) items.
                if (action != PlaylistCommandAction.REMOVE) return false
                val item = playlist.items.firstOrNull { it.itemId == packet.itemId } ?: return false
                if (!(item.pending && item.requesterId == senderId)) return false
            }
        }

        val updated = when (action) {
            PlaylistCommandAction.ADD -> {
                val url = packet.url.trim()
                if (url.isEmpty()) return false
                val pending = playlist.enqueuePolicy == PlaylistEnqueuePolicy.OWNER_APPROVAL &&
                    !isOwner && !isSenderAdmin
                val item = PlaylistItemRecord(
                    itemId = UUID.randomUUID(),
                    url = url,
                    lang = packet.lang.trim(),
                    title = packet.title.trim().take(255),
                    pending = pending,
                    requesterId = senderId,
                    requesterName = senderName.trim().take(32),
                )
                val items = playlist.items.toMutableList()
                val at = if (packet.position in 0..items.size) packet.position else items.size
                items.add(at, item)
                // A pending insertion at/after the playing index shifts nothing visible until approved,
                // but approved insertions before the playing index shift it.
                playlist.copy(items = items).let {
                    if (!pending && at <= playlist.currentIndex) {
                        it.copy(currentIndex = playlist.currentIndex + 1)
                    } else {
                        it
                    }
                }
            }

            PlaylistCommandAction.REMOVE -> {
                val index = playlist.items.indexOfFirst { it.itemId == packet.itemId }
                if (index < 0) return false
                val items = playlist.items.toMutableList()
                items.removeAt(index)
                val nextIndex = when {
                    playlist.currentIndex < 0 -> -1
                    index < playlist.currentIndex -> playlist.currentIndex - 1
                    index == playlist.currentIndex -> {
                        // The playing item left the queue: keep playing the item that took its slot.
                        if (index < items.size) index else if (items.isNotEmpty() && playlist.endBehavior == PlaylistEndBehavior.LOOP_CURRENT) 0 else items.size - 1
                    }
                    else -> playlist.currentIndex
                }
                playlist.copy(items = items, currentIndex = nextIndex.coerceAtMost(items.size - 1))
            }

            PlaylistCommandAction.MOVE -> {
                val from = playlist.items.indexOfFirst { it.itemId == packet.itemId }
                if (from < 0) return false
                val items = playlist.items.toMutableList()
                val record = items.removeAt(from)
                val to = packet.position.coerceIn(0, items.size)
                items.add(to, record)
                // Remap the playing index across the same move applied to it.
                val currentIndex = when {
                    playlist.currentIndex == from -> to
                    from < playlist.currentIndex && to >= playlist.currentIndex -> playlist.currentIndex - 1
                    to <= playlist.currentIndex && from > playlist.currentIndex -> playlist.currentIndex + 1
                    else -> playlist.currentIndex
                }
                playlist.copy(items = items, currentIndex = currentIndex)
            }

            PlaylistCommandAction.CLEAR -> playlist.copy(items = emptyList(), currentIndex = -1)

            PlaylistCommandAction.SKIP_TO -> {
                val index = playlist.items.indexOfFirst { it.itemId == packet.itemId }
                if (index < 0) return false
                playIndex(display, playlist, index)
                return true // playIndex already persisted + broadcast
            }

            PlaylistCommandAction.NEXT -> {
                val next = playlist.currentIndex + 1
                if (next >= playlist.items.size &&
                    playlist.endBehavior != PlaylistEndBehavior.LOOP_CURRENT
                ) {
                    return false
                }
                playIndex(display, playlist, if (next < playlist.items.size) next else 0)
                return true
            }

            PlaylistCommandAction.SET_END_BEHAVIOR ->
                playlist.copy(endBehavior = PlaylistEndBehavior.fromWire(packet.endBehavior))

            PlaylistCommandAction.SET_ENQUEUE_POLICY ->
                playlist.copy(enqueuePolicy = PlaylistEnqueuePolicy.fromWire(packet.enqueuePolicy))

            PlaylistCommandAction.SET_ENABLED ->
                playlist.copy(enabled = packet.enabled)

            PlaylistCommandAction.APPROVE -> {
                val index = playlist.items.indexOfFirst { it.itemId == packet.itemId && it.pending }
                if (index < 0) return false
                val items = playlist.items.toMutableList()
                items[index] = items[index].copy(pending = false)
                playlist.copy(items = items).let {
                    if (index <= it.currentIndex) it.copy(currentIndex = it.currentIndex + 1) else it
                }
            }

            PlaylistCommandAction.REJECT -> {
                val index = playlist.items.indexOfFirst { it.itemId == packet.itemId && it.pending }
                if (index < 0) return false
                val items = playlist.items.toMutableList()
                items.removeAt(index)
                playlist.copy(items = items)
            }
        }

        playlists[display.id] = updated
        // Playlist mode with an idle queue: the first enqueued (non-pending) item starts playing
        // right away, mirroring the direct-pick behaviour users expect from a queue start.
        if (updated.enabled && updated.currentIndex < 0) {
            val firstPlayable = updated.items.indexOfFirst { !it.pending }
            if (firstPlayable >= 0) {
                playIndex(display, updated, firstPlayable)
                return true // playIndex persisted + broadcast already
            }
        }
        persist(display.id)
        broadcast(display.id)
        return true
    }

    /**
     * Plays [index] immediately: loads the item's URL onto the display through the normal
     * set-video pipeline (permissions/throttles bypassed — the queue is owner-scoped already),
     * updates the playing index, persists, and broadcasts.
     */
    fun playIndex(display: DisplayData, playlist: DisplayPlaylist, index: Int) {
        val item = playlist.items.getOrNull(index) ?: return
        playlists[display.id] = playlist.copy(currentIndex = index)
        persist(display.id)
        display.url = item.url
        display.lang = item.lang
        display.duration = null
        display.seekPositionNanos = 0L
        transport.notifyVideoChanged(display)
        transport.saveDisplay(display)
        broadcast(display.id)
    }

    /**
     * Periodic completion check: when the authoritative timeline has run past the known duration of
     * the current item, advance per [PlaylistEndBehavior]. Called once per second alongside
     * [TimelineManager.tick].
     */
    fun tick() {
        if (playlists.isEmpty()) return
        for ((displayId, playlist) in playlists) {
            if (!playlist.enabled) continue
            val index = playlist.currentIndex
            if (index < 0 || index >= playlist.items.size) continue
            val item = playlist.items[index]
            if (item.pending) continue
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            if (display.mode != com.dreamdisplayx.api.playback.model.PlaybackMode.SYNCED &&
                display.mode != com.dreamdisplayx.api.playback.model.PlaybackMode.BROADCAST
            ) continue
            val durationMs = display.duration?.let { it / 1_000_000L } ?: 0L
            if (durationMs <= 0) continue
            val timeline = TimelineManager.timelineOf(displayId) ?: continue
            if (timeline.paused) continue
            val positionMs = timeline.positionAt(transport.nowMs())
            // Small grace so we don't cut the tail early on duration rounding.
            if (positionMs < durationMs + 1_500L) continue

            when (playlist.endBehavior) {
                PlaylistEndBehavior.PAUSE -> {
                    TimelineManager.applyScheduled(display, com.dreamdisplayx.api.playback.model.PlaybackAction.PAUSE)
                }

                PlaylistEndBehavior.CONTINUE -> {
                    val next = index + 1
                    if (next < playlist.items.size) {
                        playIndex(display, playlist, next)
                    } else {
                        TimelineManager.applyScheduled(display, com.dreamdisplayx.api.playback.model.PlaybackAction.PAUSE)
                    }
                }

                PlaylistEndBehavior.LOOP_CURRENT -> {
                    val next = if (index + 1 < playlist.items.size) index + 1 else 0
                    playIndex(display, playlist, next)
                }
            }
        }
    }

    /** Builds the wire snapshot for [displayId], or null when it has no playlist. */
    fun stateFor(displayId: UUID): PlaylistState? {
        val playlist = playlists[displayId] ?: return null
        return PlaylistState(
            displayId = playlist.displayId,
            currentIndex = playlist.currentIndex,
            endBehavior = playlist.endBehavior.wire,
            enqueuePolicy = playlist.enqueuePolicy.wire,
            enabled = playlist.enabled,
            items = playlist.items.map { item ->
                PlaylistItem(
                    itemId = item.itemId,
                    url = item.url,
                    lang = item.lang,
                    title = item.title,
                    pending = item.pending,
                    requesterId = item.requesterId,
                    requesterName = item.requesterName,
                )
            },
        )
    }

    /** Sends the current snapshot for [displayId] to one player (menu-open catch-up). */
    fun sendTo(displayId: UUID, playerId: UUID) {
        val state = stateFor(displayId) ?: return
        transport.sendTo(playerId, state)
    }

    /** Broadcasts the current snapshot for [displayId] to every nearby player. */
    fun broadcast(displayId: UUID) {
        val display = DisplayManager.getDisplayData(displayId) ?: return
        val state = stateFor(displayId) ?: return
        transport.broadcast(display, state)
    }

    /** Persists the live playlist for [displayId] through the platform storage backend. */
    private fun persist(displayId: UUID) {
        val playlist = playlists[displayId] ?: return
        runCatching { transport.savePlaylist(playlist) }
            .onFailure { logger.warn("Failed to persist playlist for display {}.", displayId, it) }
    }

    /** Drops [displayId]'s playlist rows through the platform storage backend. */
    private fun deletePersisted(displayId: UUID) {
        runCatching { transport.deletePlaylist(displayId) }
            .onFailure { logger.warn("Failed to delete playlist for display {}.", displayId, it) }
    }
}
