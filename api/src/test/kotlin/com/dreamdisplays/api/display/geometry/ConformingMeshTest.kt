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
            listOf(ShapedBlock(0, 0, 0, listOf(ShapeBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)))),
            width = 1, height = 1, depth = 1, facing = DisplayFacing.NORTH,
        )
        val front = quads.single { it.nz < 0f }
        assertTrue(front.vertices.all { it.y <= 0.5f + 1e-3f })
        val top = quads.single { it.ny > 0f }
        assertTrue(top.vertices.all { abs(it.y - 0.5f) < 1e-3f })
        assertTrue(normalMatchesWinding(front))
        assertTrue(normalMatchesWinding(top))
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
    fun straightStairWrapsTheRiserAndTheTread() {
        val quads = buildConformingMesh(
            listOf(ShapedBlock(0, 0, 0, listOf(BOTTOM, STEP))),
            width = 1, height = 1, depth = 1, facing = DisplayFacing.NORTH,
        )
        val fronts = quads.filter { it.nz < 0f }
        assertEquals(2, fronts.size)
        assertTrue(fronts.any { it.vertices.all { v -> v.z == 0f } && it.vertices.maxOf { it.y } <= 0.5f + 1e-3f })
        assertTrue(fronts.any { it.vertices.all { v -> abs(v.z - 0.5f) < 1e-3f } })
        val tread = quads.single { it.ny > 0f }
        assertTrue(tread.vertices.all { abs(it.y - 0.5f) < 1e-3f && it.z <= 0.5f + 1e-3f })
        assertEquals(3, quads.size, "the top of the step lies on the rim and stays bare")
        quads.forEach { assertTrue(normalMatchesWinding(it)) }
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
        assertEquals(5, quads.size)
        val walls = quads.filter { it.nx != 0f }
        assertEquals(2, walls.size, "both side walls of the groove get picture")
        for (wall in walls) {
            val us = wall.vertices.map { it.u }
            assertTrue(us.max() - us.min() > 0.1f, "a wall spans picture, not one smeared column")
        }
        quads.forEach { assertTrue(normalMatchesWinding(it)) }
        for (a in quads) for (b in quads) {
            if (a === b) continue
            for (va in a.vertices) for (vb in b.vertices) {
                if (va.x == vb.x && va.y == vb.y && va.z == vb.z) {
                    assertTrue(abs(va.u - vb.u) < 1e-4f && abs(va.v - vb.v) < 1e-4f, "seam at $va / $vb")
                    assertTrue(va.ox == vb.ox && va.oy == vb.oy && va.oz == vb.oz, "lifted faces part at $va / $vb")
                }
            }
        }
        val all = quads.flatMap { it.vertices }
        assertEquals(0f, all.minOf { it.u })
        assertEquals(1f, all.maxOf { it.u })
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
    fun staircasePictureContinuesFromOneStepOntoTheNext() {
        val quads = buildConformingMesh(
            listOf(
                ShapedBlock(0, 0, 0, listOf(BOTTOM, STEP)),
                ShapedBlock(0, 1, 1, listOf(BOTTOM, STEP)),
            ),
            width = 1, height = 2, depth = 2, facing = DisplayFacing.NORTH,
        )
        val firstCap = quads.single { it.ny > 0f && it.vertices.all { v -> abs(v.y - 1f) < 1e-3f && v.z <= 1f } }
        val nextRiser = quads.single { it.nz < 0f && it.vertices.all { v -> abs(v.z - 1f) < 1e-3f && v.y >= 1f && v.y <= 1.5f + 1e-3f } }
        val capV = firstCap.vertices.minOf { it.v }
        val riserV = nextRiser.vertices.maxOf { it.v }
        assertTrue(abs(capV - riserV) < 1e-3f, "cap v=$capV riser v=$riserV")
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
