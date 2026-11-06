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
    /**
     * Never place a fence around a `PBO` upload and never wait on one.
     *
     * MobileGlues advertises desktop GL extensions on top of GLES, and its `glFenceSync` /
     * `glClientWaitSync` emulation is the one place in the upload path that can block for the full
     * `FENCE_TIMEOUT_NS` (100 ms) per frame — which by itself caps the game at ~10 FPS and, with
     * three `PBO`s in the ring, at ~1 FPS once the waits serialise. Turning this off keeps the
     * ring-buffer upload but trusts the ring depth alone, exactly like the pre-fence code.
     */
    val noFenceSync: Boolean by lazy { prop("dreamdisplayx.noFenceSync") }
    /**
     * Upload video frames straight from client memory with `glTexSubImage2D` instead of staging
     * them through a `PBO`.
     *
     * PBOs are the fast path on a real GL driver but a virtualised GLES bridge can accept the
     * calls without a working asynchronous copy. This is the coarsest bisect switch for a frame
     * rate that collapses once a video starts, and it costs one synchronising copy per frame.
     */
    val noPboUpload: Boolean by lazy { prop("dreamdisplayx.noPboUpload") }
    /**
     * Log per-frame timing for the decode-side CPU work and the GPU upload, throttled to one line
     * per second per source.
     *
     * A video that pins the game at 1 FPS is either starving the render thread (big CPU resize per
     * frame) or the GPU side (upload/fence). Two numbers separate those cases immediately, which is
     * otherwise invisible: the CPU fit runs on libvlc's callback thread and the upload on the render
     * thread, and neither logs its duration today.
     */
    val slowFrameLog: Boolean by lazy { prop("dreamdisplayx.slowFrameLog") }
    private fun prop(name: String): Boolean =
        System.getProperty(name, "false").equals("true", ignoreCase = true)
}
