package com.dreamdisplays.media.player.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameMemoryBudgetTest {
    private val hdFrame = 1920 * 1080 * 3 / 2

    @Test
    fun `an unknown frame size keeps the wanted queue`() {
        assertEquals(28, FrameMemoryBudget.framesFor(frameBytes = 0, wanted = 28, floor = 6, outsideQueue = 6))
    }

    @Test
    fun `a single playback keeps its full queue`() {
        assertEquals(28, FrameMemoryBudget.framesFor(hdFrame, wanted = 28, floor = 6, outsideQueue = 6))
    }

    @Test
    fun `many live playbacks shrink each queue but never below the floor`() {
        val alone = FrameMemoryBudget.framesFor(Int.MAX_VALUE / 2, wanted = 28, floor = 6, outsideQueue = 6)
        assertEquals(6, alone, "A frame bigger than the whole budget still gets the floor.")

        repeat(200) { FrameMemoryBudget.acquire() }
        try {
            val crowded = FrameMemoryBudget.framesFor(hdFrame, wanted = 28, floor = 6, outsideQueue = 6)
            assertTrue(crowded in 6..<28, "Expected a reduced queue, got $crowded.")
            assertEquals(4, FrameMemoryBudget.framesFor(hdFrame, wanted = 4, floor = 6, outsideQueue = 6))
        } finally {
            repeat(200) { FrameMemoryBudget.release() }
        }
    }

    @Test
    fun `packet caches share their budget but keep a usable floor`() {
        val wanted = 96L * 1024 * 1024
        assertEquals(wanted, FrameMemoryBudget.packetCacheBytesFor(wanted))
        repeat(200) { FrameMemoryBudget.acquire() }
        try {
            assertEquals(16L * 1024 * 1024, FrameMemoryBudget.packetCacheBytesFor(wanted))
        } finally {
            repeat(200) { FrameMemoryBudget.release() }
        }
    }
}
