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
 * The queue drives the display through [PlaybackTransport.notifyVideoChanged] / [TimelineManager.applyScheduled],
 * so the authoritative timeline, throttles, and same-content grouping all keep working unchanged.
 */
object PlaylistManager {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/PlaylistManager")

    /** Zero UUID used by protobuf defaults for absent optional item identities. */
    private val ZERO_UUID = UUID(0L, 0L)

    /** Live platform transport, injected at startup. */
    private lateinit var transport: PlaybackTransport

    /** The current in-memory playlist for each display. */
    private val playlists = ConcurrentHashMap<UUID, DisplayPlaylist>()

    /** Monotonic playback generations; separate from queue edits so ADD/MOVE do not retrigger media. */
    private val playRevisions = ConcurrentHashMap<UUID, Long>()

    /** Wires the platform transport and seeds in-memory playlists from persisted rows. */
    fun init(transport: PlaybackTransport, persisted: List<DisplayPlaylist>) {
        this.transport = transport
        playRevisions.clear()
        persisted.forEach { playlists[it.displayId] = it }
    }

    /** The live playlist for [displayId], or null when it has none. */
    fun playlistOf(displayId: UUID): DisplayPlaylist? = playlists[displayId]

    /** Drops the playlist for a deleted display, both in memory and from the database. */
    fun onDisplayRemoved(displayId: UUID) {
        playlists.remove(displayId)
        playRevisions.remove(displayId)
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

        logger.debug(
            "playlist {} from {} (owner={}, admin={}) for display {}",
            action, senderName, isOwner, isSenderAdmin, display.id,
        )

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
                if (!mayDirect && !(policy == PlaylistEnqueuePolicy.OWNER_APPROVAL)) {
                    logger.warn(
                        "rejected playlist ADD from {} for display {}: enqueuePolicy={}, isOwner={}, isAdmin={}",
                        senderName, display.id, policy, isOwner, isSenderAdmin,
                    )
                    return false
                }
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
                // Keep the same current item selected after insertion, irrespective of whether the
                // inserted row is pending or approved immediately.
                playlist.copy(
                    items = items,
                    currentIndex = remapCurrentIndex(playlist.items, items, playlist.currentIndex),
                )
            }

            PlaylistCommandAction.REMOVE -> {
                val index = playlist.items.indexOfFirst { it.itemId == packet.itemId }
                if (index < 0) return false
                val items = playlist.items.toMutableList()
                items.removeAt(index)
                val nextIndex = indexAfterRemoval(
                    items, index, playlist.currentIndex,
                    playlist.endBehavior == PlaylistEndBehavior.LOOP_CURRENT,
                )
                if (playlist.currentIndex >= 0 && index == playlist.currentIndex) {
                    // The playing item left the queue: actually SWITCH playback to the item that
                    // took its slot instead of only remapping the index (which used to leave the
                    // removed video on screen and desync it from the queue bookkeeping).
                    val updated = playlist.copy(items = items, currentIndex = nextIndex)
                    playlists[display.id] = updated
                    if (nextIndex >= 0) {
                        playIndex(display, updated, nextIndex) // persists + broadcasts
                    } else {
                        // Queue exhausted: the player may keep its last frame, but the playlist is
                        // explicitly idle so a later ADD restarts through the idle rule.
                        persist(display.id)
                        broadcast(display.id)
                    }
                    return true
                }
                playlist.copy(items = items, currentIndex = nextIndex)
            }

            PlaylistCommandAction.MOVE -> {
                val from = playlist.items.indexOfFirst { it.itemId == packet.itemId }
                if (from < 0) return false
                val items = playlist.items.toMutableList()
                val record = items.removeAt(from)
                val to = packet.position.coerceIn(0, items.size)
                items.add(to, record)
                // Remap by the stable current item identity rather than by positional heuristics;
                // this remains correct when the moved row crosses pending rows or the current slot.
                playlist.copy(
                    items = items,
                    currentIndex = remapCurrentIndex(playlist.items, items, playlist.currentIndex),
                )
            }

            PlaylistCommandAction.CLEAR -> playlist.copy(items = emptyList(), currentIndex = -1)

            PlaylistCommandAction.SKIP_TO -> {
                val index = playlist.items.indexOfFirst { it.itemId == packet.itemId }
                if (index < 0 || playlist.items[index].pending) return false
                playIndex(display, playlist, index)
                return true // playIndex already persisted + broadcast
            }

            PlaylistCommandAction.NEXT -> {
                // Automatic EOS advances carry the play revision and current item identity. Reject a
                // stale callback after another client/player already started the next item; otherwise
                // two owner/admin viewers can advance A -> B -> C before either echo is observed.
                val currentRevision = playRevisions[display.id] ?: 0L
                if (!acceptsExpectedNext(playlist, currentRevision, packet)) return false
                val next = nextPlayableIndex(
                    playlist.items,
                    playlist.currentIndex,
                    playlist.endBehavior == PlaylistEndBehavior.LOOP_CURRENT,
                ) ?: return false
                playIndex(display, playlist, next)
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
                // Approval changes playability, not list order: currentIndex must continue to refer
                // to the same stable item (or remain idle), never jump merely because a row became live.
                playlist.copy(items = items)
            }

