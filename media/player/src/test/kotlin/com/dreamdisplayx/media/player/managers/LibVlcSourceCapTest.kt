package com.dreamdisplayx.media.player.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the source-to-texture capping rule that keeps a 1440p rendition from being copied and
 * resampled on the game thread for a small display.
 */
class LibVlcSourceCapTest {

    @Test
    fun `a 1440p source on a 1080 texture is capped`() {
        val capped = LibVlcSessionManager.capToTexture(2560, 1442, 1080, 1080)
        assertTrue(capped != null, "a 2560x1442 source must be capped for a 1080x1080 texture")
        val (w, h) = capped
        assertEquals(1080, w)
        assertTrue(h in 600..620, "height should follow the source aspect ratio, was $h")
    }

    @Test
    fun `a source at or near the texture size is left alone`() {
        // Resampling 1920x1080 to 1080 is a loss with no bandwidth win of note, and the renderer
        // already scales it. Anything within the 10% band stays untouched.
        assertNull(LibVlcSessionManager.capToTexture(1920, 1080, 1080, 1080))
        assertNull(LibVlcSessionManager.capToTexture(1080, 1080, 1080, 1080))
        assertNull(LibVlcSessionManager.capToTexture(800, 450, 1080, 1080))
    }

    @Test
    fun `a 4K source is capped hard`() {
        val capped = LibVlcSessionManager.capToTexture(3840, 2160, 1080, 1080)
        assertTrue(capped != null)
        assertEquals(1080, capped.first)
        assertTrue(capped.second in 600..620)
    }

    @Test
    fun `the aspect ratio is preserved`() {
        val capped = LibVlcSessionManager.capToTexture(2560, 1442, 1080, 1080)!!
        val srcRatio = 2560.0 / 1442.0
        val dstRatio = capped.first.toDouble() / capped.second
        assertTrue(kotlin.math.abs(srcRatio - dstRatio) < 0.02, "ratio drifted: $srcRatio vs $dstRatio")
    }

    @Test
    fun `a portrait source keeps its portrait shape`() {
        // The log showed a 1088x1922 vertical clip; it must stay taller than it is wide.
        val capped = LibVlcSessionManager.capToTexture(1088, 1922, 1080, 1080)
        if (capped != null) {
            assertTrue(capped.second > capped.first, "portrait source became landscape: $capped")
        }
    }

    @Test
    fun `degenerate sizes never produce a capped value`() {
        assertNull(LibVlcSessionManager.capToTexture(0, 1442, 1080, 1080))
        assertNull(LibVlcSessionManager.capToTexture(2560, 0, 1080, 1080))
        assertNull(LibVlcSessionManager.capToTexture(2560, 1442, 0, 1080))
        assertNull(LibVlcSessionManager.capToTexture(2560, 1442, 1080, 0))
    }

    @Test
    fun `the capped size is always even so chroma alignment never breaks`() {
        for (srcW in listOf(2560, 2562, 3840, 4094)) {
            val capped = LibVlcSessionManager.capToTexture(srcW, srcW / 2, 1080, 1080) ?: continue
            assertEquals(0, capped.first % 2, "odd width for source $srcW: ${capped.first}")
            assertEquals(0, capped.second % 2, "odd height for source $srcW: ${capped.second}")
        }
    }
}