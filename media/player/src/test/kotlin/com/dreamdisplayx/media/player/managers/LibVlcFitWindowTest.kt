package com.dreamdisplayx.media.player.managers

import com.dreamdisplayx.api.media.model.StretchMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the letterbox/crop window geometry inside a display texture.
 *
 * The reported "switching to CROP stops the picture while the sound and the progress bar keep
 * running" bug came from the old inline geometry: CROP scaled into a `fitW`x`fitH` intermediate whose
 * width exceeds the destination, then narrowed the source `limit()` once per row so the second row's
 * `position()` landed past that limit and threw. `publishFrame` swallows publish failures, so no frame
 * reached the texture while libvlc kept decoding and the progress bar kept advancing.
 *
 * These cases use the resolutions from the reporter's log (a 1080x1080 display fed 2560x1442 and
 * 1088x1922 sources) plus a portrait source on a portrait texture.
 */
class LibVlcFitWindowTest {

    private fun crop(srcW: Int, srcH: Int, dstW: Int, dstH: Int) =
        LibVlcSessionManager.fitWindow(StretchMode.CROP, srcW, srcH, dstW, dstH)

    private fun letterbox(srcW: Int, srcH: Int, dstW: Int, dstH: Int) =
        LibVlcSessionManager.fitWindow(StretchMode.LETTERBOX, srcW, srcH, dstW, dstH)

    @Test
    fun `crop always covers the destination and overflows symmetrically`() {
        // 2560x1442 into 1080x1080: the window must be at least as large as the texture on both axes.
        val win = crop(2560, 1442, 1080, 1080)
        assertTrue(win.fitW >= 1080, "crop window narrower than the texture: $win")
        assertTrue(win.fitH >= 1080, "crop window shorter than the texture: $win")
        // Centered: the two offsets must be equal, and both negative together with the overflow.
        assertEquals(win.offX, (1080 - win.fitW) / 2)
        assertEquals(win.offY, (1080 - win.fitH) / 2)
        assertTrue(win.offX <= 0 && win.offY <= 0, "crop window does not overflow: $win")
    }

    @Test
    fun `crop of a portrait source on a landscape texture crops the height`() {
        val win = crop(1088, 1922, 1920, 1080)
        assertTrue(win.fitW >= 1920, "crop window narrower than the texture: $win")
        assertTrue(win.fitH >= 1080, "crop window shorter than the texture: $win")
        assertTrue(win.offY < 0, "portrait crop should overflow vertically: $win")
    }

    @Test
    fun `letterbox always fits inside the destination with non-negative bars`() {
        for ((srcW, srcH) in listOf(2560 to 1442, 1088 to 1922, 480 to 866, 368 to 642)) {
            val win = letterbox(srcW, srcH, 1080, 1080)
            assertTrue(win.fitW in 1..1080, "letterbox window wider than the texture: $win")
            assertTrue(win.fitH in 1..1080, "letterbox window taller than the texture: $win")
            assertTrue(win.offX >= 0 && win.offY >= 0, "letterbox bars must not be negative: $win")
        }
    }

    @Test
    fun `the window is never empty for any mode and any sane size`() {
        val modes = listOf(StretchMode.LETTERBOX, StretchMode.CROP)
        for (mode in modes) {
            for (srcW in listOf(1, 2, 3, 360, 2560)) {
                for (srcH in listOf(1, 2, 3, 202, 1442)) {
                    for ((dstW, dstH) in listOf(1 to 1, 2 to 2, 360 to 360, 1080 to 1080)) {
                        val win = LibVlcSessionManager.fitWindow(mode, srcW, srcH, dstW, dstH)
                        assertTrue(win.fitW >= 1, "$mode ${srcW}x$srcH -> ${dstW}x$dstH gave $win")
                        assertTrue(win.fitH >= 1, "$mode ${srcW}x$srcH -> ${dstW}x$dstH gave $win")
                    }
                }
            }
        }
    }

    @Test
    fun `degenerate sizes fall back to the destination instead of dividing by zero`() {
        for (mode in listOf(StretchMode.LETTERBOX, StretchMode.CROP, StretchMode.STRETCH)) {
            assertEquals(LibVlcSessionManager.FitWindow(1080, 1080, 0, 0),
                LibVlcSessionManager.fitWindow(mode, 0, 1442, 1080, 1080))
            assertEquals(LibVlcSessionManager.FitWindow(1080, 1080, 0, 0),
                LibVlcSessionManager.fitWindow(mode, 2560, 1442, 0, 1080))
        }
    }
}