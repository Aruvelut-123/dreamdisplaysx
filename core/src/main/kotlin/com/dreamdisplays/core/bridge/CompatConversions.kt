package com.dreamdisplays.core.bridge

import com.dreamdisplays.api.display.model.property.DisplayBounds as CompatDisplayBounds
import com.dreamdisplays.api.display.model.property.DisplayFacing as CompatDisplayFacing
import com.dreamdisplays.api.display.model.property.DisplayId as CompatDisplayId
import com.dreamdisplays.api.display.model.property.DisplayState as CompatDisplayState
import com.dreamdisplays.api.display.model.settings.DisplaySettings as CompatDisplaySettings
import com.dreamdisplays.api.media.model.DreamMediaException as CompatMediaException
import com.dreamdisplays.api.media.model.VideoQuality as CompatVideoQuality
import com.dreamdisplays.api.playback.model.PlaybackMode as CompatPlaybackMode
import com.dreamdisplays.api.playback.model.WatchPartySessionState as CompatWatchPartyState
import com.dreamdisplays.api.watchparty.WatchPartySession as CompatWatchPartySession
import com.dreamdisplayx.api.display.model.property.DisplayBounds as XDisplayBounds
import com.dreamdisplayx.api.display.model.property.DisplayFacing as XDisplayFacing
import com.dreamdisplayx.api.display.model.property.DisplayId as XDisplayId
import com.dreamdisplayx.api.display.model.property.DisplayState as XDisplayState
import com.dreamdisplayx.api.display.model.settings.DisplaySettings as XDisplaySettings
import com.dreamdisplayx.api.media.model.DreamMediaException as XMediaException
import com.dreamdisplayx.api.media.model.VideoQuality as XVideoQuality
import com.dreamdisplayx.api.playback.model.PlaybackMode as XPlaybackMode
import com.dreamdisplayx.api.playback.model.WatchPartySessionState as XWatchPartyState
import com.dreamdisplayx.api.watchparty.model.WatchPartySession as XWatchPartySession

/**
 * Conversions between the fork's own API types (`com.dreamdisplayx.api.*`) and the upstream-compatible
 * surface (`com.dreamdisplays.api.*`).
 *
 * The two surfaces describe the same values, so every conversion here is lossless and total. Keeping
 * them in one file makes it obvious when a new field is added on either side: the compiler then points
 * at exactly the constructor that has to grow.
 *
 * @since 1.10.x
 */

internal fun XDisplayId.toCompat(): CompatDisplayId = CompatDisplayId(uuid)

internal fun CompatDisplayId.toFork(): XDisplayId = XDisplayId(uuid)

internal fun XDisplayFacing.toCompat(): CompatDisplayFacing = CompatDisplayFacing.fromByte(byte)

internal fun CompatDisplayFacing.toFork(): XDisplayFacing = XDisplayFacing.fromByte(byte)

internal fun XDisplayBounds.toCompat(): CompatDisplayBounds = CompatDisplayBounds(
    x = x,
    y = y,
    z = z,
    width = width,
    height = height,
    facing = facing.toCompat(),
)

internal fun CompatDisplayBounds.toFork(): XDisplayBounds = XDisplayBounds(
    x = x,
    y = y,
    z = z,
    width = width,
    height = height,
    facing = facing.toFork(),
)

internal fun XVideoQuality.toCompat(): CompatVideoQuality = when (this) {
    XVideoQuality.Auto -> CompatVideoQuality.Auto
    is XVideoQuality.Fixed -> CompatVideoQuality.Fixed(height)
}

internal fun CompatVideoQuality.toFork(): XVideoQuality = when (this) {
    CompatVideoQuality.Auto -> XVideoQuality.Auto
    is CompatVideoQuality.Fixed -> XVideoQuality.Fixed(height)
}

internal fun XDisplaySettings.toCompat(): CompatDisplaySettings = CompatDisplaySettings(
    volume = volume,
    quality = quality.toCompat(),
    brightness = brightness,
    muted = muted,
    paused = paused,
    urlOverride = urlOverride,
    audioTrackName = audioTrackName,
)

internal fun CompatDisplaySettings.toFork(): XDisplaySettings = XDisplaySettings(
    volume = volume,
    quality = quality.toFork(),
    brightness = brightness,
    muted = muted,
    paused = paused,
    urlOverride = urlOverride,
    audioTrackName = audioTrackName,
)

