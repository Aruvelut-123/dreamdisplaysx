package com.dreamdisplayx.media.player.util

/** Pure Android decoder policy kept separate so JVM tests can cover the software fallback. */
internal object AndroidDecoderPolicy {
    const val SOFTWARE_MODULE = "avcodec"

    /**
     * Returns the libvlc `--codec` module for the Android vmem path.
     *
     * The Pojav/FCL JVM does not expose Android framework classes such as MediaCodecList, so the
     * MediaCodec modules log an init failure and then fall through to avcodec anyway. Selecting
     * avcodec directly avoids that failed JNI probe and bounds the native decoder state. A non-empty
     * system-property override remains available for launchers that provide a working MediaCodec
     * bridge; an empty override explicitly means software decoding.
     */
    fun module(
        useHwAccel: Boolean,
        noHardwareAccel: Boolean,
        override: String?,
    ): String {
        if (!useHwAccel || noHardwareAccel) return SOFTWARE_MODULE
        return override?.takeIf { it.isNotBlank() } ?: SOFTWARE_MODULE
    }
}
