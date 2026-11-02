package com.dreamdisplayx.platform.client.input

/**
 * Every hotkey the mod exposes in Minecraft's own `Options -> Controls` menu.
 *
 * A hotkey only becomes editable there if it is listed here *and* a loader turns it into a real
 * `KeyMapping`. Adding an entry to [ALL] therefore means "players can rebind this"; touching only
 * [KeyBindingRegistry] would leave it invisible, which is exactly the gap this catalog closes.
 *
 * ## Why the defaults are unbound
 *
 * [UNBOUND_KEY] (`-1`) is Minecraft's "Not bound" state. Shipping a guessed default would shadow a
 * name a player already uses — and vanilla shows such a clash as a red conflict, with both actions
 * still firing. Since the whole point of the Controls integration is letting the player choose, the
 * catalog refuses to pick for them: assign any key once in Controls and it persists in `options.txt`
 * like a vanilla binding. The legacy right-click + sneak path to the menu is untouched and needs no
 * binding at all, so a fresh install loses no functionality.
 *
 * ## What intentionally stays out
 *
 * The in-menu keys are not here: `Space` (play/pause), arrows (seek / volume), `M` (mute), digits
 * (jump to a percentage), `F` (fullscreen) and `P` (pop-out) are handled by `DisplayMenu.keyPressed`
 * and only exist while the menu is open. Vanilla's own bindings already own those key codes
 * worldwide — making `Space` a mod binding would make one press both jump and pause, and `M` / `F` /
 * the digits would collide with chat, offhand swap and the hotbar. A modal screen key is contextual
 * by nature and must not be a global binding.
 */
object DisplayHotkeys {
    /** Translation-key prefix for the legacy (`1.18.2`–`1.21.1`) category argument. */
    const val LEGACY_CATEGORY_KEY: String = "key.categories.dreamdisplayx"

    /** Translation key the `1.21.11+` `KeyMapping.Category` resolves to, derived from its id. */
    const val NAMESPACED_CATEGORY_KEY: String = "key.category.dreamdisplayx.hotkeys"

    /** Minecraft's "Not bound" key code, used as every default so nothing shadows a vanilla key. */
    const val UNBOUND_KEY: Int = -1

    /** Opens the display menu for the display under the crosshair. */
    const val OPEN_MENU_ID: String = "dreamdisplayx.open_menu"

    /** Play/pause on the display under the crosshair. */
    const val TOGGLE_PLAYBACK_ID: String = "dreamdisplayx.toggle_playback"

    /** Turns displays (and their audio) on or off client-side. */
    const val TOGGLE_DISPLAYS_ID: String = "dreamdisplayx.toggle_displays"

    /** Opens the display under the crosshair fullscreen. */
    const val FULLSCREEN_ID: String = "dreamdisplayx.fullscreen"

    /**
     * Every binding registered in Controls, in display order.
     *
     * Order is stable and is what [HotkeyKeyMappings.attach] relies on to match a created
     * `KeyMapping` back to its id.
     */
    val ALL: List<KeyBinding> = listOf(
        KeyBinding(
            id = OPEN_MENU_ID,
            defaultKey = UNBOUND_KEY,
            category = LEGACY_CATEGORY_KEY,
            description = "Open the display menu",
        ),
        KeyBinding(
            id = TOGGLE_PLAYBACK_ID,
            defaultKey = UNBOUND_KEY,
            category = LEGACY_CATEGORY_KEY,
            description = "Play / pause the targeted display",
        ),
        KeyBinding(
            id = TOGGLE_DISPLAYS_ID,
            defaultKey = UNBOUND_KEY,
            category = LEGACY_CATEGORY_KEY,
            description = "Enable / disable displays",
        ),
        KeyBinding(
            id = FULLSCREEN_ID,
            defaultKey = UNBOUND_KEY,
            category = LEGACY_CATEGORY_KEY,
            description = "Open the targeted display fullscreen",
        ),
    )

    /** Translation key for [binding]'s Controls label, e.g. `key.dreamdisplayx.toggle_playback`. */
    fun labelKey(binding: KeyBinding): String = "key.${binding.id}"

    /** Returns the catalog entry with [id], or `null`. */
    fun findById(id: String): KeyBinding? = ALL.firstOrNull { it.id == id }
}