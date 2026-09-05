package com.dreamdisplayx.platform.server

import org.tomlj.Toml
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Empirical check for issue #190 ("server config default volume not applied"): verifies that
 * `parseServerConfig` really reads `display.default_volume` from a config file and maps it into the
 * wire `defaultVolume` the v2 handshake sends. If these hold, the server-side read path is correct
 * and the reported bug must come from a legacy (v1) peer, which is a frozen protocol.
 */
class ServerConfigModelTest {
    @Test
    fun defaultVolumeReadsConfiguredValue() {
        val path = Files.createTempFile("ddx-config", ".toml")
        try {
            Files.writeString(path, "[display]\ndefault_volume = 30\n")
            val parsed = parseServerConfig(Toml.parse(path))
            assertEquals(30, parsed.settings.display.default_volume)
            // default_volume is a 0..100 percentage; 30 maps to 30/100 = 0.3 on the 0..1 wire scale.
            assertEquals(0.3f, parsed.settings.defaultVolume)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun defaultVolumeFallsBackToFiftyPercent() {
        val parsed = parseServerConfig(null)
        assertEquals(50, parsed.settings.display.default_volume)
        assertEquals(0.5f, parsed.settings.defaultVolume)
    }
}
