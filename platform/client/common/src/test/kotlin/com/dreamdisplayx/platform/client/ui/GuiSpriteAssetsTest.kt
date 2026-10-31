package com.dreamdisplayx.platform.client.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the per-platform `textures/gui/sprites` assets against missing or empty files.
 *
 * A zero-length `.png` is committed more easily than it looks: the Forge module shipped empty
 * `panel.png`/`dropdown.png` copies, and on pre-1.20.2 versions [WidgetSprites] reads the sprite to
 * learn its size, so `NativeImage.read` threw
 * "Image not of any type, or corrupt" from the render thread as soon as the display menu opened.
 */
class GuiSpriteAssetsTest {
    @Test
    fun `every platform ships real gui sprite pngs`() {
        val root = findRepoRoot()
        val sprites = listOf(
            "fabric/assets/dreamdisplayx/textures/gui/sprites/widgets/panel.png",
            "fabric/assets/dreamdisplayx/textures/gui/sprites/widgets/dropdown.png",
            "neoforge/assets/dreamdisplayx/textures/gui/sprites/widgets/panel.png",
            "neoforge/assets/dreamdisplayx/textures/gui/sprites/widgets/dropdown.png",
            "forge/assets/dreamdisplayx/textures/gui/sprites/widgets/panel.png",
            "forge/assets/dreamdisplayx/textures/gui/sprites/widgets/dropdown.png",
        )
        sprites.forEach { relative ->
            val file = File(root, "platform/client/$relative")
            assertTrue(file.isFile, "missing sprite: platform/client/$relative")
            val bytes = file.readBytes()
            assertTrue(bytes.size > 8, "empty or truncated sprite: platform/client/$relative")
            assertTrue(isPng(bytes), "not a PNG: platform/client/$relative")
        }
    }

    private fun isPng(bytes: ByteArray): Boolean {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        return bytes.size >= signature.size && signature.indices.all { bytes[it] == signature[it] }
    }

    /** Walks up from the test working directory until the repository root (the one with `settings.gradle.kts`). */
    private fun findRepoRoot(): File {
        var current: File? = File("").absoluteFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile && File(current, "platform").isDirectory) {
                return current
            }
            current = current.parentFile
        }
        error("could not locate the repository root from ${File("").absolutePath}")
    }
}
