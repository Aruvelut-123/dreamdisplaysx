package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.util.OsInfo

/**
 * Picks the platform's [FfmpegPcmSink] implementation.
 *
 * ## Why the check lives here and nowhere else
 *
 * `FfmpegDesktopPcmSink` names `javax.sound.sampled` types; `FfmpegAndroidPcmSink`
 * names `android.media.AudioTrack`. Neither class exists on the other platform,
 * and the failure is not a graceful one — the *linking* of the class throws
 * `NoClassDefFoundError`, so a `try/catch` inside the implementation can never
 * help; by the time it runs, its imports have already been resolved.
 *
 * The only working arrangement is therefore what this object does: branch on
 * [OsInfo.isAndroid] **before** any platform class name appears in reachable
 * code. A caller must go through [create] rather than constructing a sink
 * directly, or a desktop JVM will crash the moment an Android display starts.
 *
 * ## Why a null return is not a failure
 *
 * A platform with no output device (a headless server host, an Android build
 * without audio permission, a missing sound card) yields `null`, and the audio
 * branch of `FfmpegSession` treats that as "no sound" instead of an error. A
 * silent picture is a degraded experience; refusing to play is not.
 */
internal object FfmpegPcmSinks {

    /**
     * Creates the sink for the current platform.
     *
     * @param debugLabel session label for logs
     * @return the platform sink, or `null` when this build has no audio output
     *   for the platform at all
     */
    fun create(debugLabel: String): FfmpegPcmSink? =
        if (OsInfo.isAndroid) {
            // Android now has an AudioTrack-backed sink. Naming it here is NOT the
            // same hazard as naming a `android.media.*` type: FfmpegAndroidPcmSink
            // touches Android only through Class.forName/reflection, so its own
            // linking never resolves an android class. Safe on a Pojav game JVM
            // (where `android.media.AudioTrack` does not exist at all: open() then
            // returns false and the display simply plays without sound).
            FfmpegAndroidPcmSink(debugLabel)
        } else {
            // Desktop only. Naming this type inside the branch is safe because
            // the JVM links it lazily, at first use, and this line is only
            // reached when we are demonstrably not on Android.
            FfmpegDesktopPcmSink(debugLabel)
        }

    /**
     * Whether this platform has a working audio sink implementation.
     *
     * Callers use this to decide between "FFmpeg owns audio" and "keep libvlc for
     * audio" without constructing a sink first — creating one opens a device.
     *
     * True everywhere now: Android joined when [FfmpegAndroidPcmSink] landed, so the
     * FFmpeg pipeline can own both video and audio on every supported platform.
     */
    val isSupported: Boolean
        get() = true
}