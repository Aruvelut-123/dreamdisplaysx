package com.dreamdisplayx.api.display.geometry

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Splits an [AffineTransform] into the three primitives a `PoseStack` can apply on every Minecraft
 * version: translation, a rotation quaternion, and a scale.
 *
 * This exists instead of feeding the matrix into the pose stack directly because the pose API is not
 * stable across the versions this fork builds: `PoseStack.mulPose(Quaternionf)` became
 * `rotate(Quaternionf)` in 26.3, and `mulPoseMatrix` is not something the fork has ever verified
 * outside the versions it happens to compile. `translate` / quaternion rotation / `scale` are the
 * three calls the renderer already uses on all eleven targets.
 *
 * The decomposition is `M = T * R * S`, which is exactly the order a matrix with no shear has, and
 * the order the caller must apply them in. Anything the affine subset cannot express (shear, a
 * mirrored or collapsed basis) returns `null` rather than a wrong transform.
 *
 * Pure doubles on purpose: `:api` carries no JOML and no Minecraft types, so this is the only place
 * the math can live and still be unit-tested.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
object AffineDecomposition {
    /**
     * The three primitives, in application order.
     *
     * [quaternion] is `(x, y, z, w)` to match JOML's `Quaternionf(x, y, z, w)`.
     */
    class Parts(
        val translationX: Double,
        val translationY: Double,
        val translationZ: Double,
        val quaternionX: Double,
        val quaternionY: Double,
        val quaternionZ: Double,
        val quaternionW: Double,
        val scaleX: Double,
        val scaleY: Double,
        val scaleZ: Double,
    ) {
        override fun toString(): String =
            "Parts(t=($translationX, $translationY, $translationZ), " +
                "q=($quaternionX, $quaternionY, $quaternionZ, $quaternionW), " +
                "s=($scaleX, $scaleY, $scaleZ))"
    }

    /** Below this column length the basis counts as collapsed and the matrix is unusable. */
    private const val MIN_AXIS = 1.0e-8

    /**
     * Decomposes [transform], or returns `null` when it is not `T * R * S`.
     *
     * A ship-to-world matrix from a physics mod is a rigid transform (rotation, translation, and
     * sometimes a uniform scale), so this succeeds in practice; `null` is the answer for a matrix a
     * broken physics step produced, and the caller must then draw the display untransformed.
     */
    fun of(transform: AffineTransform): Parts? {
        // Columns of the linear part, column-major: column c starts at index c * 4.
        val c0x = transform.at(0, 0)
        val c0y = transform.at(0, 1)
        val c0z = transform.at(0, 2)
        val c1x = transform.at(1, 0)
        val c1y = transform.at(1, 1)
        val c1z = transform.at(1, 2)
        val c2x = transform.at(2, 0)
        val c2y = transform.at(2, 1)
        val c2z = transform.at(2, 2)

        val scaleX = length(c0x, c0y, c0z)
        val scaleY = length(c1x, c1y, c1z)
        val scaleZ = length(c2x, c2y, c2z)
        if (scaleX <= MIN_AXIS || scaleY <= MIN_AXIS || scaleZ <= MIN_AXIS) return null

        // Normalised basis rows/columns of the pure rotation, needed by the quaternion extraction.
        val r00 = c0x / scaleX
        val r10 = c0y / scaleX
        val r20 = c0z / scaleX
        val r01 = c1x / scaleY
        val r11 = c1y / scaleY
        val r21 = c1z / scaleY
        val r02 = c2x / scaleZ
        val r12 = c2y / scaleZ
        val r22 = c2z / scaleZ

        val quaternion = quaternionOf(r00, r10, r20, r01, r11, r21, r02, r12, r22) ?: return null
        return Parts(
            translationX = transform.at(3, 0),
            translationY = transform.at(3, 1),
            translationZ = transform.at(3, 2),
            quaternionX = quaternion[0],
            quaternionY = quaternion[1],
            quaternionZ = quaternion[2],
            quaternionW = quaternion[3],
            scaleX = scaleX,
            scaleY = scaleY,
            scaleZ = scaleZ,
        )
    }

    private fun length(x: Double, y: Double, z: Double): Double = kotlin.math.sqrt(x * x + y * y + z * z)

    /**
     * Extracts `(x, y, z, w)` from a rotation basis given as `r[row][column]`-ordered arguments
     * (the names spell out the indices). Uses Shepperd's method: pick the largest of the four
     * candidate denominators so the result never divides by a near-zero, which is what makes the
     * naive trace formula produce a zero quaternion for a 180-degree turn.
     */
    private fun quaternionOf(
        r00: Double, r10: Double, r20: Double,
        r01: Double, r11: Double, r21: Double,
        r02: Double, r12: Double, r22: Double,
    ): DoubleArray? {
        val trace = r00 + r11 + r22
        val x: Double
        val y: Double
        val z: Double
        val w: Double
        if (trace > 0.0) {
            val s = kotlin.math.sqrt(trace + 1.0) * 2.0
            w = 0.25 * s
            x = (r21 - r12) / s
            y = (r02 - r20) / s
            z = (r10 - r01) / s
        } else if (r00 > r11 && r00 > r22) {
            val s = kotlin.math.sqrt(1.0 + r00 - r11 - r22) * 2.0
            w = (r21 - r12) / s
            x = 0.25 * s
            y = (r01 + r10) / s
            z = (r02 + r20) / s
        } else if (r11 > r22) {
            val s = kotlin.math.sqrt(1.0 + r11 - r00 - r22) * 2.0
            w = (r02 - r20) / s
            x = (r01 + r10) / s
            y = 0.25 * s
            z = (r12 + r21) / s
        } else {
            val s = kotlin.math.sqrt(1.0 + r22 - r00 - r11) * 2.0
            w = (r10 - r01) / s
            x = (r02 + r20) / s
            y = (r12 + r21) / s
            z = 0.25 * s
        }
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !w.isFinite()) return null
        val norm = kotlin.math.sqrt(x * x + y * y + z * z + w * w)
        if (!norm.isFinite() || norm <= MIN_AXIS) return null
        return doubleArrayOf(x / norm, y / norm, z / norm, w / norm)
    }
}