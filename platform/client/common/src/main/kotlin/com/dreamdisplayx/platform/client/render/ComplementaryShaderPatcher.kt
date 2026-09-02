package com.dreamdisplayx.platform.client.render

import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Creates a safe, disposable Complementary r5.8.1 shaderpack copy for Dream DisplaysX hooks.
 * The user's original archive is never modified. BSL, Bliss, Photon, and all unknown packs are skipped.
 */
object ComplementaryShaderPatcher {
    private const val PATCH_VERSION = "1"
    private const val SUPPORTED_VERSION = "r5.8.1"
    private const val MARKER = "dreamdisplayx/patch.properties"
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ComplementaryShaderPatcher")

    /** Scans and patches every supported Complementary archive present at client startup. */
    fun patchAtStartup(): List<Path> = runCatching {
        val directory = shaderpacksDirectory() ?: return@runCatching emptyList()
        if (!Files.isDirectory(directory)) return@runCatching emptyList()
        val patched = ArrayList<Path>()
        Files.list(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".zip", ignoreCase = true) }
                .filter(::isComplementary)
                .forEach { source ->
                    patch(source, directory)?.let(patched::add)
                }
        }
        patched
    }.onFailure { logger.warn("Complementary shader patch scan skipped: {}", it.message) }.getOrDefault(emptyList())

    private fun shaderpacksDirectory(): Path? = runCatching {
        val mc = Minecraft.getInstance()
        val field = mc.javaClass.methods.firstOrNull { it.name == "gameDirectory" && it.parameterCount == 0 }
        val value = field?.invoke(mc) ?: runCatching {
            mc.javaClass.getField("gameDirectory").get(mc)
        }.getOrNull()
        (value as? java.io.File)?.toPath()?.resolve("shaderpacks")
            ?: Path.of("shaderpacks").toAbsolutePath()
    }.getOrNull()

    private fun isComplementary(file: Path): Boolean = runCatching {
        val name = file.fileName.toString()
        if (name.contains("bsl", true) || name.contains("bliss", true) || name.contains("photon", true)) return false
        if (!(name.contains("complementary", true) && name.contains(SUPPORTED_VERSION, true))) return false
        ZipFile(file.toFile()).use { zip ->
            val description = zip.entries().asSequence().filter { it.name.endsWith("pack.json") }
                .map { zip.getInputStream(it).readBytes().toString(StandardCharsets.UTF_8) }.firstOrNull() ?: return false
            description.contains("complementary.dev", true)
        }
    }.getOrDefault(false)

    private fun patch(source: Path, directory: Path): Path? = runCatching {
        val digest = sha256(source)
        val output = directory.resolve("DreamDisplaysX-${source.fileName}")
        val manifest = directory.resolve("${output.fileName}.manifest")
        if (source.fileName.toString().startsWith("DreamDisplaysX-")) return null
        if (Files.exists(output) && Files.exists(manifest) && Files.readString(manifest).contains(digest)) return output

        val temp = Files.createTempFile(directory, ".dreamdisplayx-", ".zip")
        ZipFile(source.toFile()).use { input ->
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(temp))).use { out ->
                val names = input.entries().asSequence().map { it.name }.toHashSet()
                val target = "shaders/lib/common.glsl"
                input.entries().asSequence().forEach { entry ->
                    if (entry.name == target) return@forEach
                    out.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) input.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
                if (target in names) {
                    out.putNextEntry(ZipEntry(target))
                    val original = input.getInputStream(input.getEntry(target)).use { it.readBytes().toString(StandardCharsets.UTF_8) }
                    val hook = "\n// Dream DisplaysX Complementary hook v$PATCH_VERSION\n#ifndef DREAMDISPLAYX_VIDEO_LIGHT_HOOK\n#define DREAMDISPLAYX_VIDEO_LIGHT_HOOK\nvec3 dreamdisplayxVideoLight(vec3 baseLight, vec3 videoRgb, float strength) { return baseLight + videoRgb * strength; }\n#endif\n"
                    out.write((if (original.contains("DREAMDISPLAYX_VIDEO_LIGHT_HOOK")) original else original + hook).toByteArray(StandardCharsets.UTF_8))
                    out.closeEntry()
                }
                out.putNextEntry(ZipEntry(MARKER))
                out.write("pack=Complementary\nversion=$SUPPORTED_VERSION\nsource=${source.fileName}\nsha256=$digest\npatch=$PATCH_VERSION\n".toByteArray(StandardCharsets.UTF_8))
                out.closeEntry()
            }
        }
        Files.move(temp, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        Files.writeString(manifest, "source=${source.fileName}\nsha256=$digest\npatch=$PATCH_VERSION\n")
        logger.info("Created Complementary r5.8.1 patch copy: {}", output.fileName)
        output
    }.onFailure { logger.warn("Failed to patch Complementary {}: {}", source.fileName, it.message) }.getOrNull()

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(Files.newInputStream(path)).use { input ->
            val buffer = ByteArray(8192)
            var n: Int
            while (input.read(buffer).also { n = it } >= 0) if (n > 0) digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
