package com.dreamdisplayx.platform.client.config

import com.dreamdisplayx.platform.client.Config
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec

/**
 * NeoForge-side configuration registered through `ModConfigSpec`. Registering a standard NeoForge
 * client config lets Configured (MrCrayfish) discover and edit these values in-game.
 *
 * The values are bridged to the shared client [Config] (which persists `config.toml`): every time the
 * NeoForge config is loaded or reloaded — including when Configured saves edits — [applyTo] copies the
 * `ModConfigSpec` values into the common [Config] and persists them. This keeps one canonical source
 * (the common `Config`'s TOML) while letting Configured present and edit the same settings in-game.
 */
object NeoForgeConfig {
    lateinit var SPEC: ModConfigSpec

    lateinit var muteOnAltTab: ModConfigSpec.BooleanValue
    lateinit var defaultDistance: ModConfigSpec.IntValue
    lateinit var defaultDisplayVolume: ModConfigSpec.DoubleValue
    lateinit var displaysEnabled: ModConfigSpec.BooleanValue
    lateinit var ytdlpCookieSource: ModConfigSpec.ConfigValue<String>
    lateinit var ytdlpProxy: ModConfigSpec.ConfigValue<String>
    lateinit var useHwAccel: ModConfigSpec.BooleanValue
    lateinit var preferFps60: ModConfigSpec.BooleanValue
    lateinit var audioAcoustics: ModConfigSpec.ConfigValue<String>
    lateinit var audioBinauralOutput: ModConfigSpec.BooleanValue

    fun register() {
        val builder = ModConfigSpec.Builder()
        muteOnAltTab = builder.comment("Mute all displays while the game window is unfocused.")
            .define("mute_on_alt_tab", false)
        defaultDistance = builder.comment("Default render distance for new displays, in blocks.")
            .defineInRange("default_render_distance", 96, 32, 192)
        defaultDisplayVolume = builder.comment("Default volume for new displays (0.0-1.0).")
            .defineInRange("default_display_volume", 0.5, 0.0, 1.0)
        displaysEnabled = builder.comment("Whether displays are enabled at all.")
            .define("displays_enabled", true)
        ytdlpCookieSource = builder.comment("Browser to import yt-dlp cookies from, or NONE.")
            .define("ytdlp_cookies_from_browser", "NONE")
        ytdlpProxy = builder.comment("Proxy URL passed to yt-dlp, or empty for a direct connection.")
            .define("ytdlp_proxy", "")
        useHwAccel = builder.comment("Use hardware-accelerated video decoding.")
            .define("use_hw_accel", true)
        preferFps60 = builder.comment("Prefer 60 fps streams when the video supports them.")
            .define("prefer_fps60", true)
        audioAcoustics = builder.comment("3D acoustics tier: off / basic / advanced / ultra.")
            .define("audio_acoustics", "advanced")
        audioBinauralOutput = builder.comment("Render binaural audio for headphones (true) or stereo pan (false).")
            .define("audio_binaural_output", true)
        SPEC = builder.build()

        // Register a standard NeoForge client config so Configured can discover and edit these values.
        ModLoadingContext.get().getActiveContainer().registerConfig(
            ModConfig.Type.CLIENT, SPEC, "dreamdisplayx-client.toml"
        )
    }

    /**
     * Copies the current `ModConfigSpec` values into the common client [Config] and persists it.
     * Called when the config loads or reloads so Configured edits take effect on the shared settings.
     */
    fun applyTo(config: Config) {
        if (!::muteOnAltTab.isInitialized) return
        config.muteOnAltTab = muteOnAltTab.get()
        config.defaultDistance = defaultDistance.get()
        config.defaultDisplayVolume = defaultDisplayVolume.get()
        config.displaysEnabled = displaysEnabled.get()
        ytdlpCookieSource.get().let { config.ytdlpCookieSource = com.dreamdisplayx.media.source.youtube.cookie.CookieSource.fromConfig(it) ?: config.ytdlpCookieSource }
        config.ytdlpProxy = ytdlpProxy.get()
        config.useHwAccel = useHwAccel.get()
        config.preferFps60 = preferFps60.get()
        audioAcoustics.get().let { token ->
            com.dreamdisplayx.api.media.audio.model.AcousticQuality.entries
                .firstOrNull { it.name.equals(token, ignoreCase = true) }
                ?.let { config.audioAcoustics = it }
        }
        config.audioBinauralOutput = audioBinauralOutput.get()
        config.save()
    }

    /**
     * Registers a listener so that every time the NeoForge config loads or reloads — including after
     * Configured saves an edit — the values are bridged back into the shared client [Config].
     */
    fun listen(modEventBus: net.neoforged.bus.api.IEventBus) {
        modEventBus.addListener(::onConfigLoaded)
        modEventBus.addListener(::onConfigReloaded)
    }

    private fun onConfigLoaded(event: net.neoforged.fml.event.config.ModConfigEvent.Loading) {
        runCatching { applyTo(ClientStateManager.config) }
    }

    private fun onConfigReloaded(event: net.neoforged.fml.event.config.ModConfigEvent.Reloading) {
        runCatching { applyTo(ClientStateManager.config) }
    }
}
