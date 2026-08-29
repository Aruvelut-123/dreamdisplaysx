package support.shadow

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.logging.Logging
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private val libvlcLogger = Logging.getLogger("dreamdisplayx.libvlc-natives")

/**
 * Maps a platform key (e.g. `linux-x64`) to the libvlc native resource sub-path.
 */
private fun libvlcNativeSubPath(platformKey: String): String? = when (platformKey) {
    "linux-x64" -> "Linux/x86_64"
    "linux-aarch64" -> "Linux/aarch64"
    "macos-x64" -> "Mac/x86_64"
    "macos-aarch64" -> "Mac/aarch64"
    "windows-x86" -> "Windows/x86"
    "windows-x64" -> "Windows/x86_64"
    "windows-aarch64" -> "Windows/aarch64"
    else -> null
}

/**
 * Injects the CI-collected LibVLC runtime libraries (libvlc, libvlccore, plugins)
 * into the shadow jar at `libvlc/native/<os>/<arch>/...` so the runtime loader
 * can extract them.
 *
 * The CI build-natives workflow produces:
 * ```
 * <libvlcBundleDir>/          (e.g. native/build/ci-bundle/libvlc)
 *   linux-x64/
 *     Linux/x86_64/libvlc.so
 *     Linux/x86_64/libvlccore.so
 *     Linux/x86_64/plugins/...
 *   windows-x64/
 *     Windows/x86_64/libvlc.dll
 *     ...
 * ```
 *
 * When the CI native bundle is absent (local dev) this is a no-op.
 */
fun ShadowJar.includeRebuiltLibVlcNatives(libvlcBundleDir: File) {
    if (!libvlcBundleDir.isDirectory) {
        libvlcLogger.warn(
            "LibVLC native bundle not found at {}; libvlc will not load at runtime. " +
                    "Build with CI (Build Natives workflow) or place collected natives there.",
            libvlcBundleDir.absolutePath,
        )
        return
    }

    // Map platform dirs (e.g. "windows-x64") to their resource paths
    val entries = mutableMapOf<String, File>()
    libvlcBundleDir.listFiles()?.filter { it.isDirectory }?.forEach { platformDir ->
        // platformDir = "windows-x64" etc.
        val subPath = libvlcNativeSubPath(platformDir.name) ?: return@forEach
        // The native files are inside <os>/<arch>/ within the platform dir
        val osArchDir = File(platformDir, subPath)
        if (!osArchDir.isDirectory) {
            // Maybe the platform dir itself contains the files directly
            collectFiles(platformDir, "libvlc/native/$subPath", entries)
        } else {
            collectFiles(osArchDir, "libvlc/native/$subPath", entries)
        }
    }

    if (entries.isEmpty()) {
        libvlcLogger.warn(
            "No supported libvlc native libraries found in {}; " +
                    "libvlc will not load at runtime.",
            libvlcBundleDir.absolutePath,
        )
        return
    }

    doLast {
        val jar = archiveFile.get().asFile
        if (!jar.isFile) return@doLast
        val tmp = File(jar.parentFile, jar.name + ".libvlctmp")
        ZipFile(jar).use { zin ->
            ZipOutputStream(tmp.outputStream().buffered()).use { zout ->
                // Copy every existing entry
                zin.entries().asSequence().forEach { entry ->
                    val name = entry.name
                    // Skip existing libvlc entries (we'll replace with CI-built ones)
                    val replacement = if (name.startsWith("libvlc/native/")) {
                        entries[name]
                    } else null
                    zout.putNextEntry(ZipEntry(name))
                    if (replacement != null) {
                        replacement.inputStream().use { it.copyTo(zout) }
                    } else {
                        zin.getInputStream(entry).use { it.copyTo(zout) }
                    }
                    zout.closeEntry()
                }
                // Add libvlc native entries that don't exist yet
                val existing = zin.entries().asSequence().map { it.name }.toMutableSet()
                entries.forEach { (name, lib) ->
                    if (name !in existing) {
                        zout.putNextEntry(ZipEntry(name))
                        lib.inputStream().use { it.copyTo(zout) }
                        zout.closeEntry()
                    }
                }
            }
        }
        jar.delete()
        tmp.renameTo(jar)
    }
}

/** Recursively collects files under [dir] into the entries map with the given [prefix] path. */
private fun collectFiles(dir: File, prefix: String, entries: MutableMap<String, File>) {
    dir.walkTopDown().filter { it.isFile }.forEach { file ->
        val relativePath = file.relativeTo(dir).invariantSeparatorsPath
        entries["$prefix/$relativePath"] = file
    }
}