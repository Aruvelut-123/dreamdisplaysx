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
     * Returns the libvlc `--codec` module (or comma-separated chain) for the Android vmem path.
     *
     * The chain must stay a CHAIN. An earlier version pinned bare `avcodec` on every Android session,
     * which forced a two-thread software decode that never produced a vout frame at all while
     * starving the game down to a couple of FPS ("no picture + FPS collapsed" on MobileGlues).
     * MediaCodec has to stay first so a decoder can deliver frames straight into vmem.
     *
     * A non-blank `-Ddreamdisplayx.hwDecode` override replaces the whole chain; an empty override
     * explicitly requests software-only decoding.
     */
    fun module(
        useHwAccel: Boolean,
        noHardwareAccel: Boolean,
        override: String?,
    ): String {
        if (override != null) {
            val trimmed = override.trim()
            if (trimmed.isEmpty()) return SOFTWARE_MODULE
            return trimmed
        }
        if (!useHwAccel || noHardwareAccel) return SOFTWARE_MODULE
        return HARDWARE_CHAIN
    }
}
