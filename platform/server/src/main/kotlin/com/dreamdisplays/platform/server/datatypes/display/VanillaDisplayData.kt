package com.dreamdisplays.platform.server.datatypes.display

import com.dreamdisplays.api.display.model.property.DisplayRotation
import com.dreamdisplays.platform.server.utils.RegionUtil
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import java.util.*

/**
 * Vanilla implementation of [DisplayData], shared by `Fabric` and `NeoForge`.
 */
class VanillaDisplayData(
    /** Unique identifier for the display. */
    override val id: UUID,

    /** Unique identifier for the owner of the display. */
    override val ownerId: UUID,

    /** Unique identifier for the world the display is in. */
    worldKey: String,

    /** One corner of the display area. */
    pos1: BlockPos,

    /** Opposite corner of the display area. */
    pos2: BlockPos,

    /** Width of the display in blocks. */
    width: Int,

    /** Height of the display in blocks. */
    height: Int,

    /** Direction the display is facing. Default is `NORTH`. */
    facing: Direction,

    /** Content rotation; only meaningful for floor / ceiling (`UP` / `DOWN`) facings. */
    override val rotation: DisplayRotation = DisplayRotation.NONE,

    /** True for the synthetic display backing a URL-only fullscreen broadcast. */
    virtual: Boolean = false,

    /** True when the screen wraps slabs and stairs inside the box. */
    override val conforming: Boolean = false,
) : BaseDisplayData(virtual) {
    /** Unique identifier for the world the display is in. */
    var worldKey: String = worldKey; private set

    /** One corner of the display area. */
    var pos1: BlockPos = pos1; private set

    /** Opposite corner of the display area. */
    var pos2: BlockPos = pos2; private set

    /** Width of the display in blocks. */
    override var width: Int = width; private set

    /** Height of the display in blocks. */
    override var height: Int = height; private set

    /** Direction the display is facing. */
    var facing: Direction = facing; private set

    /** Bounding box of the display area. */
    private var region = RegionUtil.calculateRegion(pos1, pos2)

    /** Region. */
    var minX = region.minX; private set
    var minY = region.minY; private set
    var minZ = region.minZ; private set
    var maxX = region.maxX; private set
    var maxY = region.maxY; private set
    var maxZ = region.maxZ; private set

    /** Blocks the screen extends away from [facing]. Derived from the saved corners, so old rows stay one block deep. */
    override val depth: Int
        get() = when (facing) {
            Direction.NORTH, Direction.SOUTH -> maxZ - minZ + 1
            Direction.EAST, Direction.WEST -> maxX - minX + 1
            else -> maxY - minY + 1
        }.coerceAtLeast(1)

    /** Bounding box of the display area, calculated from the two corner positions. */
    var box: AABB = boxOf()
        private set

    /** Moves and resizes the display to the area between [pos1] and [pos2] in [worldKey] facing [facing]. */
    fun relocate(worldKey: String, pos1: BlockPos, pos2: BlockPos, facing: Direction, width: Int, height: Int) {
        this.worldKey = worldKey
        this.pos1 = pos1
        this.pos2 = pos2
        this.facing = facing
        this.width = width
        this.height = height
        region = RegionUtil.calculateRegion(pos1, pos2)
        minX = region.minX
        minY = region.minY
        minZ = region.minZ
        maxX = region.maxX
        maxY = region.maxY
        maxZ = region.maxZ
        box = boxOf()
    }

    private fun boxOf() = AABB(
        minX.toDouble(), minY.toDouble(), minZ.toDouble(),
        (maxX + 1).toDouble(), (maxY + 1).toDouble(), (maxZ + 1).toDouble(),
    )
}
