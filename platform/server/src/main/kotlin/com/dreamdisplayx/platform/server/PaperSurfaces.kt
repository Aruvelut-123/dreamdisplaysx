package com.dreamdisplayx.platform.server

import com.dreamdisplayx.api.display.geometry.DisplaySurface
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapedRecipe
import java.util.concurrent.ConcurrentHashMap

/**
 * `Paper` side of [DisplaySurface].
 *
 * `Bukkit` knows nothing about block families, but it knows recipes: a slab or stair crafted from
 * nothing but the base is that base's variant.
 */
@PaperOnly
object PaperSurfaces {
    private val cache = ConcurrentHashMap<Material, DisplaySurface>()

    /** Surface for [base], resolved once per material. */
    fun of(base: Material): DisplaySurface = cache.getOrPut(base) {
        DisplaySurface(base.key.toString(), shapedVariants(base))
    }

    private fun shapedVariants(base: Material): List<String> {
        if (!base.isItem) return emptyList()
        val sample = ItemStack(base)
        val out = ArrayList<String>(2)
        Bukkit.recipeIterator().forEachRemaining { recipe ->
            if (recipe !is ShapedRecipe) return@forEachRemaining
            val result = recipe.result.type
            if (!Tag.SLABS.isTagged(result) && !Tag.STAIRS.isTagged(result)) return@forEachRemaining
            val inputs = recipe.choiceMap.values.filterNotNull()
            if (inputs.isNotEmpty() && inputs.all { it.test(sample) }) out += result.key.toString()
        }
        return out
    }
}
