//? if <1.21.1 {
package com.dreamdisplayx.platform.client

import com.dreamdisplayx.platform.client.input.DisplayHotkeys
import com.dreamdisplayx.platform.client.input.HotkeyKeyMappings

/**
 * Forge's Controls integration (1.18.2 – 1.20.1).
 *
 * This is the only path that puts a `KeyMapping` into vanilla's `Options.keyMappings`, which is what
 * makes a binding visible in `Options -> Controls` and persisted to `options.txt`.
 *
 * ## Why there are two branches
 *
 * Forge changed its registration route between 1.18.2 and 1.19.4, verified against the official
 * Forge universal jars:
 *
 * - **1.18.2** has no `RegisterKeyMappingsEvent` at all (`forge-1.18.2-40.3.12-universal.jar` carries
 *   no `net/minecraftforge/client/event/RegisterKeyMappingsEvent.class`). The only supported entry
 *   point is `net.minecraftforge.client.ClientRegistry.registerKeyBinding(KeyMapping)`.
 * - **1.19.4+** introduced `net.minecraftforge.client.event.RegisterKeyMappingsEvent`, a mod-bus
 *   event, and *removed* `ClientRegistry` from the universal jar.
 *
 * The mod event bus is reached through `FMLJavaModLoadingContext.get().modEventBus`. It must not be
 * `ModLoadingContext.get().activeContainer` — `ModContainer` exposes no event bus at all (checked
 * with `javap` on `fmlcore`), and `activeContainer` is a `private` field on
 * `ModLoadingContext`. `getModEventBus()` exists on both 1.18.2 and 1.20.1.
 *
 * Both branches converge on the same two steps: create the mappings, then attach them to
 * `ModKeyMappings` so a press reaches [com.dreamdisplayx.platform.client.input.DisplayHotkeyActions].
 *
 * Gated below 1.21.1 because 1.21.1+ targets NeoForge, whose event lives in the
 * `net.neoforged.neoforge` package and is handled by `NeoForgeHotkeyRegistration`.
 */
object ForgeHotkeyRegistration {
    /**
     * Registers the hotkeys.
     *
     * Called once from `Client.init()` / `Client1182.init()`. Both branches are safe to run during
     * mod construction: 1.18.2's `ClientRegistry` only appends to a list that Minecraft reads later
     * when it builds `Options`, and the 1.19.4+ listener fires at exactly that point.
     */
    fun register() {
        //? if >=1.19 {
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().modEventBus.addListener(
            net.minecraftforge.eventbus.api.EventPriority.NORMAL,
            false,
            net.minecraftforge.client.event.RegisterKeyMappingsEvent::class.java,
        ) { event -> onRegisterKeyMappings(event) }
        //?} else
        /*registerDirectly()*/
    }

    //? if >=1.19 {
    /** Creates the mappings and hands them to [event]. */
    private fun onRegisterKeyMappings(
        event: net.minecraftforge.client.event.RegisterKeyMappingsEvent,
    ) {
        val mappings = HotkeyKeyMappings.create()
        mappings.forEach { event.register(it) }
        HotkeyKeyMappings.attach(mappings)
    }
    //?} else
    /*
    private fun registerDirectly() {
        val mappings = HotkeyKeyMappings.create()
        // 1.18.2 exposes the list append directly; there is no event to wait for.
        mappings.forEach { net.minecraftforge.client.ClientRegistry.registerKeyBinding(it) }
        HotkeyKeyMappings.attach(mappings)
    }
    */

    /** Startup summary, logged once after registration. */
    fun describe(): String = "forge: ${DisplayHotkeys.ALL.size} hotkeys"
}
//?}