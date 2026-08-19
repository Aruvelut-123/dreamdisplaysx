package com.dreamdisplayx.api.render.texture.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Per-frame limits used by render upload queues to avoid starving the game renderer.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
data class UploadBudget(
    /** Maximum number of frames to upload during one render frame. */
    val maxUploadsPerFrame: Int,

    /** Maximum pixel payload bytes to upload during one render frame. */
    val maxBytesPerFrame: Long,

    /** Target upload cadence in frames per second. */
    val targetFps: Int,
) {
    companion object {
        /** Conservative default budget for normal gameplay. */
        val DEFAULT = UploadBudget(
            maxUploadsPerFrame = 4,
            maxBytesPerFrame = 8 * 1024 * 1024L,
            targetFps = 30,
        )
    }
}
