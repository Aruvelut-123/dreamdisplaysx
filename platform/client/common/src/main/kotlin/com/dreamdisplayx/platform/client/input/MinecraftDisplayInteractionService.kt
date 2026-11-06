package com.dreamdisplayx.platform.client.input

import com.dreamdisplayx.api.display.model.property.DisplayFacing
import com.dreamdisplayx.api.display.model.property.DisplayId
import com.dreamdisplayx.platform.client.displays.DisplayRegistry
import com.dreamdisplayx.platform.client.displays.DisplayScreen
import com.dreamdisplayx.platform.client.utils.RayCastingUtil
import net.minecraft.client.Minecraft
import net.minecraft.core.Direction
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minecraft-backed display interaction service: resolves the display under the crosshair.
 *
 * Two independent lookups run and the nearer hit wins, because a display can be anchored either way:
 *
 *  * **World-anchored** — the vanilla block clip (`Level.clip`) plus the block box test, unchanged.
 *    It is still the right answer here: the blocks really are at those coordinates, and the clip
 *    respects the rest of the world, so a display behind a wall is not clickable through it.
 *  * **Ship-anchored** — a pure geometric ray/box test via [DisplayScreen.rayHit], which maps the ray
 *    into structure space through the screen's own transform before the slab test.
 *
 * The second path is the fix, not an optimisation: once blocks are assembled into a Valkyrien Skies
 * hull they live in the shipyard, so `Level.clip` walks straight through the space the display used to
 * occupy and reports a miss. Sneak-clicking a hull-mounted display therefore did nothing at all — the
 * raycast never even reached the "which display is this" comparison, however that comparison was
 * written.
 *
 * Only hull-anchored screens use the geometric path. A world-anchored screen could be raycast
 * geometrically too, but that would happily hit it *through* a wall, which the block clip correctly
 * refuses; keeping the two lookups separate is what keeps that behaviour intact.
 */
object MinecraftDisplayInteractionService : DisplayInteractionService {
    /** Max reach, in blocks, for the look ray-cast. Mirrors the value used by [ClientTickManager]. */
    private const val MAX_REACH: Double = 64.0

    /** Thread-safe list of listeners subscribed to [DisplayInteraction] events. */
    private val listeners = CopyOnWriteArrayList<(DisplayInteraction) -> Unit>()

    /** A resolved crosshair target: which display, where on it, and how far away. */
    private class Target(
        val screen: DisplayScreen,
        val x: Double,
        val y: Double,
        val z: Double,
        val distance: Double,
    )

    /**
     * Casts a look ray from the player's eye and returns the display under the crosshair as a
     * [RaycastResult.Hit], or [RaycastResult.Miss] when there is no player, or nothing in reach is a
     * display.
     *
     * A hull hit wins a tie: the block clip cannot see the hull's blocks at all, so any block it
     * reports on that line sits *behind* the picture the player is looking at.
     */
    override fun raycast(): RaycastResult {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return RaycastResult.Miss
        val eye = player.getEyePosition(1.0f)
        val view = player.getViewVector(1.0f)

        val hull = hullAnchoredHit(eye.x, eye.y, eye.z, view.x, view.y, view.z)
        val world = worldAnchoredHit()

        val target = when {
            hull == null -> world
            world == null -> hull
            hull.distance <= world.distance + 1.0e-6 -> hull
            else -> world
        } ?: return RaycastResult.Miss

        return RaycastResult.Hit(
            displayId = DisplayId(target.screen.uuid),
            hitX = target.x,
            hitY = target.y,
            hitZ = target.z,
            distanceSq = eye.distanceToSqr(target.x, target.y, target.z),
        )
    }

    /** Convenience wrapper around [raycast] that returns the [RaycastResult.Hit] or null on a miss. */
    override fun getCurrentTarget(): RaycastResult.Hit? = raycast() as? RaycastResult.Hit

    /**
     * Nearest hull-anchored display whose box the look ray enters, or `null`.
     *
     * Screens that are not currently riding a resolvable hull are skipped: [DisplayScreen.isOnLoadedShip]
     * is false for them, and their geometry is plain world space, which [worldAnchoredHit] already
     * resolves through the block clip (and through walls' occlusion, which this test ignores).
     */
    private fun hullAnchoredHit(
        originX: Double, originY: Double, originZ: Double,
        dirX: Double, dirY: Double, dirZ: Double,
    ): Target? {
        var best: Target? = null
        for (screen in DisplayRegistry.getScreens()) {
            if (!screen.isOnLoadedShip) continue
            val hit = screen.rayHit(originX, originY, originZ, dirX, dirY, dirZ, MAX_REACH) ?: continue
            if (best == null || hit.distance < best.distance) {
                best = Target(screen, hit.x, hit.y, hit.z, hit.distance)
            }
        }
        return best
    }

    /**
     * The display whose anchor block the world-space look ray hits, or `null`.
     *
     * Hull-anchored screens are excluded on purpose: their stored coordinates are structure space, so
     * the block under the crosshair says nothing about them (they are resolved by [hullAnchoredHit]).
     */
    private fun worldAnchoredHit(): Target? {
        val hit = RayCastingUtil.rCBlock(MAX_REACH) ?: return null
        val loc = hit.location
        val screen = DisplayRegistry.getScreens().firstOrNull { s ->
            !s.isOnLoadedShip && s.isInScreen(hit.blockPos) && hit.direction == s.facing.toDirection()
        } ?: return null
        val distance = Minecraft.getInstance().player?.getEyePosition(1.0f)?.distanceTo(loc) ?: 0.0
        return Target(screen, loc.x, loc.y, loc.z, distance)
    }

    /** Maps a [DisplayFacing] to the outward block-face [Direction] the display surface sits on. */
    private fun DisplayFacing.toDirection(): Direction = when (this) {
        DisplayFacing.NORTH -> Direction.NORTH
        DisplayFacing.EAST -> Direction.EAST
        DisplayFacing.SOUTH -> Direction.SOUTH
        DisplayFacing.WEST -> Direction.WEST
        DisplayFacing.UP -> Direction.UP
        DisplayFacing.DOWN -> Direction.DOWN
    }

    /** Subscribes [listener] to [DisplayInteraction] events; close the returned handle to unsubscribe. */
    override fun on(listener: (DisplayInteraction) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /**
     * Publishes [interaction] to all subscribers. Call sites (e.g. the tick / input handlers) emit
     * here once they detect a right-click, hotbar scroll, focus toggle, popout request, etc.
     */
    override fun emit(interaction: DisplayInteraction) {
        listeners.forEach { it(interaction) }
    }
}