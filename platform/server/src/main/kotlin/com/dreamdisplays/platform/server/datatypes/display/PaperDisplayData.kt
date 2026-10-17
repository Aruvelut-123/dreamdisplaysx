package com.dreamdisplays.platform.server.datatypes.display

import com.dreamdisplays.api.display.model.property.DisplayRotation
import com.dreamdisplays.platform.server.utils.RegionUtil
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.util.BoundingBox
import org.jspecify.annotations.NullMarked
import java.util.*

/**
 * Data class representing a display in the Minecraft world.
 */
@PaperOnly
@NullMarked
class PaperDisplayData(
    /** Unique identifier for the display. */
    override val id: UUID,

    /** Unique identifier for the owner of the display. */
    override val ownerId: UUID,

    /** One corner of the display area. */
    pos1: Location,

    /** Opposite corner of the display area. */
    pos2: Location,

    /** Width of the display in blocks. */
    width: Int,

    /** Height of the display in blocks. */
    height: Int,

    /** Direction the display is facing. Default is `NORTH`. */
    facing: BlockFace = BlockFace.NORTH,

    /** Content rotation; only meaningful for floor / ceiling (`UP` / `DOWN`) facings. */
    override val rotation: DisplayRotation = DisplayRotation.NONE,

    /** True for the synthetic display backing a URL-only fullscreen broadcast. */
    virtual: Boolean = false,

    /** True when the screen wraps slabs and stairs inside the box. */
    override val conforming: Boolean = false,
) : BaseDisplayData(virtual) {
    /** One corner of the display area. */
    var pos1: Location = pos1; private set

    /** Opposite corner of the display area. */
    var pos2: Location = pos2; private set

    /** Width of the display in blocks. */
    override var width: Int = width; private set

    /** Height of the display in blocks. */
    override var height: Int = height; private set

    /** Direction the display is facing. */
    var facing: BlockFace = facing
        private set

    /** Bounding box of the display area. */
    private var region = RegionUtil.calculateRegion(pos1, pos2)

    /** Blocks the screen extends away from [facing]. Derived from the saved corners, so old rows stay one block deep. */
    override val depth: Int
        get() = when (facing) {
            BlockFace.NORTH, BlockFace.SOUTH -> (box.maxZ - box.minZ).toInt()
            BlockFace.EAST, BlockFace.WEST -> (box.maxX - box.minX).toInt()
            else -> (box.maxY - box.minY).toInt()
        }.coerceAtLeast(1)

    /** Bounding box of the display area, calculated from the two corner positions. */
    var box: BoundingBox = boxOf(region); private set

    /** Moves and resizes the display to the area between [pos1] and [pos2] facing [facing]. */
    fun relocate(pos1: Location, pos2: Location, facing: BlockFace, width: Int, height: Int) {
        this.pos1 = pos1
        this.pos2 = pos2
        this.facing = facing
        this.width = width
        this.height = height
        region = RegionUtil.calculateRegion(pos1, pos2)
        box = boxOf(region)
    }

    private companion object {
        fun boxOf(region: RegionUtil.RegionData) = BoundingBox(
            region.minX.toDouble(), region.minY.toDouble(), region.minZ.toDouble(),
            (region.maxX + 1).toDouble(), (region.maxY + 1).toDouble(), (region.maxZ + 1).toDouble(),
        )
    }
}
