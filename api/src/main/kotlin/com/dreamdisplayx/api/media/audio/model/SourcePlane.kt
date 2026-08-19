package com.dreamdisplayx.api.media.audio.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * World-space planar sound source (rectangle with center, axes, and normal); one block = one meter.
 *
 * @since 1.9.x
 */
@DreamDisplaysXUnstableApi
data class SourcePlane(
    val centerX: Double,
    val centerY: Double,
    val centerZ: Double,
    val normalX: Double,
    val normalY: Double,
    val normalZ: Double,
    val uAxisX: Double,
    val uAxisY: Double,
    val uAxisZ: Double,
    val vAxisX: Double,
    val vAxisY: Double,
    val vAxisZ: Double,
    val width: Double,
    val height: Double,
)
