package com.dreamdisplayx.platform.client

import com.dreamdisplayx.api.media.audio.model.AcousticQuality
import com.dreamdisplayx.media.player.process.HwAccelEnumerator
import com.dreamdisplayx.platform.client.config.ConfigScreenText
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.api.ConfigCategory
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Cloth Config screen provider — only loaded at runtime when Cloth Config is on the classpath.
 * If Cloth Config is absent, [ModMenuIntegration] detects this via reflection and skips the
 * config button entirely.
 *
 * All labels are localizable through `dreamdisplayx.config.*` translation keys.
 */
object ClothConfigScreenProvider {

    fun create(parent: Screen?): Screen {
        val config = ClientStateManager.config
        val entryBuilder = ConfigEntryBuilder.create()
        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.translatable(ConfigScreenText.Keys.TITLE))
            .setSavingRunnable { config.save() }

        val general: ConfigCategory = builder.getOrCreateCategory(
            Component.translatable(ConfigScreenText.Keys.CATEGORY_GENERAL),
        )

        // Displays enabled
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.translatable(ConfigScreenText.Keys.DISPLAYS_ENABLED),
                config.displaysEnabled,
            )
                .setDefaultValue(true)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.DISPLAYS_ENABLED_TOOLTIP))
                .setSaveConsumer { v: Boolean -> config.displaysEnabled = v }
                .build(),
        )

        // Prefer 60fps
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.translatable(ConfigScreenText.Keys.PREFER_FPS60),
                config.preferFps60,
            )
                .setDefaultValue(true)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.PREFER_FPS60_TOOLTIP))
                .setSaveConsumer { v: Boolean -> config.preferFps60 = v }
                .build(),
        )

        // Hardware acceleration (toggle)
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.translatable(ConfigScreenText.Keys.USE_HW_ACCEL),
                config.useHwAccel,
            )
                .setDefaultValue(true)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.USE_HW_ACCEL_TOOLTIP))
                .setSaveConsumer { v: Boolean -> config.useHwAccel = v }
                .build(),
        )

        // Video decoder selection (button-style selector with all available FFmpeg backends)
        val decoderSelections = buildList {
            add("auto")
            add("software")
            addAll(HwAccelEnumerator.availableBackends())
        }.toTypedArray()
        general.addEntry(
            entryBuilder.startSelector(
                Component.translatable(ConfigScreenText.Keys.DECODER),
                decoderSelections,
                config.hwaccelDecoder.ifEmpty { "auto" },
            )
                .setDefaultValue("auto")
                .setNameProvider { v -> ConfigScreenText.decoderLabel(v) }
                .setTooltip(Component.translatable(ConfigScreenText.Keys.DECODER_TOOLTIP))
                .setSaveConsumer { v: String -> config.hwaccelDecoder = v }
                .build(),
        )

        // Mute on alt-tab
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.translatable(ConfigScreenText.Keys.MUTE_ON_ALT_TAB),
                config.muteOnAltTab,
            )
                .setDefaultValue(false)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.MUTE_ON_ALT_TAB_TOOLTIP))
                .setSaveConsumer { v: Boolean -> config.muteOnAltTab = v }
                .build(),
        )

        // Binaural audio
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.translatable(ConfigScreenText.Keys.AUDIO_BINAURAL),
                config.audioBinauralOutput,
            )
                .setDefaultValue(true)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.AUDIO_BINAURAL_TOOLTIP))
                .setSaveConsumer { v: Boolean -> config.audioBinauralOutput = v }
                .build(),
        )

        // Default render distance (int field)
        general.addEntry(
            entryBuilder.startIntField(
                Component.translatable(ConfigScreenText.Keys.DEFAULT_RENDER_DISTANCE),
                config.defaultDistance,
            )
                .setDefaultValue(96)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.DEFAULT_RENDER_DISTANCE_TOOLTIP))
                .setSaveConsumer { v: Int -> config.defaultDistance = v }
                .build(),
        )

        // Default volume (double field)
        general.addEntry(
            entryBuilder.startDoubleField(
                Component.translatable(ConfigScreenText.Keys.DEFAULT_VOLUME),
                config.defaultDisplayVolume,
            )
                .setDefaultValue(0.5)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.DEFAULT_VOLUME_TOOLTIP))
                .setSaveConsumer { v: Double -> config.defaultDisplayVolume = v }
                .build(),
        )

        // Audio acoustics (enum selector)
        general.addEntry(
            entryBuilder.startEnumSelector(
                Component.translatable(ConfigScreenText.Keys.AUDIO_ACOUSTICS),
                AcousticQuality::class.java,
                config.audioAcoustics,
            )
                .setDefaultValue(AcousticQuality.ADVANCED)
                .setTooltip(Component.translatable(ConfigScreenText.Keys.AUDIO_ACOUSTICS_TOOLTIP))
                .setSaveConsumer { v: AcousticQuality -> config.audioAcoustics = v }
                .build(),
        )

        return builder.build()
    }
}