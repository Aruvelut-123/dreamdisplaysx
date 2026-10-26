package com.dreamdisplays.platform.server.utils

import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked

/** Outlines a selected area with dust particles. */
@PaperOnly
@NullMarked
object OutlinerUtil {
    // Bukkit renamed "REDSTONE" to "DUST" in 1.20.5 XD
    //? if >=1.20.5 {
    private val DUST = Particle.DUST
    //?} else
    /*private val DUST = Particle.REDSTONE*/

    /** Draws the outline of the region between [pos1] and [pos2] to [player] once, in the [rgb] (`0xRRGGBB`) color. */
    fun showOutline(player: Player, pos1: Location, pos2: Location, rgb: Int) {
        val world = pos1.world ?: return
        val region = RegionUtil.calculateRegion(pos1, pos2)
        val dust = Particle.DustOptions(Color.fromRGB(rgb), 0.5f)
        OutlineGeometry.forEachPoint(
            region.minX.toDouble(), region.minY.toDouble(), region.minZ.toDouble(),
            (region.maxX + 1).toDouble(), (region.maxY + 1).toDouble(), (region.maxZ + 1).toDouble(),
        ) { x, y, z -> player.spawnParticle(DUST, Location(world, x, y, z), 1, 0.0, 0.0, 0.0, 0.0, dust) }
    }
}
