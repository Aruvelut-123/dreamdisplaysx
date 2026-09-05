package com.dreamdisplayx.platform.server.managers

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic coverage for the platform-free group membership store. The persistence / broadcast
 * half of [DisplayGroupManager] is platform-bound (storage + transport) and lives in the command
 * layer, so it is exercised in-game rather than here.
 */
class GroupRegistryTest {
    @Test
    fun createAddRemoveDeleteLifecycle() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val group = "lifecycle-${UUID.randomUUID()}"

        assertTrue(GroupRegistry.create(group))
        assertFalse(GroupRegistry.create(group), "duplicate create must fail")
        assertTrue(GroupRegistry.contains(group))

        assertTrue(GroupRegistry.add(group, first))
        assertTrue(GroupRegistry.add(group, second))
        assertFalse(GroupRegistry.add(group, first), "re-adding the same member must fail")
        assertEquals(setOf(first, second), GroupRegistry.memberIds(group))

        assertTrue(GroupRegistry.remove(group, first))
        assertFalse(GroupRegistry.remove(group, first), "removing a non-member must fail")
        assertEquals(setOf(second), GroupRegistry.memberIds(group))

        assertTrue(GroupRegistry.delete(group))
        assertFalse(GroupRegistry.contains(group))
    }

    @Test
    fun addAndRemoveOnMissingGroupFail() {
        val ghost = "missing-${UUID.randomUUID()}"
        assertFalse(GroupRegistry.add(ghost, UUID.randomUUID()))
        assertFalse(GroupRegistry.remove(ghost, UUID.randomUUID()))
    }

    @Test
    fun memberIdsIsDetachedFromInternalState() {
        val group = "detach-${UUID.randomUUID()}"
        val member = UUID.randomUUID()
        GroupRegistry.create(group)
        GroupRegistry.add(group, member)

        val snapshot = GroupRegistry.memberIds(group)
        GroupRegistry.delete(group)
        assertEquals(setOf(member), snapshot, "returned set must be a copy, not a live view")
    }

    @Test
    fun namesAreSorted() {
        val alpha = "alpha-${UUID.randomUUID()}"
        val bravo = "bravo-${UUID.randomUUID()}"
        GroupRegistry.create(bravo)
        GroupRegistry.create(alpha)
        try {
            val names = GroupRegistry.names()
            assertTrue(names.containsAll(listOf(alpha, bravo)))
            assertEquals(names.sorted(), names, "names() must return a sorted snapshot")
        } finally {
            GroupRegistry.delete(alpha)
            GroupRegistry.delete(bravo)
        }
    }
}
