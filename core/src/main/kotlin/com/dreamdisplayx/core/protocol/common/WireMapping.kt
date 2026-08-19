package com.dreamdisplayx.core.protocol.common

import com.dreamdisplayx.api.capability.ServerFeature
import com.dreamdisplayx.api.playback.model.PlaybackMode
import com.dreamdisplayx.api.playback.model.Timeline
import com.dreamdisplayx.core.protocol.common.packets.DisplaySync
import com.dreamdisplayx.core.protocol.common.packets.ServerHello
import kotlinx.serialization.Serializable
import java.util.*

/** True if this server snapshot advertises [feature]. */
fun ServerHello.hasFeature(feature: ServerFeature): Boolean =
    feature.wire in allowedFeatures

/** Builds the wire [DisplaySync] for [id] in [mode], stamped with the current [nowMs] anchor. */
fun Timeline.toSync(id: @Serializable(UuidSerializer::class) UUID, mode: PlaybackMode, nowMs: Long): DisplaySync {
    val anchored = anchoredAt(nowMs)
    return DisplaySync(
        id = id,
        isSync = mode == PlaybackMode.SYNCED,
        isPaused = anchored.paused,
        currentTimeMs = anchored.positionMs,
        durationMs = anchored.durationMs,
        serverTimeMs = anchored.serverTimeMs,
        loop = anchored.loop,
        mode = mode.wire,
    )
}

/** Reconstructs a core [Timeline] from an incoming wire [DisplaySync] packet. */
fun DisplaySync.toTimeline(): Timeline = Timeline(
    positionMs = currentTimeMs,
    serverTimeMs = serverTimeMs,
    paused = isPaused,
    durationMs = durationMs,
    loop = loop,
)
