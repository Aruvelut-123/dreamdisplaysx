package com.dreamdisplayx.platform.client.input

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [ModKeyMappings]' routing contract.
 *
 * This object is the seam that decides whether a press bound in `Options -> Controls` reaches an
 * action, and it is pure JVM precisely so it can be tested without a game. The cases below are the
 * ones that break similar bridges in practice.
 */
class ModKeyMappingsTest {
    @BeforeTest
    fun setUp() {
        ModKeyMappings.reset()
    }

    @AfterTest
    fun tearDown() {
        ModKeyMappings.reset()
    }

    @Test
    fun `unregistered binding reports no press and no attachment`() {
        val id = DisplayHotkeys.TOGGLE_PLAYBACK_ID

        assertFalse(ModKeyMappings.consume(id), "an unknown id must never look like a press")
        assertFalse(ModKeyMappings.isAttached(id))
        assertEquals(0, ModKeyMappings.size)
        assertTrue(ModKeyMappings.attachedIds().isEmpty())
    }

    @Test
    fun `attached binding forwards presses to its poller`() {
        var calls = 0
        ModKeyMappings.attach(DisplayHotkeys.TOGGLE_PLAYBACK_ID) {
            calls++
            true
        }

        assertTrue(ModKeyMappings.isAttached(DisplayHotkeys.TOGGLE_PLAYBACK_ID))
        assertEquals(1, ModKeyMappings.size)
        assertTrue(ModKeyMappings.consume(DisplayHotkeys.TOGGLE_PLAYBACK_ID))
        assertEquals(1, calls)
    }

    @Test
    fun `attach replaces instead of stacking pollers`() {
        // A dev runClient relaunch in the same JVM calls attach twice. If that accumulated, one press
        // would fire a play/pause toggle twice and cancel itself out.
        var first = 0
        var second = 0
        val id = DisplayHotkeys.TOGGLE_DISPLAYS_ID
        ModKeyMappings.attach(id) { first++; true }
        ModKeyMappings.attach(id) { second++; true }

        assertEquals(1, ModKeyMappings.size, "one id must own exactly one poller")
        assertTrue(ModKeyMappings.consume(id))
        assertEquals(0, first, "the replaced poller must never run")
        assertEquals(1, second)
    }

    @Test
    fun `detach stops delivery for that id only`() {
        ModKeyMappings.attach(DisplayHotkeys.OPEN_MENU_ID) { true }
        ModKeyMappings.attach(DisplayHotkeys.FULLSCREEN_ID) { true }

        ModKeyMappings.detach(DisplayHotkeys.OPEN_MENU_ID)

        assertFalse(ModKeyMappings.consume(DisplayHotkeys.OPEN_MENU_ID))
        assertTrue(ModKeyMappings.consume(DisplayHotkeys.FULLSCREEN_ID))
        assertEquals(setOf(DisplayHotkeys.FULLSCREEN_ID), ModKeyMappings.attachedIds())
    }

    @Test
    fun `a poller returning false is not a press`() {
        // KeyMapping.consumeClick() answers false on every tick without a fresh press, which is the
        // overwhelming majority of calls; treating it as an action would fire hotkeys continuously.
        ModKeyMappings.attach(DisplayHotkeys.FULLSCREEN_ID) { false }

        assertFalse(ModKeyMappings.consume(DisplayHotkeys.FULLSCREEN_ID))
        assertTrue(ModKeyMappings.isAttached(DisplayHotkeys.FULLSCREEN_ID))
    }

    @Test
    fun `reset clears every poller`() {
        DisplayHotkeys.ALL.forEach { ModKeyMappings.attach(it.id) { true } }
        assertEquals(DisplayHotkeys.ALL.size, ModKeyMappings.size)

        ModKeyMappings.reset()

        assertEquals(0, ModKeyMappings.size)
        DisplayHotkeys.ALL.forEach { assertFalse(ModKeyMappings.consume(it.id)) }
    }

    @Test
    fun `attachedIds snapshot does not observe later mutation`() {
        ModKeyMappings.attach(DisplayHotkeys.OPEN_MENU_ID) { true }
        val snapshot = ModKeyMappings.attachedIds()

        ModKeyMappings.attach(DisplayHotkeys.FULLSCREEN_ID) { true }

        assertEquals(setOf(DisplayHotkeys.OPEN_MENU_ID), snapshot)
        assertEquals(2, ModKeyMappings.attachedIds().size)
    }

    @Test
    fun `polls do not leak between ids`() {
        // Guards the `polls[id]` lookup itself: a map keyed by the wrong value would make every id
        // resolve to the same poller and every hotkey would fire together.
        ModKeyMappings.attach(DisplayHotkeys.OPEN_MENU_ID) { true }

        assertEquals(setOf(DisplayHotkeys.OPEN_MENU_ID), ModKeyMappings.attachedIds())
        assertFalse(ModKeyMappings.consume(DisplayHotkeys.TOGGLE_PLAYBACK_ID))
    }
}