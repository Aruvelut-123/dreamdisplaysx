//? if >=26 {
package com.dreamdisplayx.platform.client

import com.dreamdisplayx.platform.client.input.HotkeyKeyMappings
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper

/**
 * Fabric's Controls integration from 26.1 on.
 *
 * Fabric API renamed this module for the year-based versions: `fabric-key-binding-api-v1` with
 * `net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding` became
 * `fabric-key-mapping-api-v1` with
 * `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping`. Verified against
 * the `26.3` tree of FabricMC/fabric — the `keybinding` package no longer exists there, which is why
 * the pre-26 call does not compile on these versions.
 *
 * Registration also rejects a duplicate *name*, not just a duplicate instance, so this must run once
 * and `HotkeyKeyMappings` must not build the same catalog twice for one launch.
 *
 * `KeyMapping.Category.register` keeps working on this range: Fabric mixes into it only to re-sort
 * `SORT_ORDER`, so the shared `HotkeyKeyMappings` can keep creating the category itself.
 */
object FabricHotkeyRegistration {
    /** Creates the mappings, registers them with Fabric and wires them into `ModKeyMappings`. */
    fun register() {
        val mappings = HotkeyKeyMappings.create()
        mappings.forEach { KeyMappingHelper.registerKeyMapping(it) }
        HotkeyKeyMappings.attach(mappings)
    }
}
//?}