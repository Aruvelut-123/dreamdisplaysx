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
            // Android audio output is not implemented yet: there is no AudioTrack-backed
            // FfmpegPcmSink. Returning null (rather than throwing) is what lets the session
            // run video-only on Android, with libvlc's OpenSL ES path still in charge of sound.
            null
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
     */
    val isSupported: Boolean
        get() = !OsInfo.isAndroid
}