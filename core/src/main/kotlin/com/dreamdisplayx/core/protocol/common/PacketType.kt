package com.dreamdisplayx.core.protocol.common

import com.dreamdisplayx.api.protocol.model.PacketDirection
import com.dreamdisplayx.core.protocol.common.packets.ClearCache
import com.dreamdisplayx.core.protocol.common.packets.ClientHello
import com.dreamdisplayx.core.protocol.common.packets.DreamPacket
import com.dreamdisplayx.core.protocol.common.packets.DisplayDelete
import com.dreamdisplayx.core.protocol.common.packets.DisplayInfo
import com.dreamdisplayx.core.protocol.common.packets.DisplaySync
import com.dreamdisplayx.core.protocol.common.packets.FullscreenAck
import com.dreamdisplayx.core.protocol.common.packets.FullscreenState
import com.dreamdisplayx.core.protocol.common.packets.PipPin
import com.dreamdisplayx.core.protocol.common.packets.PlaybackCommand
import com.dreamdisplayx.core.protocol.common.packets.PlatformCredentials
import com.dreamdisplayx.core.protocol.common.packets.RadiusPreview
import com.dreamdisplayx.core.protocol.common.packets.RemotePlaybackToggle
import com.dreamdisplayx.core.protocol.common.packets.ReportDisplay
import com.dreamdisplayx.core.protocol.common.packets.ReportDuration
import com.dreamdisplayx.core.protocol.common.packets.RequestSync
import com.dreamdisplayx.core.protocol.common.packets.ScreenShareAck
import com.dreamdisplayx.core.protocol.common.packets.ScreenShareData
import com.dreamdisplayx.core.protocol.common.packets.ScreenShareStart
import com.dreamdisplayx.core.protocol.common.packets.ScreenShareStop
import com.dreamdisplayx.core.protocol.common.packets.ServerHello
import com.dreamdisplayx.core.protocol.common.packets.SetDisplaysEnabled
import com.dreamdisplayx.core.protocol.common.packets.SetLocked
import com.dreamdisplayx.core.protocol.common.packets.SetMode
import com.dreamdisplayx.core.protocol.common.packets.SetVideo
import com.dreamdisplayx.core.protocol.common.packets.WatchPartyControl
import com.dreamdisplayx.core.protocol.common.packets.WatchPartyStart
import com.dreamdisplayx.core.protocol.common.packets.WatchPartyState
import kotlin.reflect.KClass

/**
 * Append-only protocol-v2 packet type ids; wire-protocol stable, never reuse or renumber.
 */
enum class PacketType(
    val id: Int,
    val packetClass: KClass<out DreamPacket>,
    val direction: PacketDirection,
) {
    CLIENT_HELLO(1, ClientHello::class, PacketDirection.CLIENT_TO_SERVER),
    SERVER_HELLO(2, ServerHello::class, PacketDirection.SERVER_TO_CLIENT),
    DISPLAY_INFO(3, DisplayInfo::class, PacketDirection.SERVER_TO_CLIENT),
    DISPLAY_DELETE(4, DisplayDelete::class, PacketDirection.BIDIRECTIONAL),
    DISPLAY_SYNC(5, DisplaySync::class, PacketDirection.BIDIRECTIONAL),
    REQUEST_SYNC(6, RequestSync::class, PacketDirection.CLIENT_TO_SERVER),
    SET_VIDEO(7, SetVideo::class, PacketDirection.CLIENT_TO_SERVER),
    SET_LOCKED(8, SetLocked::class, PacketDirection.CLIENT_TO_SERVER),
    REPORT_DISPLAY(9, ReportDisplay::class, PacketDirection.CLIENT_TO_SERVER),
    SET_DISPLAYS_ENABLED(10, SetDisplaysEnabled::class, PacketDirection.BIDIRECTIONAL),
    CLEAR_CACHE(11, ClearCache::class, PacketDirection.SERVER_TO_CLIENT),
    PLAYBACK_COMMAND(12, PlaybackCommand::class, PacketDirection.CLIENT_TO_SERVER),
    SET_MODE(13, SetMode::class, PacketDirection.CLIENT_TO_SERVER),
    WATCH_PARTY_START(14, WatchPartyStart::class, PacketDirection.CLIENT_TO_SERVER),
    WATCH_PARTY_CONTROL(15, WatchPartyControl::class, PacketDirection.CLIENT_TO_SERVER),
    WATCH_PARTY_STATE(16, WatchPartyState::class, PacketDirection.SERVER_TO_CLIENT),
    FULLSCREEN_STATE(17, FullscreenState::class, PacketDirection.SERVER_TO_CLIENT),
    FULLSCREEN_ACK(18, FullscreenAck::class, PacketDirection.CLIENT_TO_SERVER),
    RADIUS_PREVIEW(19, RadiusPreview::class, PacketDirection.SERVER_TO_CLIENT),
    PIP_PIN(20, PipPin::class, PacketDirection.CLIENT_TO_SERVER),
    REPORT_DURATION(21, ReportDuration::class, PacketDirection.CLIENT_TO_SERVER),
    REMOTE_PLAYBACK_TOGGLE(22, RemotePlaybackToggle::class, PacketDirection.SERVER_TO_CLIENT),
    SCREEN_SHARE_START(23, ScreenShareStart::class, PacketDirection.CLIENT_TO_SERVER),
    SCREEN_SHARE_DATA(24, ScreenShareData::class, PacketDirection.CLIENT_TO_SERVER),
    SCREEN_SHARE_STOP(25, ScreenShareStop::class, PacketDirection.CLIENT_TO_SERVER),
    SCREEN_SHARE_ACK(26, ScreenShareAck::class, PacketDirection.SERVER_TO_CLIENT),
    PLATFORM_CREDENTIALS(27, PlatformCredentials::class, PacketDirection.SERVER_TO_CLIENT);

    companion object {
        private val byId = entries.associateBy { it.id }

        init {
            require(byId.size == entries.size) { "Duplicate protocol packet type ids." }
        }

        fun fromId(id: Int): PacketType? = byId[id]
    }
}
