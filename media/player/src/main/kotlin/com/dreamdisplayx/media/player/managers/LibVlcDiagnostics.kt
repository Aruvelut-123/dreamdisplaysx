package com.dreamdisplayx.media.player.managers

/**
 * Central diagnostic switches, each toggled by a JVM system property (`-Ddreamdisplayx.<name>=true`).
 *
 * These exist purely to bisect the intermittent native heap-corruption crash (0xC0000374 /
 * 0xC0000005) on pause/resume/seek: turn off one subsystem at a time and re-test, so we can pin
 * down whether the corruption comes from the audio line, the libvlc audio/video callbacks, the
 * frame sinks (preview/popout), hardware acceleration, or libvlc itself. All default to off (normal
 * behaviour).
 */
object LibVlcDiagnostics {

    /** Drop all audio in [onPlay] and never open the line (no Java Sound at all). */
    val silentAudio: Boolean by lazy { prop("dreamdisplayx.silentAudio") }

    /** Do not register the libvlc custom audio callbacks at all (libvlc uses its own audio handling). */
    val noAudioCallback: Boolean by lazy { prop("dreamdisplayx.noAudioCallback") }

    /** Do not register the libvlc custom video callbacks (libvlc uses its default vout). */
    val noVideoCallback: Boolean by lazy { prop("dreamdisplayx.noVideoCallback") }

    /** Do not register any preview/popout frame sink (no frame copy to the GUI). */
    val noFrameSink: Boolean by lazy { prop("dreamdisplayx.noFrameSink") }

    /** Disable the A/V auto-resync (the render-thread drift diagnostic + flush marker). */
    val noAutoResync: Boolean by lazy { prop("dreamdisplayx.noAutoResync") }

    /** Disable the low-level frame publish into the GPU surface (video frozen, audio only). */
    val noVideoPublish: Boolean by lazy { prop("dreamdisplayx.noVideoPublish") }

    /** Force the libvlc instance off (no hardware video decoding). */
    val noHardwareAccel: Boolean by lazy { prop("dreamdisplayx.noHardwareAccel") }

    private fun prop(name: String): Boolean =
        System.getProperty(name, "false").equals("true", ignoreCase = true)
}
