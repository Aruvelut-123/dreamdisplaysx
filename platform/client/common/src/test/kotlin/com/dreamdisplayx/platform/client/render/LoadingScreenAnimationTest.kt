package com.dreamdisplayx.platform.client.render

import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadingScreenAnimationTest {
    private fun frame(ms: Long, width: Int = 640, height: Int = 360) =
        LoadingScreenAnimation.frame(ms * 1_000_000L, width, height)

    @Test
    fun `intro fades a centered icon before moving or revealing text`() {
        val initial = frame(0)
        assertEquals(0f, initial.iconAlpha)
        assertEquals(0.5f, initial.icon.centerX, 0.00001f)
        assertEquals(0.5f, initial.icon.centerY, 0.00001f)
        assertEquals(0f, initial.titleAlpha)
        assertEquals(0f, initial.spinnerAlpha)
        assertTrue(initial.ringBrushes.isEmpty())
        val halfway = frame(250)
        assertEquals(0.5f, halfway.iconAlpha, 0.00001f)
        assertEquals(initial.icon, halfway.icon)
        val faded = frame(650)
        assertEquals(1f, faded.iconAlpha)
        assertEquals(initial.icon, faded.icon)
        assertEquals(0f, faded.titleAlpha)
    }

    @Test
    fun `slide moves left while title rises and ends as a centered lockup`() {
        val from = frame(650)
        val mid = frame(975)
        val settled = frame(1300)
        assertTrue(from.icon.centerX > mid.icon.centerX)
        assertTrue(mid.icon.centerX > settled.icon.centerX)
        assertTrue(from.glyphs.first().rect.y > mid.glyphs.first().rect.y)
        assertTrue(mid.glyphs.first().rect.y > settled.glyphs.first().rect.y)
        assertEquals(0.5f, mid.titleAlpha, 0.00001f)
        assertEquals(1f, settled.titleAlpha)
        assertEquals(0.5f, (settled.icon.x + settled.title.right) / 2, 0.00001f)
        assertEquals(settled.icon.centerY, settled.title.centerY, 0.00001f)
        assertTrue(settled.icon.right < settled.title.x)
    }

    @Test
    fun `spinner appears only after intro and stays below the title not the entire lockup`() {
        for (ms in listOf(0L, 250L, 975L, 1300L, 1450L)) {
            assertEquals(0f, frame(ms).spinnerAlpha)
            assertTrue(frame(ms).ringBrushes.isEmpty())
        }
        val appearing = frame(1560)
        assertEquals(0.5f, appearing.spinnerAlpha, 0.00001f)
        val shown = frame(1670)
        assertEquals(1f, shown.spinnerAlpha)
        assertEquals(shown.title.centerX, shown.spinner.centerX, 0.00001f)
        assertTrue(shown.spinner.centerX > 0.5f)
        assertTrue(shown.spinner.y > shown.glyphs.maxOf { it.rect.bottom })
        assertFalse(shown.ringBrushes.isEmpty())
    }

    @Test
    fun `wave is bounded periodic and staggered across letters`() {
        val first = frame(1950)
        val next = frame(4550)
        first.glyphs.zip(next.glyphs).forEach { (a, b) ->
            assertEquals(a.rect.y, b.rect.y, 0.00001f)
            assertTrue(abs(a.rect.y - first.title.y) <= 2.5f / 360f + 0.00001f)
        }
        assertTrue(first.glyphs.map { it.rect.y }.distinct().size > 5)
        assertNotEquals(first.glyphs.first().rect.y, frame(2600).glyphs.first().rect.y)
        assertEquals(14, first.glyphs.size)
    }

    @Test
    fun `rainbow varies spatially from warm to cool with continuous adjacent edge colors`() {
        val f = frame(0)
        val red = f.glyphs.first().leftRgb
        val violet = f.glyphs.last().rightRgb
        assertEquals(255, (red shr 16) and 255)
        assertEquals(255, violet and 255)
        assertNotEquals(red, violet)
        f.glyphs.zipWithNext().filter { (a, b) -> a.u1 == b.u0 }.forEach { (a, b) ->
            assertEquals(a.rightRgb, b.leftRgb)
        }
        assertNotEquals(f.glyphs.map { it.leftRgb }, frame(3000).glyphs.map { it.leftRgb })
        assertEquals(LoadingScreenAnimation.rainbow(0f), LoadingScreenAnimation.rainbow(-1f))
        assertEquals(LoadingScreenAnimation.rainbow(1f), LoadingScreenAnimation.rainbow(2f))
    }

    @Test
    fun `rainbow flows rightward and loops without a color jump`() {
        assertEquals(frame(2000).glyphs.map { it.leftRgb }, frame(10000).glyphs.map { it.leftRgb })
        val left = LoadingScreenAnimation.rainbow(0.25f, 0.1f)
        val travelledRight = LoadingScreenAnimation.rainbow(0.5f, 0.305f)
        fun assertClose(a: Int, b: Int, tolerance: Int = 1) {
            for (shift in listOf(0, 8, 16)) assertTrue(abs(((a shr shift) and 255) - ((b shr shift) and 255)) <= tolerance)
        }
        assertClose(left, travelledRight)
        for (position in listOf(0f, 0.2f, 0.5f, 0.9f, 1f)) {
            assertClose(LoadingScreenAnimation.rainbow(position, 0.99999f), LoadingScreenAnimation.rainbow(position, 0f))
        }
        val a = frame(7999)
        val b = frame(8000)
        a.glyphs.zip(b.glyphs).forEach { (before, after) -> assertClose(before.leftRgb, after.leftRgb) }
        assertTrue(frame(2000).glyphs.map { it.leftRgb }.distinct().size > 10)
    }

    @Test
    fun `layout fits portrait square wide and tiny displays without stretching the icon or circle`() {
        for ((w, h) in listOf(640 to 360, 360 to 640, 2000 to 120, 1 to 1, 1 to 32, 32 to 1, 1920 to 1080)) {
            for (ms in listOf(0L, 250L, 650L, 975L, 1300L, 1500L, 2000L, 5000L)) {
                val f = frame(ms, w, h)
                val rects = listOf(f.icon, f.title, f.spinner) + f.glyphs.map { it.rect } + f.ringBrushes
                rects.forEach { r ->
                    assertTrue(r.x >= 0 && r.y >= 0 && r.right <= 1f && r.bottom <= 1f, "$w x $h @ $ms: $r")
                    assertTrue(r.width > 0f && r.height > 0f)
                }
                assertEquals(f.icon.width * w, f.icon.height * h, 0.001f)
                assertEquals(f.spinner.width * w, f.spinner.height * h, 0.001f)
            }
        }
        val invalid = frame(0, 0, -1)
        assertTrue(invalid.icon.width.isFinite() && invalid.icon.height.isFinite())
    }

    @Test
    fun `spinner rotates breathes loops continuously and keeps geometry bounded`() {
        val small = frame(3050)
        val large = frame(3850)
        assertTrue(large.ringBrushes.size > small.ringBrushes.size * 3)
        assertTrue(large.ringBrushes.size < 90)
        assertEquals(frame(2250).ringBrushes, frame(3850).ringBrushes)
        assertNotEquals(frame(2000).ringBrushes, frame(2100).ringBrushes)
        val before = frame(3049).ringBrushes.last()
        val after = frame(3050).ringBrushes.last()
        assertEquals(before.centerX, after.centerX, 0.001f)
        assertEquals(before.centerY, after.centerY, 0.001f)
    }

    @Test
    fun `shader replay backdrop clears every primary sprite before blending`() {
        for (normalBase in listOf(0.005f, 0f)) { // Flat and conforming base lifts.
            val foreground = LoadingScreenGeometry.backgroundLift(normalBase, false) + LoadingScreenGeometry.CONTENT_LIFT
            val replayBackground = LoadingScreenGeometry.backgroundLift(0.01f, true)
            assertTrue(replayBackground > foreground)
        }
        assertEquals(0.005f, LoadingScreenGeometry.backgroundLift(0.005f, false))
    }

    @Test
    fun `all video UV rotations map back to an upright top-left loading canvas`() {
        for (x in listOf(0f, 0.1f, 0.7f, 1f)) for (y in listOf(0f, 0.3f, 0.8f, 1f)) {
            // ConformingMesh maps physical (right, up) positions to video texture coordinates.
            val rotations = listOf(x to 1f - y, 1f - y to 1f - x, 1f - x to y, y to x)
            rotations.forEachIndexed { turns, (u, v) ->
                val (actualX, actualY) = LoadingScreenGeometry.uprightUv(u, v, turns)
                assertEquals(x, actualX, 0.00001f)
                assertEquals(1f - y, actualY, 0.00001f)
            }
        }
    }

    @Test
    fun `phase boundaries are continuous and alpha stays in range`() {
        for (boundary in listOf(500L, 650L, 1300L, 1450L, 1670L)) {
            val a = frame(boundary - 1)
            val b = frame(boundary)
            assertEquals(a.icon.centerX, b.icon.centerX, 0.001f)
            assertEquals(a.iconAlpha, b.iconAlpha, 0.001f)
            assertEquals(a.titleAlpha, b.titleAlpha, 0.001f)
            assertEquals(a.spinnerAlpha, b.spinnerAlpha, 0.001f)
        }
        for (ms in -100L..2000L step 10) {
            val f = frame(ms)
            assertTrue(f.iconAlpha in 0f..1f && f.titleAlpha in 0f..1f && f.spinnerAlpha in 0f..1f)
        }
        assertEquals(frame(0), frame(-100))
    }

    @Test
    fun `clock starts on first visible frame and resets for a fresh media generation`() {
        val clock = LoadingScreenClock()
        assertEquals(0L, clock.elapsed(true, false, false, 1, 5_000))
        assertEquals(250L, clock.elapsed(true, false, false, 1, 5_250))
        assertEquals(250L, clock.elapsed(true, false, false, 1, 5_250))
        assertEquals(250L, clock.elapsed(true, false, false, 1, 5_255, replay = true)) // Same phase despite time spent in shaders.
        assertEquals(0L, clock.elapsed(true, false, false, 2, 5_260))
        assertEquals(100L, clock.elapsed(true, false, false, 2, 5_360))
    }

    @Test
    fun `clock immediately yields to video errors and disabling without enforcing the intro`() {
        for ((enabled, ready, error) in listOf(Triple(false, false, false), Triple(true, true, false), Triple(true, false, true))) {
            val clock = LoadingScreenClock()
            assertEquals(0L, clock.elapsed(true, false, false, 1, 0))
            assertNull(clock.elapsed(enabled, ready, error, 1, 1))
            assertEquals(0L, clock.elapsed(true, false, false, 1, 2))
        }
    }

    @Test
    fun `bundled alpha atlas matches glyph metrics and writes deterministic preview fixtures`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val assets = File(root, "platform/resources/src/main/resources/assets/dreamdisplayx/textures/gui")
        for ((name, size) in listOf("loading_wordmark.png" to (618 to 96), "loading_dot.png" to (32 to 32))) {
            val png = File(assets, name).readBytes()
            assertEquals(0x89504E47.toInt(), ByteBuffer.wrap(png, 0, 4).int)
            assertEquals(size.first, ByteBuffer.wrap(png, 16, 4).int)
            assertEquals(size.second, ByteBuffer.wrap(png, 20, 4).int)
            assertEquals(6, png[25].toInt(), "RGBA alpha is required for $name")
        }
        assertEquals(0f, frame(2000).glyphs.first().u0)
        assertEquals(1f, frame(2000).glyphs.last().u1)
        fun LoadingScreenAnimation.Rect.json() = "{\"x\":$x,\"y\":$y,\"w\":$width,\"h\":$height}"
        val frames = (0..240).joinToString(",") { sample ->
            val f = LoadingScreenAnimation.frame(sample * 16_666_667L, 640, 360)
            val glyphs = f.glyphs.joinToString(",") {
                "{\"r\":${it.rect.json()},\"u0\":${it.u0},\"u1\":${it.u1},\"left\":${it.leftRgb},\"right\":${it.rightRgb}}"
            }
            "{\"icon\":${f.icon.json()},\"ia\":${f.iconAlpha},\"ta\":${f.titleAlpha}," +
                "\"sa\":${f.spinnerAlpha},\"glyphs\":[$glyphs],\"ring\":[${f.ringBrushes.joinToString(",") { it.json() }}]}"
        }
        // A preview consumes the actual JVM model rather than maintaining a second JavaScript choreography.
        File(root, "build/reports/loading-screen/frames.json").apply {
            parentFile.mkdirs()
            writeText("[$frames]")
        }
    }

    @Test
    fun `independent displays and signed nanoTime origins do not share a phase`() {
        val first = LoadingScreenClock()
        val second = LoadingScreenClock()
        assertEquals(0L, first.elapsed(true, false, false, 1, -1000))
        assertEquals(500L, first.elapsed(true, false, false, 1, -500))
        assertEquals(0L, second.elapsed(true, false, false, 1, -500))
        assertEquals(1000L, first.elapsed(true, false, false, 1, 0))
        assertEquals(0L, first.elapsed(true, false, false, 1, -2000))
    }
}
