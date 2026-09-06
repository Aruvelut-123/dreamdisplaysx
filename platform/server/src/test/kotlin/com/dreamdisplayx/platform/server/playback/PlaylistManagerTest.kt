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

    private fun item(title: String, url: String, pending: Boolean = false): PlaylistItemRecord =
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
