package com.dreamdisplayx.media.player.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the thumbnail geometry and pixel packing.
 *
 * Extracting the frame needs FFmpeg natives, but "does a 16:9 source stay 16:9 inside a
 * 320x180 card" and "is a dark blue pixel really dark blue" are pure integers that fail
 * silently in the UI, so they are tested here instead of on a device.
 */
class ThumbScalePolicyTest {

    @Test
    fun aMatchingAspectRatioFillsTheWholeCard() {
        val r = ThumbScalePolicy.letterbox(1280, 720, 320, 180)
        assertEquals(0, r[0])
        assertEquals(0, r[1])
        assertEquals(320, r[2])
        assertEquals(180, r[3])
    }

    @Test
    fun aTallerSourceIsPillarboxedNotStretched() {
        // 9:16 portrait into a 16:9 card: the height is the constraint, so the picture
        // keeps its shape and black bars appear on both sides.
        val r = ThumbScalePolicy.letterbox(720, 1280, 320, 180)
        assertEquals(180, r[3], "the height must fill the card")
        val expectedW = (720.0 * 180 / 1280).toInt()
        assertEquals(expectedW, r[2], "width must follow the source aspect, not the card's")
        assertTrue(r[2] < 320, "a portrait source cannot fill a landscape card")
        // Centred: the two bars may differ by 1px because an odd leftover cannot be split
        // evenly, and forcing equality would push the picture off-centre by a full pixel.
        val leftBar = r[0]
        val rightBar = 320 - r[2] - r[0]
        assertTrue(kotlin.math.abs(leftBar - rightBar) <= 1, "pillarbox must be centred: $leftBar vs $rightBar")
    }

    @Test
    fun aWiderSourceIsLetterboxed() {
        // 21:9 into 16:9: width fills, bars top and bottom.
        val r = ThumbScalePolicy.letterbox(2560, 1080, 320, 180)
        assertEquals(320, r[2])
        assertTrue(r[3] <= 180)
        val topBar = r[1]
        val bottomBar = 180 - r[3] - r[1]
        assertTrue(kotlin.math.abs(topBar - bottomBar) <= 1, "letterbox must be centred: $topBar vs $bottomBar")
    }

    @Test
    fun degenerateSizesYieldAZeroAreaRectInsteadOfNegativeBounds() {
        val r = ThumbScalePolicy.letterbox(0, 720, 320, 180)
        assertTrue(r[2] >= 0 && r[3] >= 0, "no negative width/height: ${r.toList()}")
        val r2 = ThumbScalePolicy.letterbox(1280, 720, 0, 0)
        assertTrue(r2[2] >= 0 && r2[3] >= 0)
    }

    @Test
    fun theRectNeverExceedsTheCard() {
        // A 1x10000 source is the worst case for integer rounding.
        val r = ThumbScalePolicy.letterbox(1, 10_000, 320, 180)
        assertTrue(r[0] >= 0 && r[1] >= 0, "origin must be inside the card: ${r.toList()}")
        assertTrue(r[0] + r[2] <= 320, "must not overflow horizontally: ${r.toList()}")
        assertTrue(r[1] + r[3] <= 180, "must not overflow vertically: ${r.toList()}")
        assertTrue(r[2] >= 1 && r[3] >= 1, "at least one pixel must be drawn: ${r.toList()}")
    }

    @Test
    fun bgraChannelOrderIsHonoured() {
        // FFmpeg's AV_PIX_FMT_BGRA stores blue first; reading it as RGBA would turn a
        // pure-red source into pure-blue thumbnails.
        val bgra = byteArrayOf(0, 0, (255).toByte(), (255).toByte()) // B=0 G=0 R=255 A=255
        assertEquals(0xFF0000, ThumbScalePolicy.bgraToRgbInt(bgra, 0))
        val blue = byteArrayOf((255).toByte(), 0, 0, (255).toByte())
        assertEquals(0x0000FF, ThumbScalePolicy.bgraToRgbInt(blue, 0))
    }

    @Test
    fun anOutOfBoundsPixelReadsAsBlackRatherThanThrowing() {
        val bgra = byteArrayOf(1, 2, 3, 4)
        assertEquals(0, ThumbScalePolicy.bgraToRgbInt(bgra, 4))
        assertEquals(0, ThumbScalePolicy.bgraToRgbInt(bgra, -1))
        assertEquals(0, ThumbScalePolicy.bgraToRgbInt(ByteArray(0), 0))
    }

    @Test
    fun packedRowDetectionMatchesTheSolverOutput() {
        assertTrue(ThumbScalePolicy.isPackedRows(1280, 320), "320px x 4 bytes is packed")
        assertFalse(ThumbScalePolicy.isPackedRows(1282, 320), "a padded stride must be detected")
    }

    @Test
    fun rowOffsetFollowsTheStride() {
        assertEquals(0, ThumbScalePolicy.rowOffset(0, 1280))
        assertEquals(1280 * 7, ThumbScalePolicy.rowOffset(7, 1280))
    }
}