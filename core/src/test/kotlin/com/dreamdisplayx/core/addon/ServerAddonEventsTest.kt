package com.dreamdisplayx.core.addon

import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.server.ServerDisplay
import com.dreamdisplays.api.server.ServerDisplayEvent
import java.util.UUID
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the two properties a `Removed` event depends on: the event must carry a readable display after the row is gone,
 * and `isAlive` must never claim a removed display still exists just because a fallback snapshot is held.
 *
 * Pure JVM on purpose — [ServerAddonHooks] and [ServerAddonDisplayView] name no Minecraft, libvlc or loader type, so a
 * wrong `fallback` would otherwise only show up as a null-reading addon at runtime.
 */
class ServerAddonEventsTest {

    private fun snapshot(id: UUID, url: String = "", name: String? = "wall"): ServerAddonDisplay = ServerAddonDisplay(
        id = id,
        ownerId = OWNER,
        world = "minecraft:overworld",
        x1 = 1, y1 = 2, z1 = 3,
        x2 = 4, y2 = 5, z2 = 6,
        facing = DisplayFacing.SOUTH,
        width = 4,
        height = 4,
        name = name,
        url = url,
        lang = "zh",
        mode = PlaybackMode.BROADCAST,
        access = DisplayAccess.DEFAULT,
    )

    /** A host over a mutable list, so a test can remove a row between two calls. */
    private class FakeHost(initial: List<ServerAddonDisplay>) : ServerAddonHost {
        val rows = initial.toMutableList()
        val calls = mutableListOf<String>()

        override fun snapshot(): List<ServerAddonDisplay> = rows.toList()

        override fun create(
            owner: UUID, world: String,
            x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int,
            facing: DisplayFacing,
        ): ServerAddonDisplay? = null

        override fun delete(id: UUID): CommandResult {
            calls += "delete"
            return if (rows.removeAll { it.id == id }) CommandResult.OK else CommandResult.STALE
        }

        override fun setUrl(id: UUID, url: String, lang: String): CommandResult = CommandResult.OK
        override fun setAccess(id: UUID, access: DisplayAccess): CommandResult = CommandResult.OK

        override fun moveTo(
            id: UUID, world: String,
            x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int,
            facing: DisplayFacing,
        ): CommandResult = CommandResult.REFUSED

        override fun listen(listener: Consumer<ServerDisplayEvent>): AutoCloseable =
            ServerAddonEvents.on(listener)

        override fun vetoes(): com.dreamdisplayx.api.display.event.DisplayVetoes? = null
    }

    @Test
    fun removedEventCarriesTheRowAndStopsClaimingLifeAfterItIsGone() {
        val id = UUID.randomUUID()
        val host = FakeHost(listOf(snapshot(id, url = "https://example.test/v.mp4")))
        ServerAddonHooks.install(host)
        val seen = mutableListOf<ServerAddonEventFacts>()
        var retained: com.dreamdisplays.api.server.ServerDisplay? = null
        val handle = ServerAddonEvents.on { event ->
            retained = event.display
            seen += ServerAddonEventFacts(
                event::class.simpleName ?: "",
                event.display.url,
                event.display.width,
                event.display.isAlive,
            )
        }
        try {
            // The call site publishes before deleting, exactly like DisplayManager.delete does.
            ServerAddonHooks.removed(id)
            host.rows.clear()
        } finally {
            handle.close()
            ServerAddonHooks.install(null)
        }
        assertEquals(1, seen.size)
        assertEquals("Removed", seen[0].type)
        assertEquals("https://example.test/v.mp4", seen[0].url, "a Removed event must still carry the readable row")
        assertEquals(4, seen[0].width)
        // Dispatch is synchronous, so at this instant the row is still registered and the display really is alive.
        assertTrue(seen[0].alive, "during dispatch the row is still in the registry")

        // An addon that keeps the display keeps reading a usable box, but never claims it still exists.
        val kept = assertNotNull(retained)
        assertFalse(kept.isAlive, "isAlive must answer from the live registry, never from the fallback")
        assertEquals("https://example.test/v.mp4", kept.url, "the retained view falls back to the last snapshot")
        assertEquals(CommandResult.STALE, kept.setUrl("https://example.test/other.mp4", "en"))
    }

    @Test
    fun createdEventResolvesTheLiveRow() {
        val id = UUID.randomUUID()
        val host = FakeHost(listOf(snapshot(id, url = "https://example.test/a.mp4")))
        ServerAddonHooks.install(host)
        val seen = mutableListOf<String>()
        val handle = ServerAddonEvents.on { event ->
            if (event is ServerDisplayEvent.Created) seen += event.display.url
        }
        try {
            ServerAddonHooks.created(id)
            ServerAddonHooks.created(UUID.randomUUID())
        } finally {
            handle.close()
            ServerAddonHooks.install(null)
        }
        assertEquals(listOf("https://example.test/a.mp4"), seen, "an unknown id publishes nothing")
    }

    @Test
    fun urlChangedCarriesTheNewLink() {
        val id = UUID.randomUUID()
        val host = FakeHost(listOf(snapshot(id, url = "https://example.test/new.mp4")))
        ServerAddonHooks.install(host)
        val seen = mutableListOf<String>()
        val handle = ServerAddonEvents.on { event ->
            if (event is ServerDisplayEvent.UrlChanged) seen += event.url
        }
        try {
            ServerAddonHooks.urlChanged(id, "https://example.test/new.mp4")
        } finally {
            handle.close()
            ServerAddonHooks.install(null)
        }
        assertEquals(listOf("https://example.test/new.mp4"), seen)
    }

    @Test
    fun noEventIsBuiltWithoutAListener() {
        val id = UUID.randomUUID()
        val host = FakeHost(listOf(snapshot(id)))
        ServerAddonHooks.install(host)
        try {
            // Every hook returns on the hasListeners read before touching the host.
            ServerAddonHooks.created(id)
            ServerAddonHooks.removed(id)
            ServerAddonHooks.moved(id)
            ServerAddonHooks.urlChanged(id, "u")
            ServerAddonHooks.dataChanged(id, setOf("k"))
            assertTrue(ServerAddonEvents.hasListeners.not())
            assertEquals(emptyList(), host.calls, "the host is never asked to mutate on a addon-free server")
        } finally {
            ServerAddonHooks.install(null)
        }
    }

    @Test
    fun throwingListenerDoesNotStopTheOthers() {
        val id = UUID.randomUUID()
        val host = FakeHost(listOf(snapshot(id)))
        ServerAddonHooks.install(host)
        val reached = mutableListOf<String>()
        val first = ServerAddonEvents.on { throw IllegalStateException("boom") }
        val second = ServerAddonEvents.on { reached += "second" }
        try {
            ServerAddonHooks.created(id)
        } finally {
            first.close()
            second.close()
            ServerAddonHooks.install(null)
        }
        assertEquals(listOf("second"), reached)
    }

    @Test
    fun viewRefusesEveryMutationOnceTheRowIsGone() {
        val id = UUID.randomUUID()
        val host = FakeHost(listOf(snapshot(id)))
        val view: ServerDisplay = ServerAddonEvents.view(host, snapshot(id))
        assertTrue(view.isAlive)
        assertEquals(CommandResult.OK, view.setUrl("https://example.test/x.mp4", "en"))

        host.rows.clear()
        assertFalse(view.isAlive, "a view over a vanished row is not alive")
        assertEquals(CommandResult.STALE, view.setUrl("https://example.test/x.mp4", "en"))
        assertEquals(CommandResult.STALE, view.setAccess(DisplayAccess.DEFAULT))
        assertEquals(CommandResult.STALE, view.delete())
        assertEquals(CommandResult.STALE, view.moveTo(0, 0, 0, 1, 1, 1, DisplayFacing.NORTH))
    }

    @Test
    fun readsFallBackToTheSnapshotWithoutInventingLife() {
        val id = UUID.randomUUID()
        val host = FakeHost(emptyList())
        val view: ServerDisplay = ServerAddonEvents.view(host, snapshot(id, url = "https://example.test/kept.mp4"))

        assertFalse(view.isAlive)
        assertEquals("https://example.test/kept.mp4", view.url)
        assertEquals("wall", view.name)
        assertEquals(4, view.width)
        assertEquals("minecraft:overworld", view.world)
        // Centre of the box, not a corner, so a 3D addon can place a marker.
        assertEquals(2.5, view.x)
        assertEquals(3.5, view.y)
        assertEquals(4.5, view.z)
    }

    @Test
    fun customDataRefusesWritesInsteadOfDroppingThem() {
        val view = ServerAddonEvents.view(FakeHost(emptyList()), snapshot(UUID.randomUUID()))
        assertTrue(view.customData.isEmpty)
        assertEquals(emptySet(), view.customData.keys)
        assertNull(view.customData.get("anything"))
        assertEquals(CommandResult.REFUSED, view.customData.set("k", byteArrayOf(1)))
        assertEquals(CommandResult.REFUSED, view.customData.remove("k"))
        assertEquals(CommandResult.REFUSED, view.customData.clear())
    }

    @Test
    fun snapshotOfAnswersOnlyForALiveRow() {
        val id = UUID.randomUUID()
        val host = FakeHost(listOf(snapshot(id)))
        ServerAddonHooks.install(host)
        try {
            assertNotNull(ServerAddonHooks.snapshotOf(id))
            assertNull(ServerAddonHooks.snapshotOf(UUID.randomUUID()))
        } finally {
            ServerAddonHooks.install(null)
        }
        assertNull(ServerAddonHooks.snapshotOf(id), "no host means no snapshot")
    }

    @Test
    fun clearDropsEveryListener() {
        var count = 0
        ServerAddonEvents.on { count++ }
        ServerAddonEvents.clear()
        assertFalse(ServerAddonEvents.hasListeners)
        ServerAddonEvents.emit(ServerDisplayEvent.Removed(ServerAddonEvents.view(FakeHost(emptyList()), snapshot(UUID.randomUUID()))))
        assertEquals(0, count)
    }

    private data class ServerAddonEventFacts(
        val type: String,
        val url: String,
        val width: Int,
        val alive: Boolean,
    )

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    }
}
