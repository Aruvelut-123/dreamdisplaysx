package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.display.model.DisplayInfo
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.model.settings.DisplaySettings
import com.dreamdisplays.core.services.DisplayExecutor
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.ReportDisplay
import com.dreamdisplays.core.protocol.common.packets.SetLocked
import com.dreamdisplays.core.services.DisplayStorage
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.storage.ClientSettingsStore

/**
 * Client-side [DisplayExecutor]: resolves each command to the live [DisplayScreen] in the
 * [DisplayRegistry] and applies it, returning the updated [DisplayInfo] snapshot (or `null` when the
 * display is not present locally).
 */
class MinecraftDisplayCommands : DisplayExecutor {
    override fun updateSettings(id: DisplayId, settings: DisplaySettings): DisplayInfo? {
        val screen = DisplayRegistry.screens[id.uuid] ?: return null
        screen.volume = settings.volume
        screen.quality = settings.quality
        screen.brightness = settings.brightness
        screen.mute(settings.muted)
        screen.setPaused(settings.paused)

        val override = settings.urlOverride
        if (!override.isNullOrBlank()) {
            screen.playSuggestedVideo(override, settings.audioTrackName ?: screen.lang ?: "")
        }

        return screen.toDisplay()
    }

    override fun setUrl(id: DisplayId, url: String?, lang: String?): DisplayInfo? {
        val screen = DisplayRegistry.screens[id.uuid] ?: return null
        if (url.isNullOrBlank()) return screen.toDisplay()
        screen.playSuggestedVideo(url, lang ?: screen.lang ?: "")
        return screen.toDisplay()
    }

    @Deprecated("Scheduled for removal in 2.0.0")
    override fun setAccess(id: DisplayId, access: DisplayAccess): DisplayInfo? {
        val screen = DisplayRegistry.screens[id.uuid] ?: return null
        screen.access = access
        screen.isLocked = access != DisplayAccess.EVERYONE
        // TODO: remove this in 2.0.0
        // locked is filled in as well so a pre-1.10 server still gets the closest thing it understands
        Initializer.sendPacket(SetLocked(id.uuid, access != DisplayAccess.EVERYONE, access.wire))
        return screen.toDisplay()
    }

    override fun delete(id: DisplayId): Boolean {
        val screen = DisplayRegistry.screens[id.uuid] ?: return false
        DisplayStorage.removeDisplay(id.uuid)
        DisplayRegistry.unregisterScreen(screen)
        Initializer.sendPacket(DisplayDelete(id.uuid))
        return true
    }

    override fun report(id: DisplayId): DisplayInfo? {
        val screen = DisplayRegistry.screens[id.uuid] ?: return null
        Initializer.sendPacket(ReportDisplay(id.uuid))
        return screen.toDisplay()
    }

    override fun play(displayId: DisplayId): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.setPaused(false)
        return screen.toDisplay()
    }

    override fun pause(displayId: DisplayId): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.setPaused(true)
        return screen.toDisplay()
    }

    override fun stop(displayId: DisplayId): Boolean {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return false
        DisplayRegistry.unregisterScreen(screen)
        return true
    }

    override fun seek(displayId: DisplayId, positionMs: Long): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.seekToMillis(positionMs)
        return screen.toDisplay()
    }

    override fun seekRelative(displayId: DisplayId, deltaMs: Long): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.seekVideoRelative(deltaMs / 1000.0)
        return screen.toDisplay()
    }

    override fun setVolume(displayId: DisplayId, volume: Float): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.volume = volume
        return screen.toDisplay()
    }

    override fun setQuality(displayId: DisplayId, quality: VideoQuality): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.setQualityByViewer(quality)
        return screen.toDisplay()
    }

    override fun setAudioTrack(displayId: DisplayId, trackUrl: String): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.audioTrack = trackUrl
        val lang = screen.audioTrackList.firstOrNull { it.url == trackUrl }?.audioIdentity
        ClientSettingsStore.setAudioTrackLang(displayId.uuid, lang)
        return screen.toDisplay()
    }

    override fun setSubtitleTrack(displayId: DisplayId, lang: String?): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.subtitleTrack = lang
        ClientSettingsStore.setSubtitleTrackLang(displayId.uuid, lang)
        return screen.toDisplay()
    }

    override fun setBrightness(displayId: DisplayId, brightness: Float): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.brightness = brightness
        return screen.toDisplay()
    }

    override fun mute(displayId: DisplayId, muted: Boolean): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.mute(muted)
        return screen.toDisplay()
    }

    override fun restart(displayId: DisplayId): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        if (!screen.canSeekHere) return screen.toDisplay()
        val url = screen.videoUrl ?: return screen.toDisplay()
        screen.loadVideo(url, screen.lang ?: "")
        return screen.toDisplay()
    }

    override fun setMode(displayId: DisplayId, mode: PlaybackMode): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.requestMode(mode)
        return screen.toDisplay()
    }

    override fun retry(displayId: DisplayId): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.retryVideo()
        return screen.toDisplay()
    }

    override fun startWatchParty(displayId: DisplayId, url: String?): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        if (!screen.canStartWatchPartyHere) return null
        screen.startWatchParty(url ?: screen.videoUrl ?: "")
        return screen.toDisplay()
    }

    override fun setWatchPartyReady(displayId: DisplayId, ready: Boolean): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.setWatchPartyReady(ready)
        return screen.toDisplay()
    }

    override fun beginWatchParty(displayId: DisplayId): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.beginWatchParty()
        return screen.toDisplay()
    }

    override fun endWatchParty(displayId: DisplayId): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.endWatchParty()
        return screen.toDisplay()
    }

    override fun restartWatchParty(displayId: DisplayId): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.restartWatchParty()
        return screen.toDisplay()
    }

    override fun closeWatchParty(displayId: DisplayId): DisplayInfo? {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        screen.closeWatchParty()
        return screen.toDisplay()
    }
}
