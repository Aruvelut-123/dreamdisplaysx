package com.dreamdisplays.platform.client.input

import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.managers.ClientTickManager
import com.dreamdisplays.platform.client.utils.RayCastingUtil
import net.minecraft.client.Minecraft
import net.minecraft.core.Direction
import java.util.concurrent.CopyOnWriteArrayList

/** Minecraft-backed display interaction service via ray-casting from the player's eye. */
object MinecraftDisplayInteractionService : DisplayInteractionService {
    private const val MAX_REACH: Double = 64.0
    private val listeners = CopyOnWriteArrayList<(DisplayInteraction) -> Unit>()

    override fun raycast(): RaycastResult {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return RaycastResult.Miss
        val hit = RayCastingUtil.rCBlock(MAX_REACH) ?: return RaycastResult.Miss
        val screen = DisplayRegistry.getScreens().firstOrNull {
            it.isInScreen(hit.blockPos) && hit.direction == it.facing.toDirection()
        } ?: return RaycastResult.Miss
        val loc = hit.location
        val distanceSq = player.getEyePosition(1.0f).distanceToSqr(loc)
        return RaycastResult.Hit(
            displayId = DisplayId(screen.uuid),
            hitX = loc.x,
            hitY = loc.y,
            hitZ = loc.z,
            distanceSq = distanceSq,
        )
    }

    override fun getCurrentTarget(): RaycastResult.Hit? = raycast() as? RaycastResult.Hit

    private fun DisplayFacing.toDirection(): Direction = when (this) {
        DisplayFacing.NORTH -> Direction.NORTH
        DisplayFacing.EAST -> Direction.EAST
        DisplayFacing.SOUTH -> Direction.SOUTH
        DisplayFacing.WEST -> Direction.WEST
        DisplayFacing.UP -> Direction.UP
        DisplayFacing.DOWN -> Direction.DOWN
    }

    override fun on(listener: (DisplayInteraction) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun emit(interaction: DisplayInteraction) {
        listeners.forEach { it(interaction) }
    }
}
