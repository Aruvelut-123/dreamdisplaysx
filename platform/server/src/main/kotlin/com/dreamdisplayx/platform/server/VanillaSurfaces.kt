package com.dreamdisplayx.platform.server

import com.dreamdisplayx.api.display.geometry.DisplaySurface
import com.dreamdisplayx.api.display.geometry.normalizeBlockId
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.BlockFamilies
import net.minecraft.data.BlockFamily
import net.minecraft.world.level.block.Block
import java.util.concurrent.ConcurrentHashMap

/** `Fabric` / `NeoForge` side of [DisplaySurface]: the game's own block families say which slab and stair belong to the base. */
@ModLoaderOnly
object VanillaSurfaces {
    private val cache = ConcurrentHashMap<String, DisplaySurface>()

    /** Surface for the base registry ID [baseId], resolved once per ID. */
    fun of(baseId: String): DisplaySurface = cache.getOrPut(normalizeBlockId(baseId)) {
        val base = normalizeBlockId(baseId)
        val family = BlockFamilies.getAllFamilies()
            .filter { normalizeBlockId(idOf(it.baseBlock)) == base }
            .findFirst()
            .orElse(null)
        val shaped = listOfNotNull(
            family?.get(BlockFamily.Variant.SLAB),
            family?.get(BlockFamily.Variant.STAIRS),
        ).map(::idOf)
        DisplaySurface(baseId, shaped)
    }

    private fun idOf(block: Block): String = BuiltInRegistries.BLOCK.getKey(block).toString()
}
