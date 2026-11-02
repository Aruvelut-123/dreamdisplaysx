//? if <26 {
package com.dreamdisplayx.platform.client

import com.dreamdisplayx.platform.client.input.HotkeyKeyMappings
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper

/**
 * Fabric's Controls integration up to 1.21.11: hands the mod's `KeyMapping`s to `KeyBindingHelper`.
 *
 * Fabric does not scan for `KeyMapping` instances — a mapping only joins vanilla's
 * `Options.keyMappings` (and therefore only appears in Controls and gets persisted to `options.txt`)
 * once it is passed through [KeyBindingHelper.registerKeyBinding]. That call is what makes a rebind
 * survive a restart.
 *
 * `26.x` renamed the module to `fabric-key-mapping-api-v1`: the helper became
 * `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper` and the method became
 * `registerKeyMapping`. That variant lives in `FabricHotkeyRegistration26`.
 *
 * Idempotent from the caller's perspective: `registerKeyBinding` rejects a duplicate name with an
 * `IllegalArgumentException` only after `Options` is initialised, so this is expected to run exactly
 * once from `Client.onInitializeClient`. `ModKeyMappings.attach` replaces rather than appends, so even
 * a double call cannot make a press fire twice.
 */
object FabricHotkeyRegistration {
    /** Creates the mappings, registers them with Fabric and wires them into `ModKeyMappings`. */
    fun register() {
        val mappings = HotkeyKeyMappings.create()
        mappings.forEach { KeyBindingHelper.registerKeyBinding(it) }
        HotkeyKeyMappings.attach(mappings)
    }
}
//?}