package com.dreamdisplayx.api.display.event

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.UUID

/**
 * Unit tests for [DisplayVetoRegistry]: dispatch by type, first-denial-wins, listener failures.
 */
class DisplayVetoRegistryTest {
    private val registry = DisplayVetoRegistry()
    private val handles = mutableListOf<AutoCloseable>()

    private val target = DisplayVeto.Target(
        id = UUID.randomUUID(),
        ownerId = UUID.randomUUID(),
        width = 3,
        height = 2,
        url = "https://example.invalid/old",
        name = "wall",
        virtual = false,
    )

    private inline fun <reified T : DisplayVeto> listen(crossinline action: (T) -> Unit) {
        handles += registry.on<T> { action(it) }
    }

    @AfterTest
    fun tearDown() {
        handles.forEach { it.close() }
        handles.clear()
        registry.clear()
    }

    @Test
    fun `allowed by default`() {
        assertTrue(registry.isEmpty)
        assertNull(registry.ask(DisplayVeto.SetUrl(target, "https://example.invalid/new", "en", null)))
    }

    @Test
    fun `a denial is reported`() {
        listen<DisplayVeto.SetUrl> { it.deny("nope") }
        assertEquals("nope", registry.ask(DisplayVeto.SetUrl(target, "https://x.invalid/a", "en", null)))
    }

    @Test
    fun `an empty denial still refuses`() {
        listen<DisplayVeto.Delete> { it.deny() }
        val veto = DisplayVeto.Delete(target, UUID.randomUUID())
        assertEquals("", registry.ask(veto))
        assertTrue(veto.isDenied)
    }

    @Test
    fun `the first denial wins`() {
        listen<DisplayVeto.Create> { it.deny("first") }
        listen<DisplayVeto.Create> { it.deny("second") }
        assertEquals("first", registry.ask(DisplayVeto.Create(target, null)))
    }

    @Test
    fun `listeners only see their own type`() {
        var setUrlCalls = 0
        var createCalls = 0
        listen<DisplayVeto.SetUrl> { setUrlCalls++ }
        listen<DisplayVeto.Create> { createCalls++ }

        registry.ask(DisplayVeto.SetUrl(target, "https://x.invalid/a", "en", null))
        assertEquals(1, setUrlCalls)
        assertEquals(0, createCalls)
    }

    @Test
    fun `a throwing listener does not deny and is reported`() {
        val failures = mutableListOf<Throwable>()
        listen<DisplayVeto.Delete> { error("boom") }
        assertNull(registry.ask(DisplayVeto.Delete(target, null)) { failures += it })
        assertEquals(1, failures.size)
    }

    @Test
    fun `closing a handle unsubscribes`() {
        val handle = registry.on<DisplayVeto.Delete> { it.deny("gone") }
        assertEquals("gone", registry.ask(DisplayVeto.Delete(target, null)))
        handle.close()
        assertNull(registry.ask(DisplayVeto.Delete(target, null)))
    }

    @Test
    fun `clear drops every listener`() {
        listen<DisplayVeto.Delete> { it.deny("x") }
        assertFalse(registry.isEmpty)
        registry.clear()
        assertTrue(registry.isEmpty)
        assertNull(registry.ask(DisplayVeto.Delete(target, null)))
    }
}