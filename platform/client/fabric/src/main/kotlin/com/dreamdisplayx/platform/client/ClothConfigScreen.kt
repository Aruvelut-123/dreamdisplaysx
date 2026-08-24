package com.dreamdisplayx.platform.client

import com.dreamdisplayx.api.media.audio.model.AcousticQuality
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
 */
object ClothConfigScreenProvider {

    fun create(parent: Screen?): Screen {
        val config = ClientStateManager.config
        val entryBuilder = ConfigEntryBuilder.create()
        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.literal("Dream DisplaysX Config"))
            .setSavingRunnable { config.save() }

        val general: ConfigCategory = builder.getOrCreateCategory(Component.literal("General"))

        // Displays enabled
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.literal("Displays enabled"),
                config.displaysEnabled,
            )
                .setDefaultValue(true)
                .setTooltip(Component.literal("Whether displays are enabled at all."))
                .setSaveConsumer { v: Boolean -> config.displaysEnabled = v }
                .build(),
        )

        // Prefer 60fps
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.literal("Prefer 60fps"),
                config.preferFps60,
            )
                .setDefaultValue(true)
                .setTooltip(Component.literal("Prefer 60 fps streams when the video supports them."))
                .setSaveConsumer { v: Boolean -> config.preferFps60 = v }
                .build(),
        )

        // Hardware acceleration
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.literal("Hardware acceleration"),
                config.useHwAccel,
            )
                .setDefaultValue(true)
                .setTooltip(Component.literal("Whether to use hardware-accelerated video decoding."))
                .setSaveConsumer { v: Boolean -> config.useHwAccel = v }
                .build(),
        )

        // Mute on alt-tab
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.literal("Mute on alt-tab"),
                config.muteOnAltTab,
            )
                .setDefaultValue(false)
                .setTooltip(Component.literal("Mute all displays while the game window is not focused."))
                .setSaveConsumer { v: Boolean -> config.muteOnAltTab = v }
                .build(),
        )

        // Binaural audio
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Component.literal("Binaural audio"),
                config.audioBinauralOutput,
            )
                .setDefaultValue(true)
                .setTooltip(
                    Component.literal(
                        "Render binaural audio for headphones (ON) or a plain stereo pan for speakers (OFF).",
                    ),
                )
                .setSaveConsumer { v: Boolean -> config.audioBinauralOutput = v }
                .build(),
        )

        // Default render distance (int field)
        general.addEntry(
            entryBuilder.startIntField(
                Component.literal("Default render distance"),
                config.defaultDistance,
            )
                .setDefaultValue(96)
                .setTooltip(Component.literal("Default render distance for new displays, in blocks."))
                .setSaveConsumer { v: Int -> config.defaultDistance = v }
                .build(),
        )

        // Default volume (double field — no slider in 15.x API)
        general.addEntry(
            entryBuilder.startDoubleField(
                Component.literal("Default volume"),
                config.defaultDisplayVolume,
            )
                .setDefaultValue(0.5)
                .setTooltip(Component.literal("Default volume for new displays, in range 0.0 to 1.0."))
                .setSaveConsumer { v: Double -> config.defaultDisplayVolume = v }
                .build(),
        )

        // Audio acoustics (enum selector)
        general.addEntry(
            entryBuilder.startEnumSelector(
                Component.literal("Audio acoustics"),
                AcousticQuality::class.java,
                config.audioAcoustics,
            )
                .setDefaultValue(AcousticQuality.ADVANCED)
                .setTooltip(
                    Component.literal(
                        "3D acoustics rendering tier applied to every display's audio.",
                    ),
                )
                .setSaveConsumer { v: AcousticQuality -> config.audioAcoustics = v }
                .build(),
        )

        return builder.build()
    }
}