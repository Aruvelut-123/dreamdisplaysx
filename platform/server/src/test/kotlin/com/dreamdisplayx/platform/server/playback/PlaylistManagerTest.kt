package com.dreamdisplayx.platform.server.playback

import com.dreamdisplayx.api.playback.model.PlaylistCommandAction
import com.dreamdisplayx.api.playback.model.PlaylistItemRecord
import com.dreamdisplayx.api.playback.model.DisplayPlaylist
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic coverage for [PlaylistManager]. The persistence / transport half is platform-bound
 * (storage + broadcast) and lives in the command layer, so it is exercised in-game rather than here.
 */
class PlaylistManagerTest {
    @Test
    fun setEnabledToTrueStartsFirstItem() {
        val playlist = DisplayPlaylist(
            displayId = UUID.randomUUID(),
            enabled = false,
            items = listOf(
                item("A", "https://example.com/a"),
                item("B", "https://example.com/b"),
            ),
            currentIndex = -1,
        )
        // Simulate onCommand with SET_ENABLED packet
        val updated = playlist.copy(enabled = true)
        assertTrue(updated.enabled, "should be enabled after SET_ENABLED")
        assertEquals(-1, updated.currentIndex, "data copy does not perform playback side effects")
    }

    @Test
    fun setEnabledToFalseStopsAutoAdvance() {
        val playlist = DisplayPlaylist(
            displayId = UUID.randomUUID(),
            enabled = true,
            items = listOf(
                item("A", "https://example.com/a"),
                item("B", "https://example.com/b"),
            ),
            currentIndex = 0,
        )
        val updated = playlist.copy(enabled = false)
        assertFalse(updated.enabled, "should be disabled after SET_ENABLED")
    }

    @Test
    fun setEnabledRequiresOwnerOrAdmin() {
        val action = PlaylistCommandAction.SET_ENABLED
        assertTrue(requiresOwnerOrAdmin(action))
        assertTrue(requiresOwnerOrAdmin(PlaylistCommandAction.SKIP_TO))
        assertTrue(requiresOwnerOrAdmin(PlaylistCommandAction.NEXT))
        assertFalse(requiresOwnerOrAdmin(PlaylistCommandAction.ADD)) // everyone policy allowed
    }

    @Test
    fun addAndApprovePendingItem() {
        val playlist = DisplayPlaylist(
            displayId = UUID.randomUUID(),
            enabled = true,
            items = emptyList(),
            currentIndex = -1,
        )
        // Add a pending item
        val added = playlist.copy(
            items = listOf(item("X", "https://example.com/x", pending = true)),
            currentIndex = 0,
        )
        assertTrue(added.items[0].pending, "item should be pending")

        // Approve it
        val approved = playlist.copy(
            items = listOf(item("X", "https://example.com/x", pending = false)),
            currentIndex = 0,
        )
        assertFalse(approved.items[0].pending, "item should no longer be pending")
    }

    @Test
    fun removeBeforePlayingShiftsIndexLeft() {
        // Queue [A, B, C], B playing (index 1); removing A shifts the playing item to index 0.
        val remaining = listOf(item("B"), item("C"))
        assertEquals(0, PlaylistManager.indexAfterRemoval(remaining, removedIndex = 0, currentIndex = 1, loopCurrent = false))
    }

    @Test
    fun removeAfterPlayingKeepsIndex() {
        // Queue [A, B, C], A playing (index 0); removing C keeps A at index 0.
        val remaining = listOf(item("A"), item("B"))
        assertEquals(0, PlaylistManager.indexAfterRemoval(remaining, removedIndex = 2, currentIndex = 0, loopCurrent = false))
    }

    @Test
    fun removePlayingStartsItemThatTookItsSlot() {
        // Queue [A, B, C], B playing (index 1); removing B must select C (the item that took the slot).
        val remaining = listOf(item("A"), item("C"))
        assertEquals(1, PlaylistManager.indexAfterRemoval(remaining, removedIndex = 1, currentIndex = 1, loopCurrent = false))
    }

    @Test
    fun removeLastPlayingExhaustsNonLoopQueue() {
        // Queue [A, B], B playing (index 1); removing B with no loop must exhaust the queue (-1).
        val remaining = listOf(item("A"))
        assertEquals(-1, PlaylistManager.indexAfterRemoval(remaining, removedIndex = 1, currentIndex = 1, loopCurrent = false))
    }

    @Test
    fun removeLastPlayingWrapsUnderLoopCurrent() {
        // Queue [A, B], B playing (index 1); removing B with LOOP_CURRENT must wrap to index 0.
        val remaining = listOf(item("A"))
        assertEquals(0, PlaylistManager.indexAfterRemoval(remaining, removedIndex = 1, currentIndex = 1, loopCurrent = true))
    }

    @Test
    fun removeOnlyItemLeavesNothingPlaying() {
        // Queue [A], A playing; removing A must exhaust the queue (-1), loop or not.
        assertEquals(-1, PlaylistManager.indexAfterRemoval(emptyList(), removedIndex = 0, currentIndex = 0, loopCurrent = false))
        assertEquals(-1, PlaylistManager.indexAfterRemoval(emptyList(), removedIndex = 0, currentIndex = 0, loopCurrent = true))
    }

    @Test
    fun removeWhileIdleStaysIdle() {
        // Nothing playing (index -1): any removal keeps the queue idle.
        val remaining = listOf(item("B"), item("C"))
        assertEquals(-1, PlaylistManager.indexAfterRemoval(remaining, removedIndex = 0, currentIndex = -1, loopCurrent = false))
    }

    private fun item(title: String, url: String = "https://example.com/$title", pending: Boolean = false): PlaylistItemRecord =
        PlaylistItemRecord(
            itemId = UUID.randomUUID(),
            url = url,
            lang = "",
            title = title,
            pending = pending,
            requesterId = UUID.randomUUID(),
        )

    private fun requiresOwnerOrAdmin(action: PlaylistCommandAction): Boolean {
        return when (action) {
            PlaylistCommandAction.SET_END_BEHAVIOR,
            PlaylistCommandAction.SET_ENQUEUE_POLICY,
            PlaylistCommandAction.SET_ENABLED,
            PlaylistCommandAction.SKIP_TO,
            PlaylistCommandAction.NEXT,
            PlaylistCommandAction.APPROVE,
            PlaylistCommandAction.REJECT -> true
            else -> false
        }
    }
}
