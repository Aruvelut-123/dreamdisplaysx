package com.dreamdisplays.platform.client.ui.kit

import com.dreamdisplays.platform.client.ui.ClientMessage
import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.api.media.source.MediaPlatform

/**
 * The single source of truth for the little coloured platform tag ("Twitch", "Vimeo", "Kick", "Link") drawn on search
 * results and thumbnails.
 */
object PlatformBadge {

    /**
     * A platform tag: its translation [labelKey], the [bgColor] plate behind it, and the [textColor]
     * that reads on that plate (bright brand colors get dark text, dark ones get white).
     */
    data class Badge(val labelKey: String, val bgColor: Int, val textColor: Int)

    private const val DARK_TEXT = 0xFF11151A.toInt()
    private val TWITCH = Badge(ClientMessage.UI_TWITCH.key, UiTheme.ACCENT_TWITCH_TAG, UiTheme.TEXT_PRIMARY)
    private val VIMEO = Badge(ClientMessage.UI_VIMEO.key, UiTheme.ACCENT_VIMEO_TAG, DARK_TEXT)
    private val KICK = Badge(ClientMessage.UI_KICK.key, UiTheme.ACCENT_KICK_TAG, DARK_TEXT)
    private val BILIBILI = Badge(ClientMessage.UI_BILIBILI.key, UiTheme.ACCENT_BILIBILI_TAG, DARK_TEXT)
    private val CUSTOM = Badge(ClientMessage.UI_CUSTOM.key, UiTheme.ACCENT_CUSTOM_TAG, UiTheme.TEXT_PRIMARY)

    /** The badge for [platform], or null when it needs none (a plain YouTube / long-tail result). */
    fun forPlatform(platform: MediaPlatform): Badge? = when (platform) {
        MediaPlatform.TWITCH -> TWITCH
        MediaPlatform.VIMEO -> VIMEO
        MediaPlatform.KICK -> KICK
        MediaPlatform.BILIBILI -> BILIBILI
        MediaPlatform.DIRECT -> CUSTOM
        MediaPlatform.YOUTUBE, MediaPlatform.OTHER -> null
    }

    /** The badge for a search-result card, honoring the legacy `isTwitch` / `isCustom` flags too. */
    fun forResult(info: MediaSearchResult): Badge? = when {
        info.isCustom -> CUSTOM
        info.isTwitch -> TWITCH
        else -> forPlatform(info.platform)
    }
}
