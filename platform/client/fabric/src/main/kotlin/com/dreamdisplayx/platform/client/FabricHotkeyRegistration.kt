package com.dreamdisplayx.platform.client

import com.dreamdisplayx.platform.client.input.HotkeyKeyMappings
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper

/**
 * Fabric's Controls integration: hands the mod's `KeyMapping`s to `KeyBindingHelper`.
 *
 * Fabric does not scan for `KeyMapping` instances — a mapping only joins vanilla's
 * `Options.keyMappings` (and therefore only appears in Controls and gets persisted to `options.txt`)
 * once it is passed through [KeyBindingHelper.registerKeyBinding]. That call is what makes a rebind
 * survive a restart.
 *
 * NeoForge and Forge instead receive `RegisterKeyMappingsEvent`, so this class is Fabric-only; it
 * lives in the Fabric module and is never compiled for the other loaders.
 */
object FabricHotkeyRegistration {
    /**
     * Registers every [com.dreamdisplayx.platform.client.input.DisplayHotkeys] binding and wires the
     * resulting mappings into `ModKeyMappings`.
     *
     * The registration is a `forEach` lambda rather than a `mappings.forEach { ... }` chain built
     * from the returned mapping: `KeyBindingHelper.registerKeyBinding` returns the mapping it just
     * registered, and only the lambda form makes that discard explicit.
     *
     * Idempotent from the caller's perspective: Fabric rejects a duplicate `registerKeyBinding` only
     * by adding the mapping twice, so this is expected to run exactly once from
     * `Client.onInitializeClient`. `ModKeyMappings.attach` replaces rather than appends, so even a
     * double call cannot make a press fire twice.
     */
    fun register() {
        val mappings = HotkeyKeyMappings.create()
        mappings.forEach { KeyBindingHelper.registerKeyBinding(it) }
        HotkeyKeyMappings.attach(mappings)
    }
}