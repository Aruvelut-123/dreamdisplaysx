package com.dreamdisplayx.platform.client.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards two more regressions that both looked like "the GUI is broken but input still works".
 *
 * 1. Every scissor in the client UI used the raw `enableScissor`, but `UiScreenBase.renderScaled`
 *    draws the whole screen through a `PoseStack` scale whenever the window is smaller than
 *    `MIN_CONTENT_W` x `MIN_CONTENT_H` (640x410) — mobile GUI scales almost always are. Minecraft's
 *    `GuiGraphics.enableScissor` only multiplies by the window GUI scale and ignores that pose
 *    matrix, so the clip rect landed outside the real window and hid the whole card strip while the
 *    hit boxes (virtual coordinates) kept working: invisible but clickable, complete with the click
 *    sound. `enableScissorPoseAware` maps the rect through the pose matrix to avoid this.
 * 2. `/display login` and `/display logout` were guarded with `.requires { ... }`. Brigadier drops a
 *    node whose requirement fails, so a non-OP player got "unknown or incomplete command" instead of
 *    the missing-permission message — the command looked like it did not exist at all.
 */
class UiScissorAndCommandGateTest {
    @Test
    fun `client widgets clip through the pose aware scissor`() {
        val offenders = mutableListOf<String>()
        for (root in CLIP_SOURCE_ROOTS) {
            val dir = File(repoRoot(), root)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val lines = file.readLines()
                    lines.forEachIndexed { i, line ->
                        val trimmed = line.trim()
                        // Only call sites on a draw target (`g.`) matter; the helper definitions and
                        // the RenderSystem fallbacks use other receivers and are not the bug.
                        if (!trimmed.contains("g.enableScissor(")) return@forEachIndexed
                        if (trimmed.startsWith("*") || trimmed.startsWith("//")) return@forEachIndexed
                        if (trimmed.contains("enableScissorPoseAware(")) return@forEachIndexed
                        // The >=1.21.11 branch is fine: 26+ draws clips pose-aware on its own.
                        val gatedAbove = (1..GATE_LOOKBEHIND).any { back ->
                            val prev = lines.getOrNull(i - back)?.trim() ?: return@any false
                            prev.startsWith("//? if >=1.21.11")
                        }
                        if (gatedAbove) return@forEachIndexed
                        offenders += "${file.relativeTo(repoRoot())}:${i + 1}: $trimmed"
                    }
                }
        }
        assertTrue(
            offenders.isEmpty(),
            "Scissors must go through enableScissorPoseAware (or a >=1.21.11 gate):\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `login and logout are reachable for everyone and answer with a permission message`() {
        val vanilla = File(
            repoRoot(),
            "platform/server/src/main/kotlin/com/dreamdisplayx/platform/server/registrar/VanillaCommandTree.kt",
        )
        assertTrue(vanilla.isFile, "VanillaCommandTree.kt is missing.")
        val vanillaText = vanilla.readText()
        for (node in listOf("login", "logout")) {
            assertTrue(
                !vanillaText.contains(
                    "Commands.literal(\"$node\")\n" +
                        "        .requires { requiresNode(it, { _ -> \"dreamdisplayx.$node\" }",
                ),
                "/display $node must not gate the node itself with `requires`: Brigadier then hides it " +
                    "from unprivileged players, who see \"unknown or incomplete command\" instead of the " +
                    "permission message.",
            )
        }
        assertTrue(
            vanillaText.contains("{ _ -> \"dreamdisplayx.logout\" }, VanillaPermissions.Fallback.OP)) {\n" +
                "                        MessageUtil.sendMessage(player, \"displayCommandMissingPermission\")"),
            "/display logout must report the missing permission from inside executes.",
        )
        assertTrue(
            vanillaText.contains("{ _ -> \"dreamdisplayx.login\" }, VanillaPermissions.Fallback.OP)) {\n" +
                "                                MessageUtil.sendMessage(player, \"displayCommandMissingPermission\")"),
            "/display login must report the missing permission from inside executes.",
        )

        val paper = File(
            repoRoot(),
            "platform/server/src/main/kotlin/com/dreamdisplayx/platform/server/registrar/CommandRegistrar.kt",
        )
        assertTrue(paper.isFile, "CommandRegistrar.kt is missing.")
        val paperText = paper.readText()
        assertTrue(
            !paperText.contains("Commands.literal(\"logout\")\n        .requires {"),
            "The Paper /display logout must stay reachable for the same reason.",
        )
        assertTrue(
            paperText.contains("!sender.isOp && !sender.hasPermission(\"dreamdisplayx.logout\")"),
            "The Paper /display logout must check the permission inside executes.",
        )
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
        /** Client source roots that draw widgets. */
        val CLIP_SOURCE_ROOTS = listOf(
            "platform/client/common/src/main/kotlin",
        )

        /** How far above a call a `//? if >=1.21.11` gate may sit. */
        const val GATE_LOOKBEHIND = 2
    }
}