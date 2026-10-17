package com.dreamdisplays.api.display

import com.dreamdisplays.api.display.data.DisplayCustomDataLimits
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayCustomDataLimitsTest {
    @Test
    fun `custom data keys need a namespace`() {
        assertTrue(DisplayCustomDataLimits.isValidKey("sable:binding"))
        assertTrue(DisplayCustomDataLimits.isValidKey("my-addon:a/b.c_d"))
        assertFalse(DisplayCustomDataLimits.isValidKey("binding"))
        assertFalse(DisplayCustomDataLimits.isValidKey("Sable:Binding"))
        assertFalse(DisplayCustomDataLimits.isValidKey("a:" + "x".repeat(70)))
    }

    @Test
    fun `custom data sizes and counts are limited`() {
        val big = ByteArray(DisplayCustomDataLimits.MAX_VALUE_BYTES + 1)
        assertFalse(DisplayCustomDataLimits.accepts(emptyMap(), "a:b", big))
        assertTrue(DisplayCustomDataLimits.accepts(emptyMap(), "a:b", ByteArray(100)))

        val full = (0 until DisplayCustomDataLimits.MAX_ENTRIES).associate { "a:k$it" to ByteArray(1) }
        assertFalse(DisplayCustomDataLimits.accepts(full, "a:new", ByteArray(1)))
        assertTrue(DisplayCustomDataLimits.accepts(full, "a:k0", ByteArray(2)), "replacing a key is not a new entry")

        val heavy = (0 until 4).associate { "a:k$it" to ByteArray(DisplayCustomDataLimits.MAX_VALUE_BYTES) }
        assertFalse(DisplayCustomDataLimits.accepts(heavy, "a:five", ByteArray(1)))
        assertTrue(DisplayCustomDataLimits.isValid(heavy.minus("a:k3")))
        assertFalse(DisplayCustomDataLimits.isValid(mapOf("bad key" to ByteArray(1))))
    }
}
