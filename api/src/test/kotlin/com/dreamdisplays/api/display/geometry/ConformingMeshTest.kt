package com.dreamdisplays.api.display.geometry

import com.dreamdisplays.api.display.model.property.DisplayFacing
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConformingMeshTest {
    @Test
    fun shapedVariantsComeFromThePlatformNotFromTheName() {
        val concrete = DisplaySurface(
            "minecraft:black_concrete",
            listOf("minecraft:black_concrete_stairs", "minecraft:black_concrete_slab"),
        )
        assertEquals(SurfaceRole.FULL, concrete.role("BLACK_CONCRETE"))
        assertEquals(SurfaceRole.SHAPED, concrete.role("minecraft:black_concrete_stairs"))
        assertEquals(SurfaceRole.SHAPED, concrete.role("BLACK_CONCRETE_SLAB"))
        assertEquals(SurfaceRole.AIR, concrete.role("minecraft:cave_air"))
        assertEquals(SurfaceRole.FOREIGN, concrete.role("minecraft:oak_stairs"))

        val bricks = DisplaySurface("minecraft:stone_bricks", listOf("minecraft:stone_brick_slab"))
        assertTrue(bricks.accepts("STONE_BRICK_SLAB"))
        assertEquals(SurfaceRole.FOREIGN, bricks.role("minecraft:stone_bricks_slab"))
        assertTrue(DisplaySurface("minecraft:black_concrete", emptyList()).role("black_concrete_stairs") == SurfaceRole.FOREIGN)
    }

    @Test
    fun censusAllowsAirOnlyWhenAStairIsPresent() {
        val staircase = SurfaceCensus().add(SurfaceRole.SHAPED).add(SurfaceRole.AIR).add(SurfaceRole.SHAPED)
        assertTrue(staircase.conforming)
        val holedWall = SurfaceCensus().add(SurfaceRole.FULL).add(SurfaceRole.AIR)
        assertTrue(!holedWall.flat && !holedWall.conforming)
        val wall = SurfaceCensus().add(SurfaceRole.FULL).add(SurfaceRole.FULL)
        assertTrue(wall.flat)
    }

    @Test
    fun fullCubeWallMatchesTheFlatScreenUv() {
        val quads = buildConformingMesh(
            listOf(ShapedBlock(0, 0, 0, listOf(FULL))),
            width = 1, height = 1, depth = 1, facing = DisplayFacing.NORTH,
        )
        assertEquals(1, quads.size)
        val quad = quads.single()
        assertEquals(-1f, quad.nz)
        val bottomLeft = quad.vertices.single { it.x == 1f && it.y == 0f }
        assertEquals(0f, bottomLeft.u)
        assertEquals(1f, bottomLeft.v)
        val topRight = quad.vertices.single { it.x == 0f && it.y == 1f }
        assertEquals(1f, topRight.u)
        assertEquals(0f, topRight.v)
        assertTrue(normalMatchesWinding(quad))
    }

    @Test
    fun bottomSlabSitsOnTheHalfHeightAndWrapsTheTop() {
        val quads = buildConformingMesh(
            listOf(ShapedBlock(0, 0, 0, listOf(BOTTOM))),
            width = 1, height = 1, depth = 1, facing = DisplayFacing.NORTH,
        )
        val fronts = quads.filter { it.nz < 0f }
        val tops = quads.filter { it.ny > 0f }
        assertTrue(fronts.isNotEmpty() && tops.isNotEmpty())
        assertTrue(fronts.all { q -> q.vertices.all { it.y <= 0.5f + 1e-3f } })
        assertTrue(tops.all { q -> q.vertices.all { abs(it.y - 0.5f) < 1e-3f } })
        assertDraped(quads)
    }

    @Test
    fun floorSlabKeepsThePictureOnTheTopFace() {
        val quads = buildConformingMesh(
            listOf(ShapedBlock(0, 0, 0, listOf(ShapeBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)))),
            width = 1, height = 1, depth = 1, facing = DisplayFacing.UP,
        )
        assertEquals(1, quads.size)
        val top = quads.single()
        assertEquals(1f, top.ny)
        assertTrue(top.vertices.all { abs(it.y - 0.5f) < 1e-3f })
        val near = top.vertices.single { it.x == 0f && it.z == 0f }
        assertEquals(0f, near.u)
        assertEquals(0f, near.v)
        val far = top.vertices.single { it.x == 0f && it.z == 1f }
        assertEquals(1f, far.v)
    }

    @Test
    fun faceTuckedBehindAStepGetsNoPicture() {
        val quads = buildConformingMesh(
            listOf(
                ShapedBlock(0, 0, 0, listOf(BOTTOM, STEP)),
                ShapedBlock(0, 1, 1, listOf(BOTTOM, STEP)),
            ),
            width = 1, height = 2, depth = 2, facing = DisplayFacing.NORTH,
        )
        assertTrue(quads.none { it.ny < 0f }, "the underside of the upper stair hides behind the lower one")
    }

    @Test
    fun everyFacingWindsOutwardAndStaysInsideTheTexture() {
        for (facing in DisplayFacing.entries) {
            for (turns in 0..3) {
                val quads = buildConformingMesh(
                    listOf(ShapedBlock(0, 0, 0, listOf(BOTTOM, STEP))),
                    width = 1, height = 1, depth = 1, facing = facing, quarterTurns = turns,
                )
                assertTrue(quads.isNotEmpty(), "$facing")
                quads.forEach { quad ->
                    assertTrue(normalMatchesWinding(quad), "$facing winding")
                    assertTrue(quad.vertices.all { it.u in -1e-4f..1.0001f && it.v in -1e-4f..1.0001f }, "$facing uv")
                }
            }
        }
    }

    @Test
    fun straightStairWrapsTheRiserAndTheTread() {
        val quads = buildConformingMesh(
            listOf(ShapedBlock(0, 0, 0, listOf(BOTTOM, STEP))),
            width = 1, height = 1, depth = 1, facing = DisplayFacing.NORTH,
        )
        val fronts = quads.filter { it.nz < 0f }
        assertTrue(fronts.any { q -> q.vertices.all { it.z == 0f && it.y <= 0.5f + 1e-3f } }, "lower front")
        assertTrue(fronts.any { q -> q.vertices.all { abs(it.z - 0.5f) < 1e-3f } }, "upper front")
        val treads = quads.filter { it.ny > 0f }
        assertTrue(treads.isNotEmpty())
        assertTrue(treads.all { q -> q.vertices.all { abs(it.y - 0.5f) < 1e-3f && it.z <= 0.5f + 1e-3f } })
        assertTrue(quads.none { q -> q.ny > 0f && q.vertices.all { abs(it.y - 1f) < 1e-3f } }, "the step's top is rim")
        assertDraped(quads)
        assertTrue(uvSpan(treads) > 0.1f, "the tread shows picture, not a smeared line")
    }

    @Test
    fun grooveWrapsBothSideWallsAndStaysContinuous() {
        val quads = buildConformingMesh(
            listOf(
                ShapedBlock(0, 0, 0, listOf(FULL)), ShapedBlock(0, 0, 1, listOf(FULL)),
                ShapedBlock(1, 0, 1, listOf(FULL)),
                ShapedBlock(2, 0, 0, listOf(FULL)), ShapedBlock(2, 0, 1, listOf(FULL)),
            ),
            width = 3, height = 1, depth = 2, facing = DisplayFacing.NORTH,
        )
        for (plane in listOf(1f, 2f)) {
            val wall = quads.filter { q -> q.nx != 0f && q.vertices.all { abs(it.x - plane) < 1e-3f } }
            assertTrue(wall.isNotEmpty(), "wall at x=$plane")
            assertTrue(uvSpan(wall) > 0.1f, "the wall at x=$plane spans picture, not one smeared column")
        }
        assertDraped(quads)
        val all = quads.flatMap { it.vertices }
        assertEquals(0f, all.minOf { it.u }, 1e-4f)
        assertEquals(1f, all.maxOf { it.u }, 1e-4f)
    }

    @Test
    fun staircasePictureContinuesFromOneStepOntoTheNext() {
        val quads = buildConformingMesh(
            listOf(
                ShapedBlock(0, 0, 0, listOf(BOTTOM, STEP)),
                ShapedBlock(0, 1, 1, listOf(BOTTOM, STEP)),
            ),
            width = 1, height = 2, depth = 2, facing = DisplayFacing.NORTH,
        )
        assertDraped(quads)
        val all = quads.flatMap { it.vertices }
        assertEquals(1f, all.filter { it.y == 0f }.maxOf { it.v }, 1e-4f)
        assertEquals(0f, all.filter { abs(it.y - 2f) < 1e-3f }.minOf { it.v }, 1e-4f)
    }

    @Test
    fun aStairInAWallBarelyDisturbsTheRestOfThePicture() {
        val width = 9
        val height = 5
        val stairX = 4
        val blocks = ArrayList<ShapedBlock>()
        for (x in 0 until width) for (y in 0 until height) {
            blocks += if (x == stairX && y == 0) ShapedBlock(x, y, 0, listOf(BOTTOM, STEP)) else ShapedBlock(x, y, 0, listOf(FULL))
        }
        val quads = buildConformingMesh(blocks, width, height, depth = 1, facing = DisplayFacing.NORTH)
        assertDraped(quads)
        assertTrue(uvSpan(quads.filter { it.ny > 0f }) > 0.02f)
        for (v in quads.filter { it.nz < 0f }.flatMap { it.vertices }.filter { it.z == 0f }) {
            val away = maxOf(stairX - v.x, v.x - (stairX + 1), v.y - 1f)
            val shift = maxOf(abs(v.u - (width - v.x) / width) * width, abs(v.v - (1f - v.y / height)) * height)
            if (away >= 2f) assertTrue(shift < 0.1f, "shifted $shift of a block ${away} blocks from the stair at $v")
            if (away >= 4f) assertTrue(shift < 0.03f, "shifted $shift of a block ${away} blocks from the stair at $v")
        }
    }

    @Test
    fun aFloorOfSlabsAndStairsDrapesWithoutSeamsOrFolds() {
        val blocks = listOf(
            ShapedBlock(0, 0, 0, listOf(BOTTOM)),
            ShapedBlock(1, 0, 0, listOf(FULL)),
            ShapedBlock(2, 0, 0, listOf(BOTTOM, ShapeBox(0.5, 0.5, 0.0, 1.0, 1.0, 1.0))),
            ShapedBlock(0, 0, 1, listOf(FULL)),
            ShapedBlock(1, 0, 1, listOf(FULL)),
            ShapedBlock(2, 0, 1, listOf(FULL)),
        )
        val quads = buildConformingMesh(blocks, width = 3, height = 2, depth = 1, facing = DisplayFacing.UP)
        assertDraped(quads)
        assertTrue(uvSpan(quads.filter { it.ny == 0f }) > 0.05f)
    }

    @Test
    fun stairsFacingEachOtherBetweenSlabsLeaveNoSeam() {
        val stepSouth = ShapeBox(0.0, 0.5, 0.5, 1.0, 1.0, 1.0)
        val stepNorth = ShapeBox(0.0, 0.5, 0.0, 1.0, 1.0, 0.5)
        val stepEast = ShapeBox(0.5, 0.5, 0.0, 1.0, 1.0, 1.0)
        val stepWest = ShapeBox(0.0, 0.5, 0.0, 0.5, 1.0, 1.0)
        val between = listOf(
            ShapedBlock(0, 0, 0, listOf(BOTTOM)), ShapedBlock(1, 0, 0, listOf(BOTTOM, stepSouth)),
            ShapedBlock(2, 0, 0, listOf(BOTTOM)),
            ShapedBlock(0, 0, 1, listOf(BOTTOM)), ShapedBlock(1, 0, 1, listOf(BOTTOM, stepNorth)),
            ShapedBlock(2, 0, 1, listOf(BOTTOM)),
        )
        val betweenQuads = buildConformingMesh(between, width = 3, height = 2, depth = 1, facing = DisplayFacing.UP)
        assertDraped(betweenQuads)
        assertTrue(uvSpan(betweenQuads.filter { it.nz != 0f }) > 0.05f, "the lone stairs' risers show picture")

        val row = listOf(
            ShapedBlock(0, 0, 0, listOf(BOTTOM)), ShapedBlock(1, 0, 0, listOf(BOTTOM, stepEast)),
            ShapedBlock(2, 0, 0, listOf(BOTTOM, stepWest)), ShapedBlock(3, 0, 0, listOf(BOTTOM)),
        )
        val rowQuads = buildConformingMesh(row, width = 4, height = 1, depth = 1, facing = DisplayFacing.UP)
        assertDraped(rowQuads)
        for (plane in listOf(1.5f, 2.5f)) {
            val riser = rowQuads.filter { q -> q.nx != 0f && q.vertices.all { abs(it.x - plane) < 1e-3f } }
            assertTrue(uvSpan(riser) > 0.05f, "the riser at x=$plane unfolds into picture")
        }
    }

    private fun assertDraped(quads: List<ConformQuad>) {
        assertTrue(quads.isNotEmpty())
        var orientation = 0
        for (q in quads) {
            assertTrue(normalMatchesWinding(q), "winding of $q")
            assertTrue(q.vertices.all { it.u in -1e-4f..1.0001f && it.v in -1e-4f..1.0001f }, "uv of $q")
            val area = (q.v1.u - q.v0.u) * (q.v2.v - q.v0.v) - (q.v2.u - q.v0.u) * (q.v1.v - q.v0.v)
            assertTrue(abs(area) > 1e-9f, "a quad collapsed to a line of picture: $q")
            val sign = if (area > 0f) 1 else -1
            if (orientation == 0) orientation = sign
            assertEquals(orientation, sign, "picture folded back over itself at $q")
        }
        assertNoSeams(quads)
    }

    private fun uvSpan(quads: List<ConformQuad>): Float {
        val vs = quads.flatMap { it.vertices }
        if (vs.isEmpty()) return 0f
        return maxOf(vs.maxOf { it.u } - vs.minOf { it.u }, vs.maxOf { it.v } - vs.minOf { it.v })
    }

    private fun assertNoSeams(quads: List<ConformQuad>) {
        for (a in quads) for (b in quads) {
            if (a === b) continue
            for (va in a.vertices) for (vb in b.vertices) {
                if (abs(va.x - vb.x) < 1e-4f && abs(va.y - vb.y) < 1e-4f && abs(va.z - vb.z) < 1e-4f) {
                    assertTrue(abs(va.u - vb.u) < 1e-4f && abs(va.v - vb.v) < 1e-4f, "seam at $va / $vb")
                    assertTrue(va.ox == vb.ox && va.oy == vb.oy && va.oz == vb.oz, "lifted faces part at $va / $vb")
                }
            }
        }
    }

    @Test
    fun clipKeepsTheMiddleOfAQuad() {
        val quad = ConformQuad(
            ConformVertex(0f, 0f, 0f, 0f, 0f),
            ConformVertex(1f, 0f, 0f, 1f, 0f),
            ConformVertex(1f, 1f, 0f, 1f, 1f),
            ConformVertex(0f, 1f, 0f, 0f, 1f),
            0f, 0f, 1f,
        )
        val clipped = clipToUvRect(quad, 0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(1, clipped.size)
        val xs = clipped.single().vertices.map { it.x }.toSet()
        val ys = clipped.single().vertices.map { it.y }.toSet()
        assertEquals(setOf(0.25f, 0.75f), xs)
        assertEquals(setOf(0.25f, 0.75f), ys)
    }

    private fun normalMatchesWinding(quad: ConformQuad): Boolean {
        val e1x = quad.v1.x - quad.v0.x
        val e1y = quad.v1.y - quad.v0.y
        val e1z = quad.v1.z - quad.v0.z
        val e2x = quad.v2.x - quad.v0.x
        val e2y = quad.v2.y - quad.v0.y
        val e2z = quad.v2.z - quad.v0.z
        val cx = e1y * e2z - e1z * e2y
        val cy = e1z * e2x - e1x * e2z
        val cz = e1x * e2y - e1y * e2x
        return cx * quad.nx + cy * quad.ny + cz * quad.nz > 0f
    }

    private companion object {
        val FULL = ShapeBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        val BOTTOM = ShapeBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)
        val STEP = ShapeBox(0.0, 0.5, 0.5, 1.0, 1.0, 1.0)
    }
}
