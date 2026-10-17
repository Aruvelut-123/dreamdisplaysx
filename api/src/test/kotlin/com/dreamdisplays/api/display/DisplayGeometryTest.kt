package com.dreamdisplays.api.display

import com.dreamdisplays.api.display.geometry.DisplayGeometry
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.display.placement.PlacementTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayGeometryTest {
    private val eps = 1e-9

    @Test
    fun `a north display extends width along x and depth along z`() {
        assertTrue(DisplayGeometry.isInBounds(13, 66, 20, 10, 64, 20, 4, 3, DisplayFacing.NORTH))
        assertFalse(DisplayGeometry.isInBounds(14, 66, 20, 10, 64, 20, 4, 3, DisplayFacing.NORTH))
        assertFalse(DisplayGeometry.isInBounds(13, 66, 21, 10, 64, 20, 4, 3, DisplayFacing.NORTH))
    }

    @Test
    fun `an east display extends width along z and depth along x`() {
        assertTrue(DisplayGeometry.isInBounds(11, 64, 23, 10, 64, 20, 4, 3, DisplayFacing.EAST, depth = 2))
        assertFalse(DisplayGeometry.isInBounds(12, 64, 23, 10, 64, 20, 4, 3, DisplayFacing.EAST, depth = 2))
    }

    @Test
    fun `block distance is zero inside and measured to the nearest block outside`() {
        assertEquals(0.0, DisplayGeometry.distanceTo(11, 65, 20, 10, 64, 20, 4, 3, DisplayFacing.NORTH), eps)
        assertEquals(3.0, DisplayGeometry.distanceTo(11, 65, 23, 10, 64, 20, 4, 3, DisplayFacing.NORTH), eps)
        assertEquals(5.0, DisplayGeometry.distanceTo(16, 65, 24, 10, 64, 20, 4, 3, DisplayFacing.NORTH), eps)
    }

    @Test
    fun `point distance uses the continuous box`() {
        assertEquals(0.0, DisplayGeometry.distanceTo(14.0, 65.0, 20.5, 10, 64, 20, 4, 3, DisplayFacing.NORTH), eps)
        assertEquals(1.0, DisplayGeometry.distanceTo(15.0, 65.0, 20.5, 10, 64, 20, 4, 3, DisplayFacing.NORTH), eps)
        assertTrue(DisplayGeometry.isInBounds(14.0, 65.0, 20.5, 10, 64, 20, 4, 3, DisplayFacing.NORTH))
        assertFalse(DisplayGeometry.isInBounds(14.01, 65.0, 20.5, 10, 64, 20, 4, 3, DisplayFacing.NORTH))
    }

    @Test
    fun `pose has the center and unit axes`() {
        val pose = DisplayGeometry.pose(10, 64, 20, 4, 3, DisplayFacing.NORTH)
        assertEquals(12.0, pose.centerX, eps)
        assertEquals(65.5, pose.centerY, eps)
        assertEquals(20.5, pose.centerZ, eps)
        assertEquals(-1.0, pose.normalZ, eps)
        assertEquals(-1.0, pose.uAxisX, eps)
        assertEquals(1.0, pose.vAxisY, eps)
    }

    @Test
    fun `bounding sphere surrounds the box`() {
        val out = DoubleArray(4)
        DisplayGeometry.boundingSphere(0, 0, 0, 4, 3, DisplayFacing.NORTH, 1, out)
        assertEquals(2.0, out[0], eps)
        assertEquals(1.5, out[1], eps)
        assertEquals(0.5, out[2], eps)
        assertEquals(0.5 * kotlin.math.sqrt(16.0 + 9.0 + 1.0), out[3], eps)
    }

    private fun quarterTurnAboutY(angle: Double) = doubleArrayOf(0.0, sin(angle / 2), 0.0, cos(angle / 2))

    @Test
    fun `an identity placement only moves the anchor to the origin`() {
        val t = PlacementTransform(anchorX = 10, anchorY = 64, anchorZ = 20, originX = 100.0, originY = 5.0, originZ = -3.0)
        val world = t.toWorld(12.0, 65.0, 21.0)
        assertEquals(102.0, world.x, eps)
        assertEquals(6.0, world.y, eps)
        assertEquals(-2.0, world.z, eps)
    }

    @Test
    fun `toLocal undoes toWorld for a rotated and scaled placement`() {
        val q = quarterTurnAboutY(PI / 2)
        val t = PlacementTransform(10, 64, 20, 100.0, 5.0, -3.0, q[0], q[1], q[2], q[3], 2.0, 1.0, 0.5)
        val world = t.toWorld(13.0, 66.0, 24.0)
        val back = t.toLocal(world.x, world.y, world.z)
        assertEquals(13.0, back.x, 1e-9)
        assertEquals(66.0, back.y, 1e-9)
        assertEquals(24.0, back.z, 1e-9)
    }

    @Test
    fun `a quarter turn about y sends x to minus z`() {
        val q = quarterTurnAboutY(PI / 2)
        val t = PlacementTransform(0, 0, 0, 0.0, 0.0, 0.0, q[0], q[1], q[2], q[3])
        val r = t.rotate(1.0, 0.0, 0.0)
        assertTrue(abs(r.x) < 1e-9 && abs(r.y) < 1e-9)
        assertEquals(-1.0, r.z, 1e-9)
    }
}
