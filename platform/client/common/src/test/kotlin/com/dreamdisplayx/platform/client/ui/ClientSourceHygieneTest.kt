package com.dreamdisplayx.platform.client.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards two regressions that both looked like "the command does nothing" to a player.
 *
 * 1. `§` was committed to `Client.kt` as the look-alike Han character `\u6402` (U+6402), so every chat
 *    colour code silently rendered as a literal `\u6402`. `Component.literal` does not translate them,
 *    but nothing detected the wrong codepoint either — it compiled and shipped.
 * 2. `/dlogin` opened the login screen synchronously from inside the command handler, which closes
 *    any screen it just opened, so the command appeared to be a no-op on every version below 26.2
 *    (the deferred path was gated to `>=26.2`).
 *
 * These are source-level assertions on purpose: both bugs are invisible to a compiler and to any
 * runtime test that never opens the real GUI.
 */
class ClientSourceHygieneTest {
    @Test
    fun `client sources use the real section sign, not its look-alikes`() {
        val offenders = mutableListOf<String>()
        for (dir in CLIENT_SOURCE_ROOTS) {
            val root = File(repoRoot(), dir)
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val text = file.readText()
                    LOOK_ALIKE_SECTION_SIGNS.forEach { (wrong, why) ->
                        if (text.contains(wrong)) offenders += "${file.relativeTo(repoRoot())}: $why"
                    }
                }
        }
        assertTrue(offenders.isEmpty(), "Chat colour codes must use '§' (U+00A7):\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `the login screen is opened on a later tick, never inline in the command handler`() {
        for (file in listOf(
            "platform/client/fabric/src/main/kotlin/com/dreamdisplayx/platform/client/Client.kt",
            "platform/client/neoforge/src/main/kotlin/com/dreamdisplayx/platform/client/Client.kt",
        )) {
            val path = File(repoRoot(), file)
            if (!path.isFile) continue
            val text = path.readText()
            if (!text.contains("PlatformLoginScreen()")) continue
            val openAt = text.indexOf("PlatformLoginScreen()")
            val deferAt = text.lastIndexOf("execute {", openAt)
            assertTrue(
                deferAt > 0 && openAt - deferAt < 200,
                "$file must defer opening PlatformLoginScreen with Minecraft.execute { } — " +
                    "opening it inline is closed again by the command dispatch itself.",
            )
            assertTrue(
                !text.contains(".setScreen(PlatformLoginScreen())") &&
                    !text.contains(".setScreenAndShow(PlatformLoginScreen())"),
                "$file still opens PlatformLoginScreen synchronously.",
            )
        }
    }

    /** Walks up from the working directory until the Gradle + source layout root is found. */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "platform").isDirectory) return dir
            dir = dir.parentFile
        }
        error("Could not locate the repository root from ${File("").absolutePath}.")
    }

    private companion object {
        /** Client modules that ship player-visible strings. */
        val CLIENT_SOURCE_ROOTS = listOf(
            "platform/client/common/src/main/kotlin",
            "platform/client/fabric/src/main/kotlin",
            "platform/client/neoforge/src/main/kotlin",
            "platform/client/forge/src/main/kotlin",
        )

        /** Characters that were mistakenly committed in place of U+00A7 SECTION SIGN. */
        val LOOK_ALIKE_SECTION_SIGNS = mapOf(
            "\u6402" to "U+6402 (a Han look-alike) was typed where U+00A7 was meant",
        )
    }
}
