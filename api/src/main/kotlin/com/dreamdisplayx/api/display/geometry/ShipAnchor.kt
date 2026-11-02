package com.dreamdisplayx.api.display.geometry

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * A display that rides on a moving structure (e.g. a Valkyrien Skies ship).
 *
 * The display's block coordinates stay in the structure's own coordinate space: every surface the
 * player can click is resolved inside the structure, so the anchor never has to be rewritten as the
 * structure moves. Only [shipId] has to be stored; the transform itself is resolved from the live
 * world every frame.
 *
 * This type is deliberately free of any mod or JOML dependency: the `api` module is a verbatim copy
 * of upstream's tree and must stay a pure-Kotlin artifact, so ship integration crosses this boundary
 * as plain numbers.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
data class ShipAnchor(
    /** The platform-assigned id of the structure, or [NONE] for a display anchored in the world. */
    val shipId: Long = NONE,
) {
    /** True when this display rides a structure instead of standing in the world. */
    val isOnShip: Boolean get() = shipId != NONE

    companion object {
        /** Sentinel used when the display is anchored directly in the world. */
        const val NONE: Long = 0L

        /** A world-anchored display: the overwhelmingly common case. */
        val world: ShipAnchor = ShipAnchor()

        /** Wraps a platform structure id, normalising non-positive ids back to [world]. */
        fun of(shipId: Long): ShipAnchor = if (shipId <= NONE) world else ShipAnchor(shipId)

        /** An anchor on the structure [shipId], or [world] when the id is absent. */
        fun ofNullable(shipId: Long?): ShipAnchor = shipId?.let(::of) ?: world
    }
}

/**
 * A row-major 4x4 transform, stored flat so it crosses module and reflection boundaries without a
 * matrix library.
 *
 * Only the affine subset is supported: the last row is implicitly `0 0 0 1`. Indices run
 * `m[column * 4 + row]`, matching JOML's column-major layout, so a live platform matrix can be
 * copied over without transposing.
 *
 * Deliberately **not** a `data class`: the backing [m] is an array, and a synthesized `equals` would
 * compare it by reference, making two mathematically equal transforms unequal.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
class AffineTransform(
    val m: DoubleArray,
) {
    init {
        require(m.size == 16) { "an affine 4x4 transform needs 16 components, got ${m.size}" }
    }

    /** Copies the 16 components so a caller-held matrix can never mutate this value. */
    fun copy(): AffineTransform = AffineTransform(m.copyOf())

    /** Returns the component at [column], [row] (0-based). */
    fun at(column: Int, row: Int): Double = m[column * 4 + row]

    /** True when every component is finite; a NaN matrix would silently blank the screen. */
    val isFinite: Boolean get() = m.all { it.isFinite() }

    /** True when this is (numerically) the identity transform. */
    val isIdentity: Boolean
        get() = m.indices.all { i -> kotlin.math.abs(m[i] - IDENTITY.m[i]) <= EPSILON }

    /**
     * Transforms the point ([x], [y], [z]) and returns the result as a new [DoubleArray] of length 3.
     * The implicit `w = 1` is applied, so a pure rotation+translation matrix maps correctly.
     */
    fun transformPoint(x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )

    /**
     * Transforms the direction ([x], [y], [z]) — no translation, and **without** normalising, so a
     * scaled structure keeps its scale factor. Use [transformDirection] for a unit-length result.
     */
    fun transformVector(x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        m[0] * x + m[4] * y + m[8] * z,
        m[1] * x + m[5] * y + m[9] * z,
        m[2] * x + m[6] * y + m[10] * z,
    )

    /**
     * Transforms and normalises the direction ([x], [y], [z]); returns the input unchanged when the
     * result degenerates to zero length (a fully collapsed structure must not produce NaNs).
     */
    fun transformDirection(x: Double, y: Double, z: Double): DoubleArray {
        val v = transformVector(x, y, z)
        val length = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (!length.isFinite() || length <= EPSILON) return doubleArrayOf(x, y, z)
        return doubleArrayOf(v[0] / length, v[1] / length, v[2] / length)
    }

    /** Returns `this * [other]`: [other] is applied first, then this transform. Used to compose a
     * structure transform with the display's local block transform. */
    fun times(other: AffineTransform): AffineTransform {
        val out = DoubleArray(16)
        for (column in 0..3) for (row in 0..3) {
            var sum = 0.0
            for (k in 0..3) sum += at(k, row) * other.at(column, k)
            out[column * 4 + row] = sum
        }
        return AffineTransform(out)
    }

    /**
     * Component-wise equality with [EPSILON] tolerance.
     *
     * Reflexive like the contract requires, so an instance that contains a NaN still equals itself.
     * Two *distinct* instances are never equal when either has a NaN component: `NaN != NaN`, and
     * pretending otherwise would make a broken transform compare equal to a broken transform.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AffineTransform) return false
        for (i in m.indices) {
            val a = m[i]
            val b = other.m[i]
            if (a.isNaN() || b.isNaN()) return false
            if (kotlin.math.abs(a - b) > EPSILON) return false
        }
        return true
    }

    /**
     * Exact component hash. Intentionally stricter than [equals] (which allows [EPSILON] drift): a
     * tolerance-based hash cannot be made consistent at this scale without collapsing every value to
     * the same bucket, so this type is safe as a value comparison but must not be used as a hash key
     * when tolerance-equal instances are expected to collide.
     */
    override fun hashCode(): Int = m.contentHashCode()

    override fun toString(): String = (0..3).joinToString(prefix = "AffineTransform[", postfix = "]") { row ->
        (0..3).joinToString(prefix = "[", postfix = "]", separator = ", ") { column ->
            "%.4f".format(at(column, row))
        }
    }

    companion object {
        /** Tolerance for the identity comparison; well below a visible offset. */
        const val EPSILON: Double = 1.0e-9

        /** The identity transform. */
        val IDENTITY: AffineTransform = AffineTransform(
            doubleArrayOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )

        /**
         * Builds a transform that rotates by [yawDegrees] around the Y axis, then translates by
         * ([x], [y], [z]). Mirrors the rotation order a ship pose is applied with, and keeps the
         * math testable without a platform matrix type.
         */
        fun ofYawAndTranslation(
            yawDegrees: Double,
            x: Double,
            y: Double,
            z: Double,
        ): AffineTransform {
            val radians = Math.toRadians(yawDegrees)
            val cos = kotlin.math.cos(radians)
            val sin = kotlin.math.sin(radians)
            return AffineTransform(
                doubleArrayOf(
                    cos, 0.0, -sin, 0.0,
                    0.0, 1.0, 0.0, 0.0,
                    sin, 0.0, cos, 0.0,
                    x, y, z, 1.0,
                ),
            )
        }

        /** Builds a pure translation transform. */
        fun ofTranslation(x: Double, y: Double, z: Double): AffineTransform = AffineTransform(
            doubleArrayOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                x, y, z, 1.0,
            ),
        )
    }
}