            PlaylistCommandAction.REJECT -> {
                val index = playlist.items.indexOfFirst { it.itemId == packet.itemId && it.pending }
                if (index < 0) return false
                val items = playlist.items.toMutableList()
                items.removeAt(index)
                playlist.copy(
                    items = items,
                    currentIndex = remapCurrentIndex(playlist.items, items, playlist.currentIndex),
                )
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
     * Checks the optimistic-concurrency token carried by an automatic NEXT request.
     * Unrestricted manual NEXT requests retain the legacy -1/zero defaults.
     */
    internal fun acceptsExpectedNext(
        playlist: DisplayPlaylist,
        currentRevision: Long,
        packet: PlaylistCommand,
    ): Boolean {
        if (packet.expectedPlayRevision >= 0L && packet.expectedPlayRevision != currentRevision) return false
        return packet.expectedItemId == ZERO_UUID ||
            playlist.items.getOrNull(playlist.currentIndex)?.itemId == packet.expectedItemId
    }

    /**
     * Computes the playing index after removing the item at [removedIndex] from [remaining].
     * Pure function so the bookkeeping rules are unit-testable:
     * - nothing playing → stay -1
     * - removal before the playing index shifts it one slot left
     * - removal OF the playing item selects the next non-pending item, wrapping only when requested
     * - otherwise the playing index is unchanged
     */
    fun indexAfterRemoval(
        remaining: List<PlaylistItemRecord>,
        removedIndex: Int,
        currentIndex: Int,
        loopCurrent: Boolean,
    ): Int {
        if (currentIndex < 0) return -1
        if (removedIndex < currentIndex) return currentIndex - 1
        if (removedIndex > currentIndex) return currentIndex
        return nextPlayableIndex(remaining, removedIndex - 1, loopCurrent) ?: -1
    }

    /**
     * Finds the next playable queue entry after [currentIndex], skipping pending rows. When
     * [loopCurrent] is true, the search wraps to the beginning and may select the current row again
     * when it is the only playable item. A null result means the queue has no playable entry.
     */
    fun nextPlayableIndex(
        items: List<PlaylistItemRecord>,
        currentIndex: Int,
        loopCurrent: Boolean,
    ): Int? {
        if (items.isEmpty()) return null
        val normalized = currentIndex.coerceIn(-1, items.lastIndex)
        for (index in (normalized + 1) until items.size) {
            if (!items[index].pending) return index
        }
        if (!loopCurrent) return null
        for (index in 0..normalized) {
            if (!items[index].pending) return index
        }
        return null
    }

    /** Returns the index of the same stable item in [after], or -1 when no item is playing. */
    internal fun remapCurrentIndex(
        before: List<PlaylistItemRecord>,
        after: List<PlaylistItemRecord>,
        currentIndex: Int,
    ): Int {
        if (currentIndex !in before.indices) return -1
        val currentItemId = before[currentIndex].itemId
        return after.indexOfFirst { it.itemId == currentItemId }
    }

    /** Advances the wire playback generation without coupling it to queue edits. */
    internal fun advancePlayRevision(previous: Long?): Long = (previous ?: 0L) + 1L

    /**
     * Plays [index] immediately: loads the item's URL onto the display through the normal
     * set-video pipeline (permissions/throttles bypassed — the queue is owner-scoped already),
     * updates the playing index, persists, and broadcasts.
     */
    fun playIndex(display: DisplayData, playlist: DisplayPlaylist, index: Int) {
        val item = playlist.items.getOrNull(index) ?: return
        if (item.pending) {
            logger.debug("Ignoring attempt to play pending playlist item {} on display {}.", item.itemId, display.id)
            return
        }
        playRevisions.compute(display.id) { _, previous -> advancePlayRevision(previous) }
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
            // A user may play a direct URL while a playlist is still present. Never let the old
            // queue reclaim that unrelated media when its authoritative timeline later reaches EOS.
            if (display.url != item.url || display.lang != item.lang) continue
            if (display.mode != com.dreamdisplayx.api.playback.model.PlaybackMode.SYNCED &&
                display.mode != com.dreamdisplayx.api.playback.model.PlaybackMode.BROADCAST
            ) continue
            val durationMs = display.duration?.let { it / 1_000_000L } ?: 0L
            if (durationMs <= 0) continue
            val timeline = TimelineManager.timelineOf(displayId) ?: continue
            if (timeline.paused) continue
            // Raw (un-wrapped) position: a looping SYNCED / BROADCAST timeline wraps its public
            // position at the duration, so positionAt never crosses the completion threshold and
            // the queue would sit on the last item forever. rawPositionAt keeps growing past the
            // duration so the end-of-item check below fires exactly once per item.
            val positionMs = timeline.rawPositionAt(transport.nowMs())
            // Small grace so we don't cut the tail early on duration rounding.
            if (positionMs < durationMs + 1_500L) continue

            when (playlist.endBehavior) {
                PlaylistEndBehavior.PAUSE -> {
                    TimelineManager.applyScheduled(display, com.dreamdisplayx.api.playback.model.PlaybackAction.PAUSE)
                }

                PlaylistEndBehavior.CONTINUE -> {
                    val next = nextPlayableIndex(playlist.items, index, loopCurrent = false)
                    if (next != null) {
                        playIndex(display, playlist, next)
                    } else {
                        TimelineManager.applyScheduled(display, com.dreamdisplayx.api.playback.model.PlaybackAction.PAUSE)
                    }
                }

                PlaylistEndBehavior.LOOP_CURRENT -> {
                    val next = nextPlayableIndex(playlist.items, index, loopCurrent = true)
                    if (next != null) playIndex(display, playlist, next)
                    else TimelineManager.applyScheduled(display, com.dreamdisplayx.api.playback.model.PlaybackAction.PAUSE)
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
            playRevision = playRevisions[displayId] ?: 0L,
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
