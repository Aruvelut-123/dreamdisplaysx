package com.dreamdisplayx.api.display.geometry

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Builds an [AffineTransform] out of the plain numbers Sable's sub-level pose is made of.
 *
 * Sable describes a sub-level with a `dev.ryanhcode.sable.companion.math.Pose3d`, which is a
 * translation, a quaternion rotation, a scale and a *rotation point*:
 *
 * ```
 * world = T(position) * R(orientation) * S(scale) * T(-rotationPoint)
 * ```
 *
 * That is exactly what its own `Pose3dc.bakeIntoMatrix(Matrix4d)` compiles to (verified in the
 * bytecode: `identity` → `translate(position)` → `rotate(orientation)` → `scale(scale)` →
 * `translate(-rotationPoint)`). Reimplementing the composition here rather than calling that method
 * keeps the whole calculation inside the dependency-free `api` module: no JOML type has to exist on
 * the classpath, and the arithmetic becomes the one part of Sable support a unit test can prove.
 *
 * The bridge therefore only has to *read* thirteen doubles off the pose
 * (`position.xyz`, `orientation.xyzw`, `scale.xyz`, `rotationPoint.xyz`) and hand them over.
 *
 * All matrices are 16 doubles in **column-major** order (`m[column * 4 + row]`), the layout
 * [AffineTransform] already documents and the layout JOML's `Matrix4d.get(double[])` writes, so the
 * result can be compared against a live matrix without a transpose.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
object SubLevelPose {
    /** Number of doubles in a column-major 4x4 matrix. */
    const val MATRIX_SIZE: Int = ShipAnchorPolicy.MATRIX_SIZE

    /**
     * Composes the sub-level's transformation matrix from its pose components.
     *
     * The rotation is extracted from the quaternion with the standard `(x, y, z, w)` convention —
     * the one JOML's `Quaterniond` uses, which is what Sable stores. A quaternion that is not unit
     * length is normalised first so a slightly denormalised pose cannot scale the picture; a
     * degenerate one (`length <= 0`, or non-finite) falls back to no rotation, because a blank
     * orientation is recoverable while a NaN matrix blanks every display that renders it.
     *
     * @param position translation applied last, in world space
     * @param orientation quaternion `(x, y, z, w)` of the rotation
     * @param scale per-axis scale, applied to the structure's own axes
     * @param rotationPoint the point the rotation and scale are anchored at; negated on the inside
     */
    fun matrixOf(
        position: DoubleArray,
        orientation: DoubleArray,
        scale: DoubleArray,
        rotationPoint: DoubleArray,
    ): DoubleArray {
        val basis = scaledRotation(orientation, scale)
        val rp = rotationPoint.ifSize(3)
        // Translation column: the rotation+scale applied to the negated pivot, then the position.
        // `-(R*S) * rp + position` — the exact order `T(p) * R * S * T(-rp)` produces.
        val tx = position[0] - (basis[0] * rp[0] + basis[3] * rp[1] + basis[6] * rp[2])
        val ty = position[1] - (basis[1] * rp[0] + basis[4] * rp[1] + basis[7] * rp[2])
        val tz = position[2] - (basis[2] * rp[0] + basis[5] * rp[1] + basis[8] * rp[2])
        return doubleArrayOf(
            basis[0], basis[1], basis[2], 0.0,
            basis[3], basis[4], basis[5], 0.0,
            basis[6], basis[7], basis[8], 0.0,
            tx, ty, tz, 1.0,
        )
    }

    /**
     * The exact inverse of [matrixOf] for the same pose.
     *
     * Used to answer "which structure-space point is this world point" once, at discovery time, when
     * a display was created on loose blocks that were assembled into a sub-level afterwards. The row
     * then stores a **world** position while the renderer multiplies structure space, and the
     * correction `worldToShip` captured here is what makes both true at once — see
     * `DisplayScreen.anchoredTransform`.
     *
     * Composed as `T(rotationPoint) * S^-1 * R^-1 * T(-position)`. Returns `null` when the pose has no
     * inverse: any non-finite component, or a zero scale on any axis (which would flatten the
     * structure onto a plane or a line, and mapping a point back through it is genuinely undefined).
     */
    fun inverseMatrixOf(
        position: DoubleArray,
        orientation: DoubleArray,
        scale: DoubleArray,
        rotationPoint: DoubleArray,
    ): DoubleArray? {
        if (!scale[0].isFinite() || !scale[1].isFinite() || !scale[2].isFinite()) return null
        if (kotlin.math.abs(scale[0]) <= EPSILON ||
            kotlin.math.abs(scale[1]) <= EPSILON ||
            kotlin.math.abs(scale[2]) <= EPSILON
        ) {
            return null
        }
        // M^-1 = T(rp) * S^-1 * R^-1 * T(-p). For a rotation `R^-1 == R^T`, so entry
        // `(c, row)` of the inverse basis is `R[row][c] / scale[row]`: the scale to undo is the one
        // that was applied along the *output* axis. In this column-major array the flat index is
        // `c * 3 + row`, and `R[row][c]` lives at flat index `row * 3 + c` — i.e. the transpose.
        // Dividing by the wrong axis still round-trips a *uniformly* scaled pose, which is why a
        // non-uniform case has to be pinned by a test.
        val r = rotation(orientation)
        val inv = DoubleArray(9) { i -> r[(i % 3) * 3 + (i / 3)] / scale[i % 3] }
        val rp = rotationPoint.ifSize(3)
        // Translation: rp - inv * position.
        val tx = rp[0] - (inv[0] * position[0] + inv[3] * position[1] + inv[6] * position[2])
        val ty = rp[1] - (inv[1] * position[0] + inv[4] * position[1] + inv[7] * position[2])
        val tz = rp[2] - (inv[2] * position[0] + inv[5] * position[1] + inv[8] * position[2])
        val out = doubleArrayOf(
            inv[0], inv[1], inv[2], 0.0,
            inv[3], inv[4], inv[5], 0.0,
            inv[6], inv[7], inv[8], 0.0,
            tx, ty, tz, 1.0,
        )
        return if (out.all { it.isFinite() }) out else null
    }

    /**
     * The 3x3 basis of `R * S`, flattened column-major into nine doubles.
     *
     * Column `j` of `R * S` is column `j` of `R` scaled by `scale[j]`, so the scale multiplies down
     * each column of the rotation.
     */
    private fun scaledRotation(orientation: DoubleArray, scale: DoubleArray): DoubleArray {
        val r = rotation(orientation)
        val sx = scale[0]
        val sy = scale[1]
        val sz = scale[2]
        return doubleArrayOf(
            r[0] * sx, r[1] * sx, r[2] * sx,
            r[3] * sy, r[4] * sy, r[5] * sy,
            r[6] * sz, r[7] * sz, r[8] * sz,
        )
    }

    /**
     * The 3x3 rotation basis of [orientation], column-major, normalised.
     *
     * A degenerate quaternion yields the identity basis rather than NaNs: one display drawn with the
     * wrong orientation is a visual bug, a NaN matrix that the renderer multiplies into the pose stack
     * is a black screen.
     */
    private fun rotation(orientation: DoubleArray): DoubleArray {
        var qx = orientation[0]
        var qy = orientation[1]
        var qz = orientation[2]
        var qw = orientation[3]
        val length = kotlin.math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        if (!length.isFinite() || length <= EPSILON) {
            return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        }
        qx /= length
        qy /= length
        qz /= length
        qw /= length
        val xx = qx * qx
        val yy = qy * qy
        val zz = qz * qz
        val xy = qx * qy
        val xz = qx * qz
        val yz = qy * qz
        val wx = qw * qx
        val wy = qw * qy
        val wz = qw * qz
        // Row-major values, then written out column-major below.
        val r00 = 1.0 - 2.0 * (yy + zz)
        val r01 = 2.0 * (xy - wz)
        val r02 = 2.0 * (xz + wy)
        val r10 = 2.0 * (xy + wz)
        val r11 = 1.0 - 2.0 * (xx + zz)
        val r12 = 2.0 * (yz - wx)
        val r20 = 2.0 * (xz - wy)
        val r21 = 2.0 * (yz + wx)
        val r22 = 1.0 - 2.0 * (xx + yy)
        return doubleArrayOf(
            r00, r10, r20,
            r01, r11, r21,
            r02, r12, r22,
        )
    }

    /** Pads a short component array with zeros so a defensive read cannot index out of bounds. */
    private fun DoubleArray.ifSize(size: Int): DoubleArray =
        if (this.size == size) this else DoubleArray(size) { if (it < this.size) this[it] else 0.0 }

    /** Tolerance used for the degenerate-scale and degenerate-quaternion checks. */
    private const val EPSILON: Double = 1.0e-12
}
