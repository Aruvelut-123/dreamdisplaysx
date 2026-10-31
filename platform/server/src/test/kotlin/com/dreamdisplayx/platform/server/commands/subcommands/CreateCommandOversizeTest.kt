package com.dreamdisplayx.platform.server.commands.subcommands

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [isOversized], the early rejection that stops `/display create` from walking the surface
 * census of a selection that can never be accepted.
 *
 * Regression guard for issue #272: a selection used to be measured block by block and only rejected
 * afterwards, so dragging a huge region blocked the server thread for no benefit. The check also has
 * to stay permissive for legitimate selections, otherwise it would reject valid displays early.
 */
class CreateCommandOversizeTest {

    @Test
    fun `a selection inside the limits is accepted`() {
        assertFalse(isOversized(width = 5, height = 5, depth = 1, maxWidth = 10, maxHeight = 10))
    }

    @Test
    fun `a selection exactly at the limit is still accepted`() {
        // Off-by-one here would reject the largest display the config allows.
        assertFalse(isOversized(width = 10, height = 10, depth = 10, maxWidth = 10, maxHeight = 10))
    }

    @Test
    fun `a wider selection is rejected`() {
        assertTrue(isOversized(width = 11, height = 5, depth = 1, maxWidth = 10, maxHeight = 10))
    }

    @Test
    fun `a taller selection is rejected`() {
        assertTrue(isOversized(width = 5, height = 11, depth = 1, maxWidth = 10, maxHeight = 10))
    }

    @Test
    fun `depth is bounded by the larger of the two limits`() {
        // Conforming displays may be deeper than wide (stairs and slabs), so depth uses the max of
        // the two limits rather than maxWidth alone.
        assertTrue(isOversized(width = 5, height = 5, depth = 11, maxWidth = 10, maxHeight = 10))
        assertFalse(isOversized(width = 5, height = 5, depth = 10, maxWidth = 5, maxHeight = 10))
    }
}