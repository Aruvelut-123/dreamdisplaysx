package com.dreamdisplayx.platform.server.managers

import com.dreamdisplayx.platform.server.ModLoaderOnly
import com.dreamdisplayx.platform.server.VanillaServerState
import com.dreamdisplayx.platform.server.datatypes.selection.VanillaSelectionData
import com.dreamdisplayx.platform.server.utils.RegionUtil
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.AABB
//? if >=1.21.11 {
//?} else
/*import org.joml.Vector3f*/
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Draws the active selection box for Fabric and NeoForge players.
 *
 * Paper has a Bukkit-only scheduler and particle API, while the vanilla loaders share the
 * ServerLevel packet API. The small lifecycle-owned executor only enqueues work onto the server
 * thread; all selection reads and packet sends remain on that thread.
 */
@ModLoaderOnly
object VanillaSelectionVisualizer {
    private const val PARTICLE_SPACING = 0.5
    private const val PARTICLE_SCALE = 0.5f
    private const val CYAN_RGB = 0x00FFFF

    private val lock = Any()
    private var executor: ScheduledExecutorService? = null
    private var task: ScheduledFuture<*>? = null

    /** Starts the repeating particle task for [server], replacing a task from a prior world. */
    fun startParticleTask(server: MinecraftServer) {
        if (!VanillaServerState.config.settings.particlesEnabled) return
        synchronized(lock) {
            stopLocked()
            val threadFactory = ThreadFactory { runnable ->
                Thread(runnable, "DreamDisplaysX-selection-particles").apply { isDaemon = true }
            }
            val service = Executors.newSingleThreadScheduledExecutor(threadFactory)
            executor = service
            val periodTicks = max(1, VanillaServerState.config.settings.particleRenderDelay)
            task = service.scheduleAtFixedRate(
                { server.execute { render(server) } },
                0L,
                periodTicks * 50L,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /** Stops the task when the loader tears down the server. */
    fun stopParticleTask() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        task?.cancel(false)
        task = null
        executor?.shutdownNow()
        executor = null
    }

    private fun render(server: MinecraftServer) {
        if (!VanillaServerState.config.settings.particlesEnabled) return
        val options = cyanDust()
        SelectionManager.selectionPoints.forEach { (playerId, rawSelection) ->
            val selection = rawSelection as? VanillaSelectionData ?: return@forEach
            if (!selection.isReady) return@forEach
            if (selection.pos1 == null || selection.pos2 == null) return@forEach
            val worldKey = selection.worldKey ?: return@forEach
            val player = server.playerList.getPlayer(playerId) ?: return@forEach
            if (RegionUtil.getPlayerLevelKey(player) != worldKey) return@forEach
            val level = RegionUtil.getLevelByKey(server, worldKey) ?: return@forEach
            val box = selection.selectionBox() ?: return@forEach
            drawBox(level, player, box, options)
        }
    }

    private fun drawBox(level: ServerLevel, player: ServerPlayer, box: AABB, options: DustParticleOptions) {
        drawLine(level, player, options, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ)
        drawLine(level, player, options, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ)
        drawLine(level, player, options, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ)
        drawLine(level, player, options, box.minX, box.minY, box.maxZ, box.minX, box.minY, box.minZ)

        drawLine(level, player, options, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ)
        drawLine(level, player, options, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ)
        drawLine(level, player, options, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ)
        drawLine(level, player, options, box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ)

        drawLine(level, player, options, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ)
        drawLine(level, player, options, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ)
        drawLine(level, player, options, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ)
        drawLine(level, player, options, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ)
    }

    private fun drawLine(
        level: ServerLevel,
        player: ServerPlayer,
        options: DustParticleOptions,
        fromX: Double,
        fromY: Double,
        fromZ: Double,
        toX: Double,
        toY: Double,
        toZ: Double,
    ) {
        val dx = toX - fromX
        val dy = toY - fromY
        val dz = toZ - fromZ
        val points = max(1, ceil(sqrt(dx * dx + dy * dy + dz * dz) / PARTICLE_SPACING).toInt())
        for (index in 0..points) {
            val fraction = index.toDouble() / points
            sendParticle(
                level,
                player,
                options,
                fromX + dx * fraction,
                fromY + dy * fraction,
                fromZ + dz * fraction,
            )
        }
    }

    private fun sendParticle(
        level: ServerLevel,
        player: ServerPlayer,
        options: DustParticleOptions,
        x: Double,
        y: Double,
        z: Double,
    ) {
        //? if >=1.21.11 {
        level.sendParticles(player, options, false, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)
        //?} else
        /*level.sendParticles(player, options, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)*/
    }

    private fun cyanDust(): DustParticleOptions =
        //? if >=1.21.11 {
        DustParticleOptions(CYAN_RGB, PARTICLE_SCALE)
        //?} else
        /*DustParticleOptions(Vector3f(0f, 1f, 1f), PARTICLE_SCALE)*/
}
