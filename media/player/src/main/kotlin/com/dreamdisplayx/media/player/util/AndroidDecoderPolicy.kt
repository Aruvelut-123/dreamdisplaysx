package com.dreamdisplayx.media.player.util

/** Pure Android decoder policy kept separate so JVM tests can cover the module chain. */
internal object AndroidDecoderPolicy {
    /**
     * The order libvlc tries Android video decoders. `mediacodec_ndk` (API 21+) is the only module
     * that can hand a decoded frame to a vmem output directly, `mediacodec_jni` is the legacy
     * fallback, and `any` lets libvlc reach software avcodec if neither MediaCodec module
     * initialises (Pojav-style JVMs do not expose `android.media.MediaCodecList`).
     */
    const val HARDWARE_CHAIN = "mediacodec_ndk,mediacodec_jni,any"

    /** Software-only module, used when hardware decode is disabled or explicitly overridden empty. */
    const val SOFTWARE_MODULE = "avcodec"

    /**
     * Explicit `-Ddreamdisplayx.hwDecode` module chain, or null when libvlc should choose itself.
     *
     * Android must not receive `--codec` by default. `--codec` is a hard filter, and a vmem output
     * cannot satisfy `mediacodec_ndk`'s Surface requirement, so a forced chain leaves libvlc with no
     * usable video output (observed: `hasVout=0` while the stream decodes and the player is playing).
     * Only an explicit override is forwarded; an empty override means software-only.
     */
    fun explicitModule(override: String?): String? {
        if (override == null) return null
        val trimmed = override.trim()
        return if (trimmed.isEmpty()) SOFTWARE_MODULE else trimmed
    }
}
