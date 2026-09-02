package com.dreamdisplayx.platform.client.config

import net.minecraft.network.chat.Component

/**
 * Shared localized display text for the configuration screens (Cloth Config).
 *
 * Every label uses `dreamdisplayx.config.*` translation keys so the same keys work on both
 * platforms; the [decoderLabel] function localizes each FFmpeg backend name when a known key
 * exists (`dreamdisplayx.config.decoder.<name>`) and falls back to the raw name otherwise.
 */
object ConfigScreenText {

    /** Known decoder values that have a translation key in every language file. */
    private val KNOWN_DECODER_VALUES = setOf(
        "auto", "software", "cuda", "qsv", "amf", "d3d11va", "vaapi",
        "vulkan", "videotoolbox", "dxva2", "mediacodec", "nvdec", "vdpau",
        "drm", "d3d12va", "v4l2m2m", "opencl", "v4l2request", "ohcodec", "v3d",
    )

    /** Field labels / tooltips shared by the config screens. */
    object Keys {
        const val TITLE = "dreamdisplayx.config.title"
        const val CATEGORY_GENERAL = "dreamdisplayx.config.category.general"
        const val DISPLAYS_ENABLED = "dreamdisplayx.config.displays_enabled"
        const val DISPLAYS_ENABLED_TOOLTIP = "dreamdisplayx.config.displays_enabled.tooltip"
        const val FLASHBACK_RENDER_HUD = "dreamdisplayx.config.flashback_render_hud"
        const val FLASHBACK_RENDER_HUD_TOOLTIP = "dreamdisplayx.config.flashback_render_hud.tooltip"
        const val FLASHBACK_RENDER_DISPLAYS = "dreamdisplayx.config.flashback_render_displays"
        const val FLASHBACK_RENDER_DISPLAYS_TOOLTIP = "dreamdisplayx.config.flashback_render_displays.tooltip"
        const val PREFER_FPS60 = "dreamdisplayx.config.prefer_fps60"
        const val PREFER_FPS60_TOOLTIP = "dreamdisplayx.config.prefer_fps60.tooltip"
        const val USE_HW_ACCEL = "dreamdisplayx.config.use_hw_accel"
        const val USE_HW_ACCEL_TOOLTIP = "dreamdisplayx.config.use_hw_accel.tooltip"
        const val DECODER = "dreamdisplayx.config.decoder"
        const val DECODER_TOOLTIP = "dreamdisplayx.config.decoder.tooltip"
        const val RESET = "dreamdisplayx.config.reset"
        const val MUTE_ON_ALT_TAB = "dreamdisplayx.config.mute_on_alt_tab"
        const val MUTE_ON_ALT_TAB_TOOLTIP = "dreamdisplayx.config.mute_on_alt_tab.tooltip"
        const val AUDIO_BINAURAL = "dreamdisplayx.config.audio_binaural"
        const val AUDIO_BINAURAL_TOOLTIP = "dreamdisplayx.config.audio_binaural.tooltip"
        const val DEFAULT_RENDER_DISTANCE = "dreamdisplayx.config.default_render_distance"
        const val DEFAULT_RENDER_DISTANCE_TOOLTIP = "dreamdisplayx.config.default_render_distance.tooltip"
        const val DEFAULT_VOLUME = "dreamdisplayx.config.default_volume"
        const val DEFAULT_VOLUME_TOOLTIP = "dreamdisplayx.config.default_volume.tooltip"
        const val AUDIO_ACOUSTICS = "dreamdisplayx.config.audio_acoustics"
        const val AUDIO_ACOUSTICS_TOOLTIP = "dreamdisplayx.config.audio_acoustics.tooltip"
        const val CDN_MIRROR = "dreamdisplayx.config.cdn_mirror"
        const val CDN_MIRROR_TOOLTIP = "dreamdisplayx.config.cdn_mirror.tooltip"
    }

    /** Localized component for the decoder dropdown label. Falls back to the raw [value] when the key is unknown. */
    fun decoderLabel(value: String): Component =
        if (value in KNOWN_DECODER_VALUES) Component.translatable("dreamdisplayx.config.decoder.$value")
        else Component.literal(value)

    /** Localized component for the Bilibili CDN mirror dropdown label. */
    fun cdnLabel(value: String): Component {
        val label = com.dreamdisplayx.media.player.cdn.BilibiliCdnMirror.CDN_LABELS[value]
            ?: return Component.literal(value)
        return Component.translatable("dreamdisplayx.config.cdn.$label")
    }
}