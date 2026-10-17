package com.dreamdisplays.core.display

import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.placement.DisplayPlacement
import com.dreamdisplays.api.display.placement.DisplayPlacementProvider
import com.dreamdisplays.api.display.placement.PlacementTransform
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacementHubTest {
    private val id = DisplayId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val registrations = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanUp() = registrations.forEach { it.close() }

    @Test
    fun `with no provider every display sits at its stored coordinates`() {
        assertTrue(PlacementHub.isEmpty)
        assertNull(PlacementHub.placementOf(id))
    }

    @Test
    fun `the first provider that knows the display wins`() {
        val placement = DisplayPlacement { PlacementTransform(0, 0, 0, 1.0, 2.0, 3.0) }
        registrations += PlacementHub.register { null }
        registrations += PlacementHub.register { requested -> placement.takeIf { requested == id } }

        assertTrue(!PlacementHub.isEmpty)
        assertEquals(placement, PlacementHub.placementOf(id))
        assertNull(PlacementHub.placementOf(DisplayId(UUID.randomUUID())))
    }

    @Test
    fun `closing the registration removes the provider and a throwing provider is skipped`() {
        val registration = PlacementHub.register(DisplayPlacementProvider { error("provider bug") })
        registrations += PlacementHub.register { DisplayPlacement { PlacementTransform(0, 0, 0, 0.0, 0.0, 0.0) } }
        assertNotNull(PlacementHub.placementOf(id), "the second provider still answers")

        registration.close()
        registrations.forEach { it.close() }
        registrations.clear()
        assertTrue(PlacementHub.isEmpty)
    }
}
