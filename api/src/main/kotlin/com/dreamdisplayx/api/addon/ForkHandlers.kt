package com.dreamdisplayx.api.addon

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Handler annotations a [ForkAddon] can put on its methods.
 *
 * Every annotation follows the same contract as upstream's handlers: the method receives a *prefix* of the available
 * parameters, in order, and may declare fewer than offered. A method may be `private`; the binder makes it
 * accessible. Return values are ignored.
 *
 * These cover events upstream has no equivalent for. The upstream set (`OnDisplay`, `OnPlaying`, `OnFailed`,
 * `OnDisplayCreated`, `OnDisplayRemoved`, `OnUrlChanged`) keeps working unchanged on a fork addon too — both binder
 * families run for every addon.
 *
 * @since 1.10.x
 */

/**
 * `(Display)`, `(Display, VideoQuality)` or `(Display, VideoQuality, VideoQuality)` — called when a display's
 * quality setting changes; the second argument is the new quality, the third (when declared) the previous one.
 */
@DreamDisplaysXUnstableApi
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnQualityChanged

/**
 * `(Display)`, `(Display, Float)` or `(Display, Float, Float)` — called when a display's volume changes; the new
 * volume, then the previous one.
 */
@DreamDisplaysXUnstableApi
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnVolumeChanged

/**
 * `(Display)`, `(Display, Float)` or `(Display, Float, Float)` — called when a display's brightness changes; the new
 * brightness, then the previous one.
 */
@DreamDisplaysXUnstableApi
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnBrightnessChanged

/** `(Display)`, `(Display, Long)` or `(Display, Long, Long)` — the playback position advanced; new then previous ms. */
@DreamDisplaysXUnstableApi
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnPlaybackPosition

/** `(Display)` or `(Display, DisplayState)` — the display changed state for any reason (not just play/pause). */
@DreamDisplaysXUnstableApi
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnStateChanged

/** `(Display)` — the display finished initialising and now holds a usable surface (the first frame arrived). */
@DreamDisplaysXUnstableApi
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnFirstFrame

/** `(Display)` — the display stopped because playback reached the end of the video. */
@DreamDisplaysXUnstableApi
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnEnded