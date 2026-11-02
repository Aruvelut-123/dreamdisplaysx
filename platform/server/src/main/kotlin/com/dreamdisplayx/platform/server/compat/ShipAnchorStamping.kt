package com.dreamdisplayx.platform.server.compat

import com.dreamdisplayx.platform.server.datatypes.display.DisplayData
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Records which Valkyrien Skies ship owns the block at ([x], [y], [z]) in [level].
 *
 * Only the anchor corner is sampled. A display selection is one flat panel (or a single staircase
 * column), and VS already funnels `Level.clip` through `getShipManagingPos`, so the block the player
 * clicked and this lookup name the same structure. A selection straddling a ship edge is rejected
 * earlier by the surface census, which sees air on the far side.
 *
 * Leaves [DisplayData.shipId] untouched unless a ship is actually found: a lookup that misses (mod
 * absent, chunk unloaded, plain world) must keep the row world-anchored rather than clear a stored
 * anchor during an ordinary `/display create` on an empty deck.
 */
fun stampShipAnchor(data: DisplayData, level: ServerLevel, x: Int, y: Int, z: Int) {
    val shipId = ValkyrienCompat.shipIdAt(level, BlockPos(x, y, z))
    if (shipId > 0L) data.shipId = shipId
}

/**
 * Bukkit-side overload: unwraps the Craft world behind [craftWorld] and stamps the anchor.
 *
 * Separate from the [ServerLevel] overload because only Paper hands out `org.bukkit.World`, and the
 * compat package must stay usable from the vanilla build, where that type does not exist.
 */
fun stampShipAnchor(data: DisplayData, craftWorld: Any?, x: Int, y: Int, z: Int) {
    val level = nmsLevelOf(craftWorld) ?: return
    stampShipAnchor(data, level, x, y, z)
}