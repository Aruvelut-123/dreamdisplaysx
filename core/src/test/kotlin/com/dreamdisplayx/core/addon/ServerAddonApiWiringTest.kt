package com.dreamdisplayx.core.addon

import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.addon.Addon
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.internal.ServerHolder
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.server.DreamDisplaysServer
import com.dreamdisplays.api.server.ServerDisplays
import com.dreamdisplays.api.server.ServerDisplayEvent
import com.dreamdisplayx.api.display.event.DisplayVeto as ForkVeto
import com.dreamdisplayx.api.display.event.DisplayVetoRegistry
import com.dreamdisplays.api.server.DisplayVeto as UpVeto
import java.util.UUID
import java.util.function.Consumer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the *server* half of the addon API: `DreamDisplaysServer` must answer from a real backend instead of throwing
 * "not available", a registered addon must reach the manager's sink, and an addon veto must land in the fork's own
 * veto registry.
 *
 * This is the seam that used to be missing: the classes were in the jar and `ServerAddonRuntime.start` logged that it
 * attached, but nothing proved `ServerHolder` was ever fed or that a veto reached a gate. Both are pure JVM — no
 * Minecraft, no libvlc, no loader type is named below.
 */
class ServerAddonApiWiringTest {

    @AfterTest
    fun detachApi() {
        ServerHolder.detach()
        ServerAddonHooks.install(null)
        ServerAddonEvents.clear()
    }

    /** A host over one fixed row; mutations only record what was asked. */
    private class FakeHost(private val rows: List<ServerAddonDisplay>, private val registry: DisplayVetoRegistry?) :
        ServerAddonHost {

        val calls = mutableListOf<String>()

        override fun snapshot(): List<ServerAddonDisplay> = rows

        override fun create(
            owner: UUID, world: String,
            x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int,
            facing: DisplayFacing,
        ): ServerAddonDisplay? {
            calls += "create"
            return rows.firstOrNull()
        }

        override fun delete(id: UUID): CommandResult {
            calls += "delete"
            return CommandResult.OK
        }

        override fun setUrl(id: UUID, url: String, lang: String): CommandResult {
            calls += "setUrl:$url"
            return CommandResult.OK
        }

        override fun setAccess(id: UUID, access: DisplayAccess): CommandResult {
            calls += "setAccess:$access"
            return CommandResult.OK
        }

        override fun moveTo(
            id: UUID, world: String,
            x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int,
            facing: DisplayFacing,
        ): CommandResult = CommandResult.REFUSED

        override fun listen(listener: Consumer<ServerDisplayEvent>): AutoCloseable = ServerAddonEvents.on(listener)

        override fun vetoes(): com.dreamdisplayx.api.display.event.DisplayVetoes? = registry
    }

    private fun snapshot(id: UUID, url: String = "https://example.test/a.mp4"): ServerAddonDisplay = ServerAddonDisplay(
        id = id,
        ownerId = OWNER,
        world = "minecraft:overworld",
        x1 = 0, y1 = 64, z1 = 0,
        x2 = 2, y2 = 66, z2 = 0,
        facing = DisplayFacing.SOUTH,
        width = 2,
        height = 2,
        name = "wall",
        url = url,
        lang = "en",
        mode = PlaybackMode.LOCAL,
        access = DisplayAccess.EVERYONE,
    )

    private fun attach(host: ServerAddonHost, registry: DisplayVetoRegistry? = null): MutableList<Any> {
        val registered = mutableListOf<Any>()
        val bridge = ServerAddonVetoBridge(host) { registry }
        ServerAddonHooks.install(host)
        ServerHolder.attach(ServerAddonBackend(host, bridge, { registered += it }))
        return registered
    }

    @Test
    fun serverApiIsUnavailableUntilAttachedAndAnswersAfterwards() {
        assertFalse(DreamDisplaysServer.isAvailable, "nothing is attached before the loader starts the runtime")

        val id = UUID.randomUUID()
        attach(FakeHost(listOf(snapshot(id)), null))

        assertTrue(DreamDisplaysServer.isAvailable)
        assertEquals(1, DreamDisplaysServer.displays.size)
        val display = assertNotNull(DreamDisplaysServer.display(id), "the API must resolve a live row, not throw")
        assertEquals(id, display.id)
        assertEquals("wall", display.name)
        assertEquals(CommandResult.OK, display.setUrl("https://example.test/b.mp4", "en"))
    }

    @Test
    fun onReadyRunsImmediatelyOnceTheBackendIsAttached() {
        attach(FakeHost(emptyList(), null))
        var ran = false
        DreamDisplaysServer.onReady { ran = true }
        assertTrue(ran, "an already-attached backend answers right away instead of queueing forever")
    }

    @Test
    fun registerAddonReachesTheManagersSink() {
        val registered = attach(FakeHost(emptyList(), null))
        val addon = SampleAddon()
        DreamDisplaysServer.register(addon)
        assertEquals(listOf<Any>(addon), registered, "register() must land in the server AddonManager")
    }

    @Test
    fun addonVetoLandsInTheForksOwnRegistry() {
        val id = UUID.randomUUID()
        val registry = DisplayVetoRegistry()
        attach(FakeHost(listOf(snapshot(id)), registry), registry)

        // Exactly what a third-party addon does: subscribe through the upstream API.
        val handle = DreamDisplaysServer.vetoes.on(UpVeto.SetUrl::class.java, Consumer { it.deny("addon says no") })
        try {
            val target = ForkVeto.Target(
                id = id, ownerId = OWNER, width = 2, height = 2,
                url = "https://example.test/a.mp4", name = "wall", virtual = false,
            )
            val denial = registry.ask(ForkVeto.SetUrl(target, "https://example.test/b.mp4", "en", null))
            assertEquals("addon says no", denial, "the gate must see the addon's denial, not one of its own")
        } finally {
            handle.close()
        }

        val afterClose = registry.ask(
            ForkVeto.SetUrl(
                ForkVeto.Target(
                    id = id, ownerId = OWNER, width = 2, height = 2,
                    url = "https://example.test/a.mp4", name = "wall", virtual = false,
                ),
                "https://example.test/b.mp4", "en", null,
            ),
        )
        assertEquals(null, afterClose, "closing the handle detaches the registry listener again")
    }

    @Test
    fun serverDisplaysViewReReadsTheLiveRows() {
        val id = UUID.randomUUID()
        val host = FakeHost(listOf(snapshot(id)), null)
        attach(host)
        val view: ServerDisplays = DreamDisplaysServer.displays
        assertEquals(1, view.size)
        assertNotNull(view.get(id))
        assertEquals(id, assertNotNull(view.byName("wall")).id)
        assertEquals(1, view.ownedBy(OWNER).size)
        assertEquals(0, view.ownedBy(UUID.randomUUID()).size, "a filter over no rows is empty, not an error")
    }

    /** A minimal upstream-shaped addon; `AddonManager` reads the annotation off the class. */
    @Addon(id = "test-addon", name = "Test Addon")
    private class SampleAddon

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
    }
}
