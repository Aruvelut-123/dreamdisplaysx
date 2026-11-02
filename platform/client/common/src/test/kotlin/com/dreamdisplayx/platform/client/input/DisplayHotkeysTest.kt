package com.dreamdisplayx.platform.client.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the rules [DisplayHotkeys] is supposed to encode.
 *
 * These are not cosmetic: the catalog is the single source of truth for what a player can rebind, so
 * an accidental duplicate id would make one Controls row shadow another, and a shipped default would
 * silently steal a vanilla bind.
 */
class DisplayHotkeysTest {
    @Test
    fun `every binding has a unique id`() {
        val ids = DisplayHotkeys.ALL.map { it.id }

        assertEquals(ids.size, ids.toSet().size, "duplicate id would collapse two Controls rows")
    }

    @Test
    fun `every id is namespaced with the mod id`() {
        // The id becomes the translation key (`key.<id>`) and is written to options.txt, so an
        // un-namespaced one could collide with another mod's binding or a future vanilla key.
        DisplayHotkeys.ALL.forEach { binding ->
            assertTrue(
                binding.id.startsWith("dreamdisplayx."),
                "binding '${binding.id}' must be namespaced so Controls shows a stable, unique row",
            )
            assertTrue(
                binding.id.substringAfter("dreamdisplayx.").isNotEmpty(),
                "binding '${binding.id}' needs a name after the namespace",
            )
        }
    }

    @Test
    fun `no binding ships a default key`() {
        // An unbound default is the whole design: the player picks the key in Controls, and vanilla
        // shows no red conflict until they do. A real default here would shadow a key they already use.
        DisplayHotkeys.ALL.forEach { binding ->
            assertEquals(
                DisplayHotkeys.UNBOUND_KEY,
                binding.defaultKey,
                "binding '${binding.id}' must default to 'Not bound', not steal a vanilla key",
            )
        }
    }

    @Test
    fun `the in-menu keys are not exposed as world bindings`() {
        // Space / arrows / M / F / P / digits belong to DisplayMenu.keyPressed and are contextual.
        // If one ever appears here, a single press would both jump and pause (or both open the
        // inventory and seek), which is exactly the collision this design avoids.
        val forbidden = setOf(
            "dreamdisplayx.pause",
            "dreamdisplayx.seek_forward",
            "dreamdisplayx.seek_back",
            "dreamdisplayx.volume_up",
            "dreamdisplayx.volume_down",
            "dreamdisplayx.mute",
            "dreamdisplayx.popout",
            "dreamdisplayx.jump_percent",
        )

        val present = DisplayHotkeys.ALL.map { it.id }.filter { it in forbidden }

        assertTrue(present.isEmpty(), "in-menu keys must not become global bindings: $present")
    }

    @Test
    fun `lookup by id round-trips and rejects unknown ids`() {
        DisplayHotkeys.ALL.forEach { binding ->
            assertNotNull(DisplayHotkeys.findById(binding.id), "findById must resolve '${binding.id}'")
        }

        assertNull(DisplayHotkeys.findById("dreamdisplayx.does_not_exist"))
        assertNull(DisplayHotkeys.findById(""))
    }

    @Test
    fun `label keys follow the vanilla key-dot-id convention`() {
        // Controls resolves a row's name from `key.<id>`; without that prefix the screen shows the
        // raw translation key instead of the translated label.
        val binding = DisplayHotkeys.findById(DisplayHotkeys.TOGGLE_PLAYBACK_ID)

        assertNotNull(binding)
        assertEquals("key.dreamdisplayx.toggle_playback", DisplayHotkeys.labelKey(binding))
    }

    @Test
    fun `both category translation keys are declared`() {
        // 1.18.2-1.21.1 pass the legacy key straight through, while 1.21.11+ builds it from the
        // Category identifier as `key.category.<namespace>.<path>`. Language files must carry both,
        // otherwise one range shows the raw key as the category header.
        assertEquals("key.categories.dreamdisplayx", DisplayHotkeys.LEGACY_CATEGORY_KEY)
        assertEquals("key.category.dreamdisplayx.hotkeys", DisplayHotkeys.NAMESPACED_CATEGORY_KEY)
    }

    @Test
    fun `the four documented actions are catalogued`() {
        val ids = DisplayHotkeys.ALL.map { it.id }.toSet()

        assertTrue(DisplayHotkeys.OPEN_MENU_ID in ids)
        assertTrue(DisplayHotkeys.TOGGLE_PLAYBACK_ID in ids)
        assertTrue(DisplayHotkeys.TOGGLE_DISPLAYS_ID in ids)
        assertTrue(DisplayHotkeys.FULLSCREEN_ID in ids)
    }

    @Test
    fun `the catalog is exactly the set the tick handler can act on`() {
        // A binding the catalog lists but DisplayHotkeyActions does not handle would appear in
        // Controls, be assignable, and then do nothing — the worst possible outcome for a player.
        val handled = setOf(
            DisplayHotkeys.OPEN_MENU_ID,
            DisplayHotkeys.TOGGLE_PLAYBACK_ID,
            DisplayHotkeys.TOGGLE_DISPLAYS_ID,
            DisplayHotkeys.FULLSCREEN_ID,
        )

        assertEquals(handled, DisplayHotkeys.ALL.map { it.id }.toSet())
    }
}