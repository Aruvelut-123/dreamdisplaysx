package com.dreamdisplayx.platform.client.render

import com.dreamdisplayx.util.log.DreamLog
import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.display.geometry.AffineTransform
import com.dreamdisplayx.api.display.geometry.ShipAnchorPolicy
import com.dreamdisplayx.api.display.geometry.ShipTransformSource
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.Level
import java.lang.reflect.Method

/**
 * Reflection bridge to Valkyrien Skies on the client, the mirror of the server's
 * `ValkyrienCompat`.
 *
 * A display on a ship stores its block coordinates in the ship's **structure space** (that is what
 * the server's raycast reports, because VS moves the structure into the shipyard). Rendering
 * therefore has to place the quad at `shipToWorld * pos`, not at `pos`. Nothing here is a
 * compile-time reference: adding VS to the build would mean one artifact per Minecraft version and
 * the fork builds eleven of them, several of which VS does not support.
 *
 * Verified against `valkyrienskies-120-2.4.11.jar`:
 *
 * ```
 * VSGameUtilsKt.getShipObjectWorld(ClientLevel): VsiClientShipWorld
 * <shipWorld>.getAllShips(): QueryableShipData<ClientShip>       // a java.util.Collection
 * Ship.getId(): long
 * ClientShip.getRenderTransform(): ShipTransform
 * ShipTransform.getShipToWorld(): Matrix4dc
 * Matrix4dc.get(double[]): double[]                               // column-major, 16 doubles
 * ```
 *
 * Two reflection traps are handled explicitly, both found by running the bridge against stub classes
 * rather than by reading the signatures:
 *
 * 1. `getShipToWorld()` must be resolved on the **transform** object's runtime class, not on `Ship`.
 *    `Ship` declares the same name, but invoking a `Ship` method with a `ShipTransform` receiver
 *    throws `IllegalArgumentException: object is not an instance of declaring class`.
 * 2. VS's ship-world implementation class is package-private, so calling its public `getAllShips()`
 *    through plain reflection throws `IllegalAccessException: cannot access a member of class ...`.
 *    The method has to be opened with `setAccessible(true)` first.
 *
 * `getAllShips()` and `getShipToWorld()` are resolved once on the runtime class instead of on a
 * named interface, because VS has spelled those types differently between releases (`ShipWorld` vs
 * `ClientShipWorld`, and the transform's package) — and because the runtime type is the only thing
 * that is guaranteed to match the object in hand.
 *
 * `getRenderTransform()` rather than the plain transform is deliberate: the render transform is the
 * one VS interpolates between the previous and current physics tick, so a display does not judder
 * against the hull it is bolted to while the ship moves.
 */
@DreamDisplaysXUnstableApi
object ValkyrienClientCompat {
    private val logger = DreamLog.of("ValkyrienClientCompat")

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
        /** Marker for "the mod is not on this client", so a failed lookup is not retried per frame. */
        object Absent : Resolution()

        class Ready(
            /** `VSGameUtilsKt.getShipObjectWorld(ClientLevel)`. */
            val shipWorld: Method,
            /** `Ship.getId()`. */
            val shipId: Method,
            /** `ClientShip.getRenderTransform()`. */
            val renderTransform: Method,
            /** `Matrix4dc.get(double[])`. */
            val readMatrix: Method,
            /** `VSGameUtilsKt.getLoadedShipManagingPos(ClientLevel, double, double, double)`. */
            val loadedShipAt: Method?,
        ) : Resolution()
    }

    /**
     * Cache key for a member resolved on a runtime class rather than on a named interface.
     *
     * [parameters] disambiguates overloads that share a name and an arity — `AABBic.containsPoint` has
     * three three-parameter spellings, and caching them together would hand the wrong `Method` back.
     */
    private data class MemberKey(
        val owner: Class<*>,
        val name: String,
        val arity: Int = 0,
        val parameters: String = "",
    )

    /**
     * Members already resolved per runtime class.
     *
     * `getAllShips()` is declared on a ship-world interface but implemented by a package-private
     * class, and the same is true of `getShipToWorld()` on the transform; the lookup therefore
     * happens per concrete class and is cached so a moving ship does not pay for it every frame.
     */
    private val memberCache = java.util.concurrent.ConcurrentHashMap<MemberKey, Method>()

    /** True once a successful lookup proved the mod is present and usable. */
    val isAvailable: Boolean get() = resolve() != null

    /** True when the mod's entry point could not be resolved on this client. */
    val isMissing: Boolean get() = resolve() == null && state === Resolution.Absent

    /**
     * Returns the ship-to-world matrix of [shipId] as 16 column-major doubles, or `null` when the
     * ship is not loaded on this client.
     *
     * `null` is the normal case for a ship outside the client's loaded chunks, and the caller must
     * fall back rather than treat it as identity — see [ShipAnchorPolicy.transformFor].
     */
    fun shipToWorldMatrix(level: ClientLevel?, shipId: Long): DoubleArray? {
        if (shipId <= 0L || level == null) return null
        val ready = resolve() ?: return null
        return runCatching {
            // Look the ship up by id rather than by position: a merged or renamed structure keeps its
            // id, and the display's stored coordinates are structure space, which no world-space
            // lookup helper accepts.
            val world = ready.shipWorld.invoke(null, level) ?: return null
            // `getAllShips()` is declared on `ClientShipWorld`/`ShipWorld`, but VS spells that
            // interface differently between releases and its implementation class is package-private,
            // so the member is resolved on the runtime class and opened for access. QueryableShipData
            // is a java.util.Collection, so plain iteration needs no generic parameter.
            val ships = invokeMember(world, "getAllShips") as? Iterable<*> ?: return null
            for (ship in ships) {
                if (ship == null) continue
                val id = (ready.shipId.invoke(ship) as? Long) ?: continue
                if (id != shipId) continue
                val transform = ready.renderTransform.invoke(ship) ?: return null
                // Resolved on the transform's own runtime class: `Ship.getShipToWorld()` exists too,
                // but a Ship-typed Method cannot be invoked with a transform receiver.
                val matrix = invokeMember(transform, "getShipToWorld") ?: return null
                return readMatrix(ready, matrix)
            }
            null
        }.getOrElse {
            // A ship that vanishes mid-frame is not an error worth a stack trace per frame; the
            // policy layer keeps the display at its structure coordinates until it comes back.
            null
        }
    }

    /**
     * Returns the id of the structure that owns the block at ([x], [y], [z]), or `0` when none does.
     *
     * This is the discovery half of the ship anchor. The server only samples the ship once, inside
     * `/display create`; a display built on loose blocks and *then* assembled into a ship therefore
     * arrives with `shipId = 0` and would stay world-anchored for the rest of the session — the
     * picture keeps its stored coordinates while the hull sails away. The client therefore probes for
     * itself and hands the scheduling to `ShipAnchorDetectPolicy`.
     *
     * Two lookups run because the two coordinate spaces differ and only a real VS install proves
     * which one a given display was stamped in:
     *
     * 1. `getLoadedShipManagingPos(level, x, y, z)` treats the argument as a **world** position and
     *    resolves it through the shipyard's chunk claims (`>>4`, verified in the bytecode). This hits
     *    while the display's blocks have not moved since they were assembled.
     * 2. A scan over `getAllShips()` comparing `getShipAABB()` (the **structure-space** voxel bounds)
     *    against the same numbers. This hits after the hull has moved, because the stored coordinates
     *    are structure space by design — see `ShipAnchor`'s contract.
     *
     * Both are cheap and both fail closed, so running them in order is cheaper than choosing wrong.
     * Never throws: an absent or incompatible install must degrade to "no structure here".
     */
    fun shipIdAt(level: ClientLevel?, x: Int, y: Int, z: Int): Long {
        if (level == null) return 0L
        val ready = resolve() ?: return 0L
        return runCatching {
            worldSpaceLookup(ready, level, x, y, z)?.takeIf { it > 0L }
                ?: structureSpaceLookup(ready, level, x, y, z)
        }.getOrElse {
            // A structure that vanishes mid-probe is ordinary and must not spam the log.
            0L
        }
    }

    /**
     * World-position lookup through `getLoadedShipManagingPos`.
     *
     * The block corner is offset by 0.5 so the sample lands inside the block rather than on an edge,
     * where a comparison against a chunk claim could fall the wrong way.
     */
    private fun worldSpaceLookup(ready: Resolution.Ready, level: ClientLevel, x: Int, y: Int, z: Int): Long? {
        val lookup = ready.loadedShipAt ?: return null
        val ship = lookup.invoke(
            null, level, x.toDouble() + 0.5, y.toDouble() + 0.5, z.toDouble() + 0.5,
        ) ?: return null
        return (ready.shipId.invoke(ship) as? Long)?.coerceAtLeast(0L)
    }

    /**
     * Structure-space lookup: iterate the loaded ships and test each one's voxel bounds.
     *
     * `getShipAABB()` is the **structure-space** bound (`getWorldAABB()` is the other one), which is
     * exactly the space the display's stored coordinates live in. Only `containsPoint` is used, so a
     * VS release that reshapes the AABB type still resolves as long as the three-`int` test survives;
     * everything is resolved on the runtime class and cached.
     */
    private fun structureSpaceLookup(ready: Resolution.Ready, level: ClientLevel, x: Int, y: Int, z: Int): Long {
        val world = ready.shipWorld.invoke(null, level) ?: return 0L
        val ships = invokeMember(world, "getAllShips") as? Iterable<*> ?: return 0L
        val int = Int::class.javaPrimitiveType ?: return 0L
        for (ship in ships) {
            if (ship == null) continue
            val bounds = invokeMember(ship, "getShipAABB") ?: continue
            // Match on the declared parameter types, not on the arity: AABBic declares
            // containsPoint(int, int, int), (float, float, float) and (Vector3ic), and picking the
            // first three-argument overload by reflection order could hand ints to a float signature.
            val inside = runCatching {
                invokeMemberWith(bounds, "containsPoint", arrayOf(int, int, int), arrayOf<Any?>(x, y, z)) as? Boolean
            }.getOrNull() ?: false
            if (!inside) continue
            val id = (ready.shipId.invoke(ship) as? Long)?.coerceAtLeast(0L) ?: 0L
            if (id > 0L) return id
        }
        return 0L
    }

    /**
     * Resolves `VSGameUtilsKt.getLoadedShipManagingPos(<level>, double, double, double)`.
     *
     * Returns `null` instead of throwing: the *transform* path is the one that must not break, and a
     * VS release that renames this helper should degrade to "world lookup unavailable" rather than
     * disable ship rendering entirely — the structure-space scan still covers it.
     *
     * The `ClientLevel` spelling comes first because that overload returns `ClientShip`, but the plain
     * `Level` one is tried too: `getMethod` matches declared parameter types exactly, and VS declares
     * most helpers against `Level`.
     */
    private fun resolveLoadedShipAt(utils: Class<*>): Method? {
        val double = Double::class.javaPrimitiveType
        val candidates = listOf(ClientLevel::class.java, Level::class.java)
        for (param in candidates) {
            runCatching {
                utils.getMethod("getLoadedShipManagingPos", param, double, double, double)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    /**
     * Invokes a method that takes arguments, matched on its **declared parameter types**.
     *
     * Arity alone is not enough: `AABBic` declares `containsPoint(int,int,int)`,
     * `containsPoint(float,float,float)` and `containsPoint(Vector3ic)`, so a three-argument scan could
     * resolve a `float` overload and then throw `IllegalArgumentException` on the `Integer` boxed
     * arguments. Matching the exact parameter list also keeps this safe across VS releases that add
     * another overload. Cached per class, name and parameter list.
     */
    private fun invokeMemberWith(
        receiver: Any,
        name: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any?>,
    ): Any? {
        val key = MemberKey(receiver.javaClass, name, parameterTypes.size, parameterTypes.joinToString(",") { it.name })
        val method = memberCache[key] ?: runCatching {
            // `getMethod` only returns *public* members of a *public* declaring class. VS implements
            // these interfaces in package-private classes often enough that the declared-method
            // fallback is required, not defensive padding (same trap as `invokeMember`).
            val declared = runCatching { receiver.javaClass.getMethod(name, *parameterTypes) }.getOrNull()
                ?: receiver.javaClass.getDeclaredMethod(name, *parameterTypes)
            declared.also { it.isAccessible = true }
        }.getOrNull()?.also { memberCache[key] = it } ?: return null
        return method.invoke(receiver, *args)
    }

    /**
     * Invokes a no-argument getter resolved on [receiver]'s runtime class, opening it first.
     *
     * Both lookups here land on package-private implementation classes of public interfaces
     * (`VsiClientShipWorld`'s implementation, the transform's), so `Method.invoke` throws
     * `IllegalAccessException` unless the member is made accessible. Results are cached per class.
     */
    private fun invokeMember(receiver: Any, name: String): Any? {
        val key = MemberKey(receiver.javaClass, name)
        val method = memberCache[key] ?: runCatching {
            receiver.javaClass.getMethod(name).also { it.isAccessible = true }
        }.getOrNull()?.also { memberCache[key] = it } ?: return null
        return method.invoke(receiver)
    }

    /**
     * Resolves `VSGameUtilsKt.getShipObjectWorld(<level>)`, tolerating either declared parameter type.
     *
     * `getMethod` matches **declared** parameter types exactly — it does not accept a subtype. VS
     * spells this the same way it spells `getAllShips`, i.e. it takes a plain `Level`, so asking for
     * `ClientLevel` throws `NoSuchMethodException` on every VS release and silently disabled this
     * whole bridge (the display then stayed at its structure coordinates while the hull sailed
     * away). The server bridge already documents the identical trap for `getAllShips`. Both
     * spellings are tried here so neither a future VS rename nor a variant signature can re-break it
     * the same silent way.
     */
    private fun resolveShipWorld(utils: Class<*>): Method {
        val candidates = ShipAnchorPolicy.levelFacadeCandidates().map { name ->
            when (name) {
                "ClientLevel" -> ClientLevel::class.java
                "ServerLevel" -> ClientLevel::class.java // unreachable on a client; kept for symmetry
                else -> Level::class.java
            }
        }.distinct()
        for (param in candidates) {
            runCatching { utils.getMethod("getShipObjectWorld", param) }.getOrNull()?.let { return it }
        }
        throw NoSuchMethodException(
            "VSGameUtilsKt.getShipObjectWorld: tried ${candidates.joinToString { it.simpleName }}",
        )
    }

    /**
     * The current transform for [shipId], or [AffineTransform.IDENTITY] when it cannot be resolved.
     *
     * Reads the cached resolution once per call and never throws: a broken or half-loaded install
     * must leave displays where the last good frame put them, not blank the world.
     */
    fun transformFor(shipId: Long): AffineTransform =
        ShipAnchorPolicy.transformFor(shipId, source())

    /** Wraps this bridge as the api-level source, binding the client level lazily per lookup. */
    fun source(): ShipTransformSource = ShipTransformSource { shipId ->
        shipToWorldMatrix(clientLevel(), shipId)
    }

    /** Reads the current client level, tolerating a client that is still starting up. */
    private fun clientLevel(): ClientLevel? =
        runCatching { net.minecraft.client.Minecraft.getInstance().level }.getOrNull()

    /** Copies a JOML `Matrix4dc` into a flat column-major array. */
    private fun readMatrix(ready: Resolution.Ready, matrix: Any?): DoubleArray? {
        if (matrix == null) return null
        val out = DoubleArray(ShipAnchorPolicy.MATRIX_SIZE)
        // Matrix4dc.get(double[]) writes 16 values in column-major order — the same layout
        // AffineTransform uses, so no transpose is needed.
        ready.readMatrix.invoke(matrix, out)
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
                val ship = Class.forName("org.valkyrienskies.core.api.ships.Ship")
                val clientShip = Class.forName("org.valkyrienskies.core.api.ships.ClientShip")
                val matrixClass = Class.forName("org.joml.Matrix4dc")
                Resolution.Ready(
                    shipWorld = resolveShipWorld(utils),
                    // getId() is declared on Ship and inherited by ClientShip; getRenderTransform()
                    // is the interpolated transform and lives on ClientShip itself.
                    shipId = ship.getMethod("getId"),
                    renderTransform = clientShip.getMethod("getRenderTransform"),
                    readMatrix = matrixClass.getMethod("get", DoubleArray::class.java),
                    // Optional: the transform path must keep working on a VS release that renames
                    // this helper, so a miss here disables discovery only, not ship rendering.
                    loadedShipAt = resolveLoadedShipAt(utils),
                ).also {
                    // INFO on success, not silence. A shipped log otherwise cannot distinguish "the
                    // mod is absent" from "the bridge resolved and drew in structure space anyway",
                    // and the 1.10.8.2 ship report had exactly that blind spot.
                    logger.info("Valkyrien Skies client bridge resolved; ship displays follow their hull.")
                }
            } catch (_: ClassNotFoundException) {
                // The mod is simply not installed; this is the normal case.
                Resolution.Absent
            } catch (t: Throwable) {
                // Installed but a different, incompatible version: report once, then stay inert.
                logger.warn("Valkyrien Skies is present but its client API did not match; ship displays stay in structure space.", t)
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
            // The runtime classes may have been reloaded between a test's two runs, so a cached
            // Method from the previous classloader must not be reused.
            memberCache.clear()
        }
    }
}
