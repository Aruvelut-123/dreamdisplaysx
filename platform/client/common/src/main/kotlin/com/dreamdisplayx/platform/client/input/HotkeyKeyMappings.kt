package com.dreamdisplayx.platform.client.input

import com.dreamdisplayx.platform.client.utils.identifier
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping

/**
 * Creates the vanilla `KeyMapping`s behind [DisplayHotkeys], so the mod's hotkeys show up in
 * `Options -> Controls` and players can rebind them.
 *
 * ## Version gates
 *
 * `KeyMapping`'s category argument changed type in 1.21.11:
 *
 * - 1.18.2 – 1.21.1: `KeyMapping(String, int, String)` / `(String, Type, int, String)` — a plain
 *   translation-key string such as `"key.categories.movement"`.
 * - 1.21.11+: `KeyMapping(String, int, KeyMapping.Category)` — a record wrapping an `Identifier`,
 *   registered through `KeyMapping.Category.register(Identifier)`. That call throws
 *   `IllegalArgumentException` on a duplicate id, hence the single lazy instance in [category].
 *
 * The category *translation key* differs too, and Minecraft derives it from the `Identifier`:
 * `Identifier.toLanguageKey("key.category")` yields `key.category.dreamdisplayx.hotkeys`, i.e.
 * `<prefix>.<namespace>.<path>`. The lang files therefore carry both spellings — the legacy
 * `key.categories.dreamdisplayx` for 1.18.2–1.21.1 and the namespaced one for 1.21.11+; a missing
 * one just shows the raw key on that version.
 *
 * `InputConstants.Type` also changed in 26.3: `KEYSYM` was renamed to `KEYBOARD` and `SCANCODE` was
 * removed. Checked against the official 26.1.2 / 26.2 / 26.3 client jars — 26.1.2 and 26.2 still
 * expose `KEYSYM`/`SCANCODE`/`MOUSE`, 26.3 exposes only `KEYBOARD`/`MOUSE`. Hence [keyboardType].
 *
 * Whatever a loader does, it must call [create] and then [attach]; that is what turns the binding
 * into something [DisplayHotkeyActions] can act on.
 */
object HotkeyKeyMappings {
    /**
     * The `KeyMapping` category object on 1.21.11+.
     *
     * Lazy and single-instance on purpose: `Category.register` rejects a second registration of the
     * same id, so a dev `runClient` relaunch reusing this JVM must not build a fresh one.
     */
    //? if >=1.21.11 {
    // NeoForge 1.21.11+ marks `Category.register` `@Deprecated` and points at
    // `RegisterKeyMappingsEvent.registerCategory`, but the patch only adds the annotation: the body
    // still appends to `SORT_ORDER`, so the call keeps working and the mapping still groups under our
    // category. Going through the event instead would force this common file to name a NeoForge type
    // and stop Fabric/Forge from sharing it, which is a worse trade than one suppressed warning.
    @Suppress("DEPRECATION")
    private val category by lazy { KeyMapping.Category.register(identifier("dreamdisplayx", "hotkeys")) }
    //?}

    /**
     * The `InputConstants.Type` used for keyboard bindings on this Minecraft version.
     *
     * Split out so the 26.3 rename lives in exactly one place instead of at every construction site.
     */
    private val keyboardType: InputConstants.Type
        get() =
            //? if >=26.3 {
            InputConstants.Type.KEYBOARD
            //?} else
            /*InputConstants.Type.KEYSYM*/

    /**
     * Creates one `KeyMapping` for every [DisplayHotkeys] entry, in catalog order.
     *
     * Every mapping starts unbound (`-1`), which Minecraft renders as "Not bound": the player is
     * expected to assign the key in Controls, and vanilla persists it to `options.txt` from there.
     *
     * @return the created mappings, in [DisplayHotkeys.ALL] order.
     */
    fun create(): List<KeyMapping> =
        DisplayHotkeys.ALL.map { binding ->
            val type = if (binding.isMouse) InputConstants.Type.MOUSE else keyboardType
            //? if >=1.21.11 {
            KeyMapping(DisplayHotkeys.labelKey(binding), type, binding.defaultKey, category)
            //?} else
            /*KeyMapping(DisplayHotkeys.labelKey(binding), type, binding.defaultKey, DisplayHotkeys.LEGACY_CATEGORY_KEY)*/
        }

    /**
     * Wires [mappings] into [ModKeyMappings], so a press becomes an action.
     *
     * Ids are matched to [DisplayHotkeys.ALL] by position, which is why [create] returns them in
     * catalog order. Attaching replaces rather than appends, so calling this twice cannot make a
     * single press fire twice.
     */
    fun attach(mappings: List<KeyMapping>) {
        mappings.zip(DisplayHotkeys.ALL).forEach { (mapping, binding) ->
            ModKeyMappings.attach(binding.id) { mapping.consumeClick() }
        }
    }
}
