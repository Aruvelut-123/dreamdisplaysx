package com.dreamdisplays.core.addon

import com.dreamdisplays.api.Display
import com.dreamdisplays.api.addon.Addon
import com.dreamdisplays.api.addon.AddonContext
import com.dreamdisplays.api.addon.AddonSide
import com.dreamdisplays.api.addon.DreamDisplaysAddon
import com.dreamdisplays.api.addon.OnDisplay
import com.dreamdisplays.api.addon.OnFailed
import com.dreamdisplays.api.addon.OnPlaying
import com.dreamdisplays.api.display.model.DisplayInfo
import com.dreamdisplays.api.display.model.property.DisplayBounds
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.model.property.DisplayState
import com.dreamdisplays.api.display.model.settings.DisplaySettings
import com.dreamdisplays.core.api.DefaultDisplays
import com.dreamdisplays.core.services.DefaultDisplayService
import com.dreamdisplays.core.services.DefaultDisplaySystem
import com.dreamdisplays.core.services.DefaultPlaybackService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AddonManagerTest {
    private val log = mutableListOf<String>()

    @Addon(id = "lifecycle", name = "Lifecycle")
    private inner class Lifecycle : DreamDisplaysAddon {
        override fun onEnable(context: AddonContext) {
            log += "enable ${context.id} ${context.side}"
            context.own(AutoCloseable { log += "closed" })
        }

        override fun onDisable() {
            log += "disable"
        }
    }

    @Addon(id = "broken")
    private class Broken : DreamDisplaysAddon {
        override fun onEnable(context: AddonContext) = error("boom")
    }

    @Addon(id = "server-only", side = AddonSide.SERVER)
    private class ServerOnly : DreamDisplaysAddon {
        override fun onEnable(context: AddonContext) = error("must not run on the client")
    }

    class NotAnAddon

    private fun manager() = AddonManager(AddonSide.CLIENT, [])

    @Test
    fun `an addon is enabled on start and disabled with its resources closed on stop`() {
        val manager = manager()
        manager.register(Lifecycle())
        assertEquals([], log)

        manager.start()
        assertEquals(setOf("lifecycle"), manager.enabledIds)
        manager.stop()

        assertEquals(["enable lifecycle CLIENT", "disable", "closed"], log)
        assertTrue(manager.enabledIds.isEmpty())
    }

    @Test
    fun `an addon registered while running is enabled at once`() {
        val manager = manager()
        manager.start()
        manager.register(Lifecycle())
        assertEquals(setOf("lifecycle"), manager.enabledIds)
    }

    @Test
    fun `registrations survive a stop and are enabled again on the next start`() {
        val manager = manager()
        manager.register(Lifecycle())
        manager.start()
        manager.stop()
        manager.start()
        assertEquals(2, log.count { it.startsWith("enable") })
    }

    @Test
    fun `a failing addon is disabled and does not stop the others`() {
        val manager = manager()
        manager.register(Broken())
        manager.register(Lifecycle())
        manager.start()
        assertEquals(setOf("lifecycle"), manager.enabledIds)
    }

    @Test
    fun `an addon for the other side is skipped`() {
        val manager = manager()
        manager.register(ServerOnly())
        manager.start()
        assertTrue(manager.enabledIds.isEmpty())
    }

    @Test
    fun `registration needs the Addon annotation and a unique id`() {
        val manager = manager()
        assertFailsWith<IllegalArgumentException> { manager.register(NotAnAddon()) }
        manager.register(Lifecycle())
        assertFailsWith<IllegalArgumentException> { manager.register(Lifecycle()) }
    }

    private val system = DefaultDisplaySystem()
    private val displayService = DefaultDisplayService(system, system)
    private val playbackService = DefaultPlaybackService(system)
    private val displays = DefaultDisplays(displayService, playbackService, owner = null)

    private fun id(n: Int) = DisplayId(UUID.fromString("00000000-0000-0000-0000-00000000000$n"))

    private fun info(n: Int, state: DisplayState = DisplayState.Idle) = DisplayInfo(
        id = id(n),
        bounds = DisplayBounds(0.0, 0.0, 0.0, 4, 3, DisplayFacing.NORTH),
        settings = DisplaySettings.DEFAULT,
        url = null,
        state = state,
    )

    @Addon(id = "handlers")
    private inner class Handlers {
        @OnDisplay
        fun seen(display: Display) {
            log += "display ${display.id}"
        }

        @OnPlaying
        fun playing(display: Display, state: DisplayState.Playing) {
            log += "playing ${display.id} at ${state.positionMs}"
        }

        @OnFailed
        fun failed(display: Display) {
            log += "failed ${display.id}"
        }
    }

    @Addon(id = "bad-signature")
    private class BadSignature {
        @OnDisplay
        fun wrong(text: String) = Unit
    }

    @Addon(id = "throws")
    private class Throws {
        @OnDisplay
        fun seen(display: Display) {
            error("handler bug")
        }
    }

    private fun clientManager() = AddonManager(AddonSide.CLIENT, [ClientAddonBinder { displays }])

    @Test
    fun `OnDisplay is called for existing and later displays`() {
        system.recordDisplay(info(1))
        val manager = clientManager()
        manager.register(Handlers())
        manager.start()
        system.recordDisplay(info(2))
        assertEquals(["display ${id(1)}", "display ${id(2)}"], log.filter { it.startsWith("display") })
    }

    @Test
    fun `OnPlaying passes the display and the playing state`() {
        system.recordDisplay(info(1))
        val manager = clientManager()
        manager.register(Handlers())
        manager.start()
        system.recordDisplay(info(1, DisplayState.Playing("s", 42, 100)))
        assertEquals(["playing ${id(1)} at 42"], log.filter { it.startsWith("playing") })
    }

    @Test
    fun `handlers stop when the addon is disabled`() {
        system.recordDisplay(info(1))
        val manager = clientManager()
        manager.register(Handlers())
        manager.start()
        manager.stop()
        system.recordDisplay(info(1, DisplayState.Playing("s", 1, 10)))
        assertTrue(log.none { it.startsWith("playing") })
    }

    @Test
    fun `a handler with the wrong signature disables only that addon`() {
        val manager = clientManager()
        manager.register(BadSignature())
        manager.register(Handlers())
        manager.start()
        assertEquals(setOf("handlers"), manager.enabledIds)
    }

    @Test
    fun `a throwing handler does not break the addon or the display system`() {
        system.recordDisplay(info(1))
        val manager = clientManager()
        manager.register(Throws())
        manager.start()
        system.recordDisplay(info(2))
        assertEquals(setOf("throws"), manager.enabledIds)
    }
}