/**
 * Rebuilds a fork media exception as the compatible one, keeping the subclass, message, cause,
 * failure [XMediaException.kind] and fatality flag.
 */
internal fun XMediaException.toCompat(): CompatMediaException = when (this) {
    is XMediaException.Network -> CompatMediaException.Network(message.orEmpty(), cause, isFatal)
    is XMediaException.Decode -> CompatMediaException.Decode(message.orEmpty(), cause, isFatal)
    is XMediaException.NotFound -> CompatMediaException.NotFound(message.orEmpty(), cause)
    is XMediaException.GeoBlocked -> CompatMediaException.GeoBlocked(message.orEmpty(), cause)
    is XMediaException.Timeout -> CompatMediaException.Timeout(message.orEmpty(), cause, isFatal)
    is XMediaException.Unknown -> CompatMediaException.Unknown(message.orEmpty(), cause, isFatal)
}

/** Rebuilds a compatible media exception as the fork one. Used only when forwarding addon-thrown values. */
internal fun CompatMediaException.toFork(): XMediaException = when (this) {
    is CompatMediaException.Network -> XMediaException.Network(message.orEmpty(), cause, isFatal)
    is CompatMediaException.Decode -> XMediaException.Decode(message.orEmpty(), cause, isFatal)
    is CompatMediaException.NotFound -> XMediaException.NotFound(message.orEmpty(), cause)
    is CompatMediaException.GeoBlocked -> XMediaException.GeoBlocked(message.orEmpty(), cause)
    is CompatMediaException.Timeout -> XMediaException.Timeout(message.orEmpty(), cause, isFatal)
    is CompatMediaException.Unknown -> XMediaException.Unknown(message.orEmpty(), cause, isFatal)
}

internal fun XDisplayState.toCompat(): CompatDisplayState = when (this) {
    XDisplayState.Idle -> CompatDisplayState.Idle
    XDisplayState.OutOfRange -> CompatDisplayState.OutOfRange
    XDisplayState.Preparing -> CompatDisplayState.Preparing
    is XDisplayState.Buffering -> CompatDisplayState.Buffering(sessionId)
    is XDisplayState.Playing -> CompatDisplayState.Playing(sessionId, positionMs, durationMs)
    is XDisplayState.Paused -> CompatDisplayState.Paused(sessionId, positionMs)
    is XDisplayState.Failed -> CompatDisplayState.Failed(cause.toCompat(), retryCount)
    XDisplayState.Stopped -> CompatDisplayState.Stopped
}

internal fun CompatDisplayState.toFork(): XDisplayState = when (this) {
    CompatDisplayState.Idle -> XDisplayState.Idle
    CompatDisplayState.OutOfRange -> XDisplayState.OutOfRange
    CompatDisplayState.Preparing -> XDisplayState.Preparing
    is CompatDisplayState.Buffering -> XDisplayState.Buffering(sessionId)
    is CompatDisplayState.Playing -> XDisplayState.Playing(sessionId, positionMs, durationMs)
    is CompatDisplayState.Paused -> XDisplayState.Paused(sessionId, positionMs)
    is CompatDisplayState.Failed -> XDisplayState.Failed(cause.toFork(), retryCount)
    CompatDisplayState.Stopped -> XDisplayState.Stopped
}

internal fun XPlaybackMode.toCompat(): CompatPlaybackMode = CompatPlaybackMode.fromWire(wire)

internal fun CompatPlaybackMode.toFork(): XPlaybackMode = XPlaybackMode.fromWire(wire)

internal fun XWatchPartyState.toCompat(): CompatWatchPartyState = CompatWatchPartyState.fromWire(wire)

internal fun CompatWatchPartyState.toFork(): XWatchPartyState = XWatchPartyState.fromWire(wire)

internal fun XWatchPartySession.toCompat(): CompatWatchPartySession = CompatWatchPartySession(
    sessionId = sessionId,
    state = state.toCompat(),
    isHost = isHost,
    hostName = hostName,
    readyCount = readyCount,
    nearbyCount = nearbyCount,
    positionMs = positionMs,
    countdownRemainingMs = countdownRemainingMs,
)

internal fun CompatWatchPartySession.toFork(): XWatchPartySession = XWatchPartySession(
    sessionId = sessionId,
    state = state.toFork(),
    isHost = isHost,
    hostName = hostName,
    readyCount = readyCount,
    nearbyCount = nearbyCount,
    positionMs = positionMs,
    countdownRemainingMs = countdownRemainingMs,
)
