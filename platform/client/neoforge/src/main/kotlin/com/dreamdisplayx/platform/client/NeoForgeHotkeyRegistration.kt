//? if >=1.21.1 {
package com.dreamdisplayx.platform.client

import com.dreamdisplayx.platform.client.input.DisplayHotkeys
import com.dreamdisplayx.platform.client.input.HotkeyKeyMappings
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent

/**
 * NeoForge's Controls integration: adds the mod's `KeyMapping`s through
 * [RegisterKeyMappingsEvent], the only bus event that puts a mapping into vanilla's
 * `Options.keyMappings` — and therefore into `Options -> Controls` and `options.txt`.
 *
 * Registered on the **mod** event bus, not `NeoForge.EVENT_BUS`: `RegisterKeyMappingsEvent`
 * implements `IModBusEvent`, so a game-bus listener would never be invoked.
 *
 * Gated to `>=1.21.1` because that is where NeoForge starts in this project (1.18.2–1.20.1 target
 * Forge, which has the same event under the `net.minecraftforge` package and lives in its own file).
 */
object NeoForgeHotkeyRegistration {
    /**
     * Creates the mappings and hands them to [event].
     *
     * `HotkeyKeyMappings.create` does not touch `KeyMapping.Category` on this range — the record form
     * arrived in 1.21.11 — so the legacy `String` category argument is used there.
     */
    fun onRegister(event: RegisterKeyMappingsEvent) {
        val mappings = HotkeyKeyMappings.create()
        mappings.forEach(event::register)
        HotkeyKeyMappings.attach(mappings)
    }

    /** Startup summary, logged once after registration. */
    fun describe(): String = "neoforge: ${DisplayHotkeys.ALL.size} hotkeys"
}
//?}