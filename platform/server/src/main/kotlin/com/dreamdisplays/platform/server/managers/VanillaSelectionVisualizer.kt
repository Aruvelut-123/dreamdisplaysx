package com.dreamdisplays.platform.server.managers

import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.selection.VanillaSelectionData
import com.dreamdisplays.platform.server.utils.OutlineGeometry
import com.dreamdisplays.platform.server.utils.RegionUtil
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
//? if <1.21.11 {
import org.joml.Vector3f
//?}

/** Draws particle outlines around the ready selections of players on `Fabric` / `NeoForge` servers. */
@ModLoaderOnly
object VanillaSelectionVisualizer {
    private const val DUST_SCALE = 0.5f

    /** Draws every ready selection to the player who made it, when that player is still in the selection's world. */
    fun tick(server: MinecraftServer) {
        val dust = dust(VanillaServerState.config.settings.particleColorRgb)
        SelectionManager.selectionPoints.forEach { (playerId, selection) ->
            if (selection !is VanillaSelectionData || !selection.isReady) return@forEach
            val player = server.playerList.getPlayer(playerId) ?: return@forEach
            val pos1 = selection.pos1 ?: return@forEach
            val pos2 = selection.pos2 ?: return@forEach
            val worldKey = selection.worldKey ?: return@forEach
            if (RegionUtil.getPlayerLevelKey(player) != worldKey) return@forEach
            val level = RegionUtil.getLevelByKey(server, worldKey) ?: return@forEach

            val region = RegionUtil.calculateRegion(pos1, pos2)
            OutlineGeometry.forEachPoint(
                region.minX.toDouble(), region.minY.toDouble(), region.minZ.toDouble(),
                (region.maxX + 1).toDouble(), (region.maxY + 1).toDouble(), (region.maxZ + 1).toDouble(),
            ) { x, y, z -> send(level, player, dust, x, y, z) }
        }
    }

    private fun dust(rgb: Int): ParticleOptions =
        //? if >=1.21.11 {
        DustParticleOptions(rgb, DUST_SCALE)
    //?} else
    /*
    //? if >=1.19.3 {
    DustParticleOptions(Vector3f((rgb shr 16 and 0xFF) / 255f, (rgb shr 8 and 0xFF) / 255f, (rgb and 0xFF) / 255f), DUST_SCALE)
    //?}
    //? if <1.19.3 {
    DustParticleOptions(
        com.mojang.math.Vector3f((rgb shr 16 and 0xFF) / 255f, (rgb shr 8 and 0xFF) / 255f, (rgb and 0xFF) / 255f), DUST_SCALE,
    )
    //?}
    */

    private fun send(level: ServerLevel, player: ServerPlayer, options: ParticleOptions, x: Double, y: Double, z: Double) {
        //? if >=1.21.11 {
        level.sendParticles(player, options, false, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)
        //?} else
        /*level.sendParticles(player, options, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)*/
    }
}
