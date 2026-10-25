package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.display.geometry.DisplayGeometry
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.placement.DisplayPlacement
import com.dreamdisplays.api.display.placement.PlacementTransform
import com.dreamdisplays.api.media.audio.ListenerPose
import com.dreamdisplays.api.media.audio.SourcePlane
import com.dreamdisplays.core.display.PlacementHub
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import java.util.UUID

/**
 * Everything the client does differently for a display that an addon has put somewhere other than its stored
 * coordinates.
 *
 * @see [DisplayPlacement]
 */
internal object PlacedDisplays {
    /** The addon-supplied placement of [uuid], or `null` when it sits at its stored coordinates. */
    fun placementOf(uuid: UUID): DisplayPlacement? =
        if (PlacementHub.isEmpty) null else PlacementHub.placementOf(DisplayId(uuid))

    /** Game-tick fraction for interpolating moving placements. */
    fun partialTick(): Float {
        val mc = Minecraft.getInstance()
        //? if >=1.21.11 {
        return mc.deltaTracker.getGameTimeDeltaPartialTick(false)
        //?} else
        /*return mc.timer.getGameTimeDeltaPartialTick(false)*/
    }

    private fun localOrigin(transform: PlacementTransform) = BlockPos(transform.anchorX, transform.anchorY, transform.anchorZ)

    /** The block that holds the display's blocks' minimum corner: the placement's anchor, or [stored] when unplaced. */
    fun blockOrigin(uuid: UUID, stored: BlockPos): BlockPos {
        val placement = placementOf(uuid) ?: return stored
        return localOrigin(placement.transform(1f))
    }

    /** Distance from the player block [pos] to the display, or `null` when the display is not placed. */
    fun distance(
        uuid: UUID, width: Int, height: Int, depth: Int, facing: DisplayFacing, pos: BlockPos,
    ): Double? {
        val placement = placementOf(uuid) ?: return null
        val t = placement.transform(1f)
        val local = t.toLocal(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        return DisplayGeometry.distanceTo(
            local.x, local.y, local.z, t.anchorX, t.anchorY, t.anchorZ, width, height, facing, depth.coerceAtLeast(1),
        )
    }

    /** Whether the player block [pos] is inside the display, or `null` when the display is not placed. */
    fun contains(
        uuid: UUID, width: Int, height: Int, depth: Int, facing: DisplayFacing, pos: BlockPos,
    ): Boolean? {
        val placement = placementOf(uuid) ?: return null
        val t = placement.transform(1f)
        val local = t.toLocal(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        return DisplayGeometry.isInBounds(
            local.x, local.y, local.z, t.anchorX, t.anchorY, t.anchorZ, width, height, facing, depth.coerceAtLeast(1),
        )
    }

    /** Writes the world-space bounding sphere of a placed display into [out]; `false` when it is not placed. */
    fun boundingSphere(
        uuid: UUID, width: Int, height: Int, depth: Int, facing: DisplayFacing, out: DoubleArray,
    ): Boolean {
        val placement = placementOf(uuid) ?: return false
        val t = placement.transform(partialTick())
        DisplayGeometry.boundingSphere(t.anchorX, t.anchorY, t.anchorZ, width, height, facing, depth, out)
        val center = t.toWorld(out[0], out[1], out[2])
        out[0] = center.x
        out[1] = center.y
        out[2] = center.z
        out[3] *= maxOf(t.scaleX, t.scaleY, t.scaleZ)
        return true
    }

    /** The sound source in world space for a placed display, or `null` when it is not placed. */
    fun sourcePlane(
        uuid: UUID, width: Int, height: Int, depth: Int, facing: DisplayFacing,
    ): SourcePlane? {
        val placement = placementOf(uuid) ?: return null
        val t = placement.transform(partialTick())
        val local = DisplayGeometry.pose(t.anchorX, t.anchorY, t.anchorZ, width, height, facing, depth)
        val center = t.toWorld(local.centerX, local.centerY, local.centerZ)
        val normal = t.rotate(local.normalX, local.normalY, local.normalZ)
        val u = t.rotate(local.uAxisX, local.uAxisY, local.uAxisZ)
        val v = t.rotate(local.vAxisX, local.vAxisY, local.vAxisZ)
        return SourcePlane(
            center.x, center.y, center.z, normal.x, normal.y, normal.z, u.x, u.y, u.z, v.x, v.y, v.z,
            width.toDouble(), height.toDouble(),
        )
    }

    /** A plane and listener in the display's local space, where its blocks are, for tracing sound through them. */
    class LocalProbe(val plane: SourcePlane, val listener: ListenerPose)

    /** The acoustic probe inputs in local space for a placed display, or `null` when it is not placed. */
    fun localProbe(
        uuid: UUID, width: Int, height: Int, depth: Int, facing: DisplayFacing, listener: ListenerPose,
    ): LocalProbe? {
        val placement = placementOf(uuid) ?: return null
        val t = placement.transform(partialTick())
        val local = DisplayGeometry.pose(t.anchorX, t.anchorY, t.anchorZ, width, height, facing, depth)
        val ear = t.toLocal(listener.x, listener.y, listener.z)
        val forward = t.inverseRotate(listener.forwardX, listener.forwardY, listener.forwardZ)
        val up = t.inverseRotate(listener.upX, listener.upY, listener.upZ)
        return LocalProbe(
            SourcePlane(
                local.centerX, local.centerY, local.centerZ, local.normalX, local.normalY, local.normalZ,
                local.uAxisX, local.uAxisY, local.uAxisZ, local.vAxisX, local.vAxisY, local.vAxisZ,
                width.toDouble(), height.toDouble(),
            ),
            ListenerPose(ear.x, ear.y, ear.z, forward.x, forward.y, forward.z, up.x, up.y, up.z),
        )
    }
}
