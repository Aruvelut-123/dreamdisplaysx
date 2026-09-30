package com.dreamdisplayx.platform.client.render

import com.dreamdisplayx.api.display.geometry.ConformQuad
import com.dreamdisplayx.api.display.geometry.ShapeBox
import com.dreamdisplayx.api.display.geometry.ShapedBlock
import com.dreamdisplayx.api.display.geometry.blockSpans
import com.dreamdisplayx.api.display.geometry.buildConformingMesh
import com.dreamdisplayx.api.display.model.property.DisplayFacing
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.UUID

private const val RECHECK_NANOS = 500_000_000L

/**
 * Reads the blocks inside a wrapped display and caches the quads that hug them.
 *
 * The cache rebuilds when the outline changes, so breaking or replacing a stair updates the picture.
 */
internal object ConformingWorldMesh {
    private data class Key(
        val x: Int,
        val y: Int,
        val z: Int,
        val width: Int,
        val height: Int,
        val depth: Int,
        val facing: DisplayFacing,
        val quarterTurns: Int,
    )

    private data class Entry(val key: Key, val stamp: Int, val quads: List<ConformQuad>, var checkedAt: Long)

    private val cache = HashMap<UUID, Entry>()

    /** Drops the cached mesh for a display that just went away. */
    fun forget(id: UUID) {
        cache.remove(id)
    }

    /**
     * Quads for [screen], or null when the world is not available yet.
     *
     * An empty list means the volume was read and has nothing to draw.
     */
    fun quads(screen: DisplayScreen): List<ConformQuad>? {
        if (!screen.conforming) return null
        val level = Minecraft.getInstance().level ?: return null
        val origin = screen.pos
        val key = Key(
            origin.x, origin.y, origin.z, screen.width, screen.height, screen.depth,
            screen.facing, screen.rotation.quarterTurns,
        )
        val now = System.nanoTime()
        val existing = cache[screen.uuid]
        if (existing != null && existing.key == key && now - existing.checkedAt < RECHECK_NANOS) {
            return existing.quads
        }
        val (spanX, spanY, spanZ) = blockSpans(screen.width, screen.height, screen.depth, screen.facing)
        val stamp = stamp(level, origin, spanX, spanY, spanZ)
        if (existing != null && existing.key == key && existing.stamp == stamp) {
            existing.checkedAt = now
            return existing.quads
        }
        val blocks = readBlocks(level, origin, spanX, spanY, spanZ)
        val quads = buildConformingMesh(
            blocks, screen.width, screen.height, screen.depth, screen.facing, screen.rotation.quarterTurns,
        )
        cache[screen.uuid] = Entry(key, stamp, quads, now)
        return quads
    }

    private fun stamp(level: Level, origin: BlockPos, spanX: Int, spanY: Int, spanZ: Int): Int {
        var hash = 1
        val cursor = BlockPos.MutableBlockPos()
        for (dx in 0 until spanX) {
            for (dy in 0 until spanY) {
                for (dz in 0 until spanZ) {
                    cursor.set(origin.x + dx, origin.y + dy, origin.z + dz)
                    hash = 31 * hash + level.getBlockState(cursor).hashCode()
                }
            }
        }
        return hash
    }

    private fun readBlocks(
        level: Level,
        origin: BlockPos,
        spanX: Int,
        spanY: Int,
        spanZ: Int,
    ): List<ShapedBlock> {
        val blocks = ArrayList<ShapedBlock>()
        val cursor = BlockPos.MutableBlockPos()
        for (dx in 0 until spanX) {
            for (dy in 0 until spanY) {
                for (dz in 0 until spanZ) {
                    cursor.set(origin.x + dx, origin.y + dy, origin.z + dz)
                    val state = level.getBlockState(cursor)
                    if (state.isAir) continue
                    val shape = state.outline(level, cursor)
                    if (shape.isEmpty) continue
                    val boxes = shape.toAabbs().map { box ->
                        ShapeBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
                    }
                    if (boxes.isNotEmpty()) blocks += ShapedBlock(dx, dy, dz, boxes)
                }
            }
        }
        return blocks
    }

    private fun BlockState.outline(level: Level, pos: BlockPos) =
        getShape(level, pos, CollisionContext.empty())
}
