package com.dreamdisplayx.api.media.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Platform-free pixel layout for decoded video frames; platform maps to concrete formats.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
enum class FramePixelFormat(val bytesPerPixel: Int) {
    /** Single-channel plane of an RGB frame. */
    RGB24(3),

    /** Four-channel plane of an RGBA frame. */
    RGBA32(4),

    /**
     * Four-channel plane of a BGRA frame (libvlc RV32). Kept distinct from [RGBA32] so the GPU
     * uploader can pass `GL_BGRA` without a per-pixel colour swap.
     */
    BGRA32(4),

    /** Single-channel plane of an R8 frame. */
    R8(1),
}
