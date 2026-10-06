package com.dreamdisplayx.platform.client

import org.tomlj.Toml
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigAnimatedLoadingScreenTest {
    @Test
    fun `new configuration disables and persists animated loading by default`() = withConfigDirectory { directory ->
        val config = Config(directory)

        assertFalse(config.animatedLoadingScreen)
        assertPersistedValue(directory, false)
        assertFalse(Config(directory).animatedLoadingScreen)
    }

    @Test
    fun `old TOML without the new key leaves animated loading disabled`() = withConfigDirectory { directory ->
        File(directory, "config.toml").writeText("displays-enabled = false\n")

        val config = Config(directory)

        assertFalse(config.animatedLoadingScreen)
        assertFalse(config.displaysEnabled)
    }

    @Test
    fun `reloading a missing key clears a previous opt in`() = withConfigDirectory { directory ->
        val config = Config(directory)
        config.animatedLoadingScreen = true
        config.save()
        File(directory, "config.toml").writeText("displays-enabled = true\n")

        config.reload()

        assertFalse(config.animatedLoadingScreen)
    }

    @Test
    fun `legacy configuration without the new key migrates with animated loading disabled`() = withConfigDirectory { directory ->
        File(directory, "config.yml").writeText("displays-enabled: false\n")

        val config = Config(directory)

        assertFalse(config.animatedLoadingScreen)
        assertFalse(config.displaysEnabled)
        assertPersistedValue(directory, false)
    }

    @Test
    fun `enabled animated loading survives save reload and a new config instance`() = withConfigDirectory { directory ->
        val config = Config(directory)
        config.animatedLoadingScreen = true

        config.save()
        config.animatedLoadingScreen = false
        config.reload()

        assertTrue(config.animatedLoadingScreen)
        assertPersistedValue(directory, true)
        assertTrue(Config(directory).animatedLoadingScreen)
    }

    @Test
    fun `turning animated loading off persists across reloads`() = withConfigDirectory { directory ->
        val config = Config(directory)
        config.animatedLoadingScreen = true
        config.save()

        config.animatedLoadingScreen = false
        config.save()
        config.animatedLoadingScreen = true
        config.reload()

        assertFalse(config.animatedLoadingScreen)
        assertPersistedValue(directory, false)
        assertFalse(Config(directory).animatedLoadingScreen)
    }

    @Test
    fun `declarative UI entry is a boolean and applies both values with immediate persistence`() = withConfigDirectory { directory ->
        val config = Config(directory)
        val entry = config.configEntries().single { it.key == "animated-loading-screen" }

        assertEquals(ConfigEntryType.BOOLEAN, entry.type)
        assertEquals("Animated loading screen", entry.label)
        assertTrue(entry.comment.contains("logo intro"))
        assertTrue(entry.comment.contains("rainbow waving title"))
        assertTrue(entry.comment.contains("spinner"))
        assertEquals(false, entry.get())

        @Suppress("UNCHECKED_CAST")
        val toggle = entry as ConfigEntry<Boolean>
        toggle.apply(true)

        assertTrue(config.animatedLoadingScreen)
        assertTrue(toggle.get())
        assertPersistedValue(directory, true)
        assertTrue(Config(directory).animatedLoadingScreen)

        toggle.apply(false)

        assertFalse(config.animatedLoadingScreen)
        assertFalse(toggle.get())
        assertPersistedValue(directory, false)
        assertFalse(Config(directory).animatedLoadingScreen)
    }

    @Test
    fun `non boolean TOML values safely disable a previous opt in`() = withConfigDirectory { directory ->
        val config = Config(directory)
        val invalidValues = listOf("\"true\"", "1", "1.0", "[true]", "{ enabled = true }", "1979-05-27")

        for (value in invalidValues) {
            config.animatedLoadingScreen = true
            File(directory, "config.toml").writeText("animated-loading-screen = $value\n")

            config.reload()

            assertFalse(config.animatedLoadingScreen, "Invalid value must not opt in: $value")
            assertFalse(Config(directory).animatedLoadingScreen, "Invalid value must be safe on first load: $value")
        }
    }

    @Test
    fun `malformed TOML safely disables a previous opt in`() = withConfigDirectory { directory ->
        val config = Config(directory)
        val malformedValues = listOf("yes", "TRUE", "[", "true\nanimated-loading-screen = true")

        for (value in malformedValues) {
            config.animatedLoadingScreen = true
            File(directory, "config.toml").writeText("animated-loading-screen = $value\n")

            config.reload()

            assertFalse(config.animatedLoadingScreen, "Malformed TOML must not preserve an opt in: $value")
            assertPersistedValue(directory, false)
            assertFalse(Config(directory).animatedLoadingScreen)
        }
    }

    @Test
    fun `reloading a deleted file recreates it with animated loading disabled`() = withConfigDirectory { directory ->
        val config = Config(directory)
        config.animatedLoadingScreen = true
        config.save()
        Files.delete(File(directory, "config.toml").toPath())

        config.reload()

        assertFalse(config.animatedLoadingScreen)
        assertPersistedValue(directory, false)
    }

    private fun assertPersistedValue(directory: File, expected: Boolean) {
        val parsed = Toml.parse(File(directory, "config.toml").toPath())
        assertFalse(parsed.hasErrors(), "Saved configuration must be valid TOML")
        assertEquals(expected, parsed.getBoolean("animated-loading-screen"))
    }

    private fun withConfigDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("ddx-animated-loading-config-").toFile()
        val runtimeProperties = listOf(
            "dreamdisplayx.audio.globalMultiplier",
            "dreamdisplayx.stream.preferFps60",
            "dreamdisplayx.flashback.renderHud",
            "dreamdisplayx.flashback.renderDisplays",
        ).associateWith { System.getProperty(it) }
        try {
            test(directory)
        } finally {
            runtimeProperties.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
            directory.deleteRecursively()
        }
    }
}
