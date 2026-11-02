package com.dreamdisplayx.platform.server.compat

import com.dreamdisplayx.api.display.geometry.ShipTransformSource
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

/**
 * Reflection bridge to Valkyrien Skies, the only way this fork can talk to it.
 *
 * Adding `org.valkyrienskies` to the build would either pin one Minecraft version or force a separate
 * artifact per version, and the fork builds eleven of them — several of which VS does not support at
 * all. So nothing here is a compile-time reference: every name below was read out of the shipped mod
 * jar, and the whole thing is inert when the mod is absent.
 *
 * Verified against `valkyrienskies-120-2.4.11.jar` → nested `api-1.1.0+cf208d8b56.jar`:
 *
 * ```
 * VSGameUtilsKt.getShipManagingPos(ServerLevel, BlockPos): ServerShip
 * VSGameUtilsKt.getShipManagingPos(ServerLevel, double, double, double): ServerShip
 * Ship.getId(): long
 * Ship.getShipToWorld(): Matrix4dc
 * Matrix4dc.get(double[]): double[]      // column-major, 16 doubles
 * ```
 *
 * `getShipManagingPos` is chosen over `getAllShips` + a manual AABB test on purpose: it is the same
 * call `MixinLevel` already makes for block raycasts, so the display's click position and this lookup
 * agree on which structure owns a block.
 */
object ValkyrienCompat {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** `org.valkyrienskies.mod.common.VSGameUtilsKt`, the Kotlin file facade with the static helpers. */
    private const val GAME_UTILS = "org.valkyrienskies.mod.common.VSGameUtilsKt"

    /** Cached reflection state; `null` means "not resolved yet". */
    @Volatile
    private var state: Resolution? = null

    /**
     * Result of one resolution attempt.
     *
     * A sealed hierarchy rather than `Any?`: `state === Absent` and `state is Ready` both need a
     * common type, and an `Any?` field makes the first one a compile error.
     */
    private sealed class Resolution {
        /** Marker for "the mod is not on this server", so a failed lookup is not retried per display. */
        object Absent : Resolution()

        class Ready(
            /** `getShipManagingPos(ServerLevel, BlockPos)`. */
            val byBlockPos: Method,
            /** `Ship.getId()`. */
            val shipId: Method,
            /** `Ship.getShipToWorld()`. */
            val shipToWorld: Method,
            /** `Matrix4dc.get(double[])`. */
            val readMatrix: Method,
            /** `VSGameUtilsKt.getAllShips(Level)`. */
            val allShips: Method,
        ) : Resolution()
    }

    /** True once a successful lookup proved the mod is present and usable. */
    val isAvailable: Boolean get() = resolve() != null

    /** True when the mod's entry point could not be resolved on this server. */
    val isMissing: Boolean get() = resolve() == null && state === Resolution.Absent

    /**
     * Returns the structure id of the ship owning the block at [pos] in [level], or `0` when the block
     * is plain world and the mod is (or resolves to) nothing.
     *
     * Never throws: a broken install must degrade to "no ships here", not take the server down.
     */
    fun shipIdAt(level: ServerLevel, pos: BlockPos): Long {
        val s = resolve() ?: return 0L
        return runCatching {
            val ship = s.byBlockPos.invoke(null, level, pos)
            if (ship == null) 0L else (s.shipId.invoke(ship) as? Long)?.coerceAtLeast(0L) ?: 0L
        }.getOrElse {
            logger.debug("Valkyrien Skies lookup failed for {}: {}", pos, it.toString())
            0L
        }
    }

    /**
     * Returns the ship-to-world matrix of [shipId] as 16 column-major doubles, or `null` when the ship
     * is not loaded in [level].
     *
     * A structure can exist while its chunks are unloaded, so a `null` here is normal and the caller
     * must fall back (see `ShipAnchorPolicy.transformFor` rather than treating it as identity).
     */
    fun shipToWorldMatrix(level: ServerLevel, shipId: Long): DoubleArray? {
        if (shipId <= 0L) return null
        val s = resolve() ?: return null
        return runCatching {
            // Resolve the ship through its own registry rather than a position: a merged or renamed
            // structure keeps its id, and this is the only lookup that takes an id directly.
            val ship = shipById(s, level, shipId)
            if (ship == null) null else readMatrix(s, s.shipToWorld.invoke(ship))
        }.getOrNull()
    }

    /** Wraps [shipIdAt] / [shipToWorldMatrix] as the api-level source, binding the level up front. */
    fun sourceFor(level: ServerLevel): ShipTransformSource = ShipTransformSource { shipId ->
        shipToWorldMatrix(level, shipId)
    }

    /**
     * Looks a ship up by id through `getAllShips(Level)` and an id comparison.
     *
     * Deliberately avoids `QueryableShipData.getById(long)`: its return is typed to the generic ship
     * type and the generated bridge would need the exact `VsiQueryableShipData` signature, which
     * differs between VS releases. Iterating and comparing `getId()` only depends on `Iterable`.
     */
    private fun shipById(s: Resolution.Ready, level: ServerLevel, shipId: Long): Any? {
        val ships = runCatching { s.allShips.invoke(null, level) as? Iterable<*> }.getOrNull() ?: return null
        for (ship in ships) {
            if (ship == null) continue
            val id = (s.shipId.invoke(ship) as? Long) ?: continue
            if (id == shipId) return ship
        }
        return null
    }

    /** Copies a JOML `Matrix4dc` into a flat column-major array. */
    private fun readMatrix(s: Resolution.Ready, matrix: Any?): DoubleArray? {
        if (matrix == null) return null
        val out = DoubleArray(16)
        // Matrix4dc.get(double[]) writes 16 values in column-major order — the same layout
        // AffineTransform uses, so no transpose is needed.
        s.readMatrix.invoke(matrix, out)
        return out
    }

    /** Resolves and caches the bridge, or records that the mod is absent. */
    private fun resolve(): Resolution.Ready? {
        when (val cached = state) {
            is Resolution.Ready -> return cached
            Resolution.Absent -> return null
            null -> Unit
        }
        synchronized(this) {
            when (val cached = state) {
                is Resolution.Ready -> return cached
                Resolution.Absent -> return null
                null -> Unit
            }
            val resolved = try {
                val utils = Class.forName(GAME_UTILS)
                val serverLevel = ServerLevel::class.java
                val byBlockPos = utils.getMethod("getShipManagingPos", serverLevel, BlockPos::class.java)
                val ship = Class.forName("org.valkyrienskies.core.api.ships.Ship")
                val matrixClass = Class.forName("org.joml.Matrix4dc")
                Resolution.Ready(
                    byBlockPos = byBlockPos,
                    shipId = ship.getMethod("getId"),
                    shipToWorld = ship.getMethod("getShipToWorld"),
                    readMatrix = matrixClass.getMethod("get", DoubleArray::class.java),
                    // getAllShips takes a Level, NOT a ServerLevel — getMethod needs the exact
                    // declared parameter type, so passing ServerLevel would fail resolution and
                    // silently disable the whole bridge.
                    allShips = utils.getMethod("getAllShips", Level::class.java),
                )
            } catch (_: ClassNotFoundException) {
                // The mod is simply not installed; this is the normal case on most servers.
                Resolution.Absent
            } catch (t: Throwable) {
                // Installed but a different, incompatible version: report it once, then stay inert.
                logger.warn("Valkyrien Skies is present but its API did not match; ship displays are disabled.", t)
                Resolution.Absent
            }
            state = resolved
            return resolved as? Resolution.Ready
        }
    }

    /** Drops the cached resolution; used by tests and after a mod reload. */
    internal fun reset() {
        synchronized(this) {
            state = null
        }
    }
}

/**
 * Resolves the NMS `ServerLevel` behind a CraftBukkit world instance, or `null` when it is not one.
 *
 * The concrete class is `org.bukkit.craftbukkit.v<version>.CraftWorld`, and the version segment moves
 * with every Minecraft release, so the name cannot be hardcoded. Every Craft world exposes a
 * `getHandle()` returning its `ServerLevel`, which is the only part worth reflection here.
 */
fun nmsLevelOf(world: Any?): ServerLevel? {
    if (world == null) return null
    return runCatching {
        // `getMethod` only returns public members. Paper forks sometimes narrow CraftWorld's
        // visibility, so fall back to the declared method with access forced open before giving up.
        val handle = runCatching { world.javaClass.getMethod("getHandle") }.getOrElse {
            world.javaClass.getDeclaredMethod("getHandle").apply { isAccessible = true }
        }
        handle.invoke(world) as? ServerLevel
    }.getOrNull()
}