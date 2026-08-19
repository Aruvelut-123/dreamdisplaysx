package com.dreamdisplayx.api.render.backend.service

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Per-frame render input shared with display surfaces.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
interface RenderContext {
    /** Partial tick / frame interpolation value. */
    val tickDelta: Float

    /** Camera X position in world coordinates. */
    val cameraX: Double

    /** Camera Y position in world coordinates. */
    val cameraY: Double

    /** Camera Z position in world coordinates. */
    val cameraZ: Double
}
