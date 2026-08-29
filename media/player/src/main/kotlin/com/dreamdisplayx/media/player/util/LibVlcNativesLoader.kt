package com.dreamdisplayx.media.player.util

import com.dreamdisplayx.util.natives.NativesDownloader
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.net.JarURLConnection
import java.util.jar.JarFile

/**
 * Loads the LibVLC native runtime and sets the system properties vlcj needs to
 * discover it.
 *
 * The primary path is [NativesDownloader], which fetches LibVLC into
 * `./dreamdisplayx/natives/<os>/<arch>/` at startup (the jar no longer bundles
 * any native binaries). A classpath extraction fallback (`libvlc/native/...`)
 * is retained for local dev builds that may still place natives on the
 * classpath.
 *
 * Survival-critical: vlcj 4.8.1's `vlcj-natives` artifact ships no native
 * binaries, so without the runtime download there is nothing to load.
 */
object LibVlcNativesLoader {

    private val logger = LoggerFactory.getLogger("DreamDisplaysX/LibVlcNatives")

    /** Cached libvlc version string, populated after a successful load. */
    @Volatile
    var libvlcVersion: String? = null
        private set

    /** True once [load] has completed successfully. */
    @Volatile
    var loaded: Boolean = false
        private set

    /** Path to the extracted libvlc native directory. */
    @Volatile
    private var extractedDir: File? = null

    /**
     * Extracts the bundled libvlc natives (if any) and sets up the system
     * properties so vlcj's `NativeDiscovery` can find them.
     *
     * Safe to call multiple times; subsequent calls are a no-op.
     *
     * @return true if natives were extracted and configured, false if no
     *         bundled natives were found (libvlc will need system-installed VLC).
     */
    @JvmStatic
    fun load(): Boolean {
        if (loaded) return true

        // 1. Ensure natives are downloaded (NativesDownloader handles the
        //    download + system-property setup). This is the primary path.
        NativesDownloader.ensure()

        // 2. Check whether the download path already has natives ready
        val downloadDir = resolveDownloadDir()
        if (downloadDir != null) {
            System.setProperty("jna.library.path", downloadDir.absolutePath)
            val pluginsDir = File(downloadDir, "plugins")
            if (pluginsDir.isDirectory) {
                System.setProperty("VLC_PLUGIN_PATH", pluginsDir.absolutePath)
            }
            loaded = true
            logger.info("LibVLC natives loaded from download cache: {}", downloadDir)
            cacheVersionFromExtracted()
            return true
        }

        // 3. Fallback: extract bundled natives from classpath (for users who
        //    run from a fat jar that already has them, or offline mode).
        val platform = detectPlatform()
        val os = platform.os
        val arch = platform.arch
        val resourceRoot = "libvlc/native/$os/$arch/"

        // Probe a concrete file to detect whether bundled natives exist.
        // jar: protocol does not reliably resolve directory entries, so we
        // check for a known file name inside the platform directory.
        val probeName = when (os) {
            "Windows" -> "libvlc.dll"
            "Mac"     -> "libvlc.dylib"
            else      -> "libvlc.so"
        }
        val probeResource = javaClass.getResource("/$resourceRoot$probeName")
        if (probeResource == null) {
            logger.warn(
                "No bundled libvlc natives found at {} (probed {}); " +
                    "falling back to system-installed VLC. " +
                    "Run the 'Build Natives' workflow to bundle libvlc.",
                resourceRoot, probeName,
            )
            cacheVersionFromSystem()
            return false
        }

        try {
            // Extract to a stable cache directory
            val cacheDir = File(
                System.getProperty("user.home"),
                ".dreamdisplayx/libvlc/cache",
            )
            cacheDir.mkdirs()

            // Use a versioned subdirectory based on resource content hash
            val extractDir = File(cacheDir, platform.os + "-" + platform.arch)
            extractDir.mkdirs()

            // Extract all files from the resource tree
            val extracted = extractResources(resourceRoot, extractDir)
            if (extracted.isEmpty()) {
                logger.warn("No libvlc files extracted from classpath resources.")
                return false
            }

            // Set system properties for vlcj's discovery
            System.setProperty("jna.library.path", extractDir.absolutePath)

            // Set VLC_PLUGIN_PATH if a plugins directory was extracted
            val pluginsDir = File(extractDir, "plugins")
            if (pluginsDir.isDirectory) {
                System.setProperty("VLC_PLUGIN_PATH", pluginsDir.absolutePath)
            }

            extractedDir = extractDir
            loaded = true

            logger.info(
                "LibVLC natives extracted to {} ({} files), " +
                    "jna.library.path and VLC_PLUGIN_PATH set.",
                extractDir.absolutePath, extracted.size,
            )

            // Cache the version for DebugStats
            cacheVersionFromExtracted()
            return true
        } catch (e: Exception) {
            logger.error("Failed to extract libvlc natives from classpath", e)
            return false
        }
    }

    /** Platform detection result. */
    private data class NativePlatform(val os: String, val arch: String)

    private fun detectPlatform(): NativePlatform {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()

        val os = when {
            osName.contains("win") -> "Windows"
            osName.contains("mac") -> "Mac"
            else -> "Linux"
        }
        val arch = when {
            osArch.contains("aarch64") || osArch.contains("arm64") -> "aarch64"
            osArch.contains("x86_64") || osArch.contains("amd64") -> "x86_64"
            osArch.contains("x86") || osArch.contains("i386") || osArch.contains("i686") -> "x86"
            else -> "x86_64"
        }
        return NativePlatform(os, arch)
    }

    /**
     * Resolves the directory where [NativesDownloader] extracts the LibVLC
     * runtime: `<gameDir>/dreamdisplayx/natives/<os>/<arch>/libvlc`.
     * Returns null when the download has not produced the expected layout.
     */
    private fun resolveDownloadDir(): File? {
        val platform = detectPlatform()
        val dir = File(
            "./dreamdisplayx/natives/${platform.os}/${platform.arch}/libvlc",
        )
        val probeName = when (platform.os) {
            "Windows" -> "libvlc.dll"
            "Mac"     -> "libvlc.dylib"
            else      -> "libvlc.so"
        }
        if (File(dir, probeName).isFile) return dir
        return null
    }

    /**
     * Recursively lists all resources under [resourceRoot] and copies them to
     * [extractDir], preserving the relative path structure.
     *
     * Works with both `jar:` URLs (packed fat jar) and `file:` URLs (dev
     * run from the build output directory).
     *
     * @return list of extracted files
     */
    private fun extractResources(resourceRoot: String, extractDir: File): List<File> {
        val extracted = mutableListOf<File>()

        val classLoader = javaClass.classLoader ?: ClassLoader.getSystemClassLoader()
        val resources = classLoader.getResources(resourceRoot)
        while (resources.hasMoreElements()) {
            val url = resources.nextElement()
            when (url.protocol) {
                "jar" -> extracted += extractFromJar(url, resourceRoot, extractDir)
                "file" -> extracted += extractFromDir(File(url.toURI()), resourceRoot, extractDir)
                else -> logger.warn("Unhandled libvlc resource protocol: {}", url.protocol)
            }
        }
        return extracted
    }

    /** Copies every entry under [resourceRoot] from the jar containing [jarUrl]. */
    private fun extractFromJar(jarUrl: java.net.URL, resourceRoot: String, extractDir: File): List<File> {
        val extracted = mutableListOf<File>()
        val connection = jarUrl.openConnection() as JarURLConnection
        val jarFile: JarFile = connection.jarFile
        try {
            val prefix = resourceRoot.removeSuffix("/")
            jarFile.entries().asSequence().forEach { entry ->
                val name = entry.name
                if (!entry.isDirectory && name.startsWith(prefix + "/")) {
                    val relativePath = name.removePrefix(prefix).trimStart('/')
                    val target = File(extractDir, relativePath)
                    target.parentFile.mkdirs()
                    jarFile.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    extracted.add(target)
                }
            }
        } finally {
            jarFile.close()
        }
        return extracted
    }

    /** Copies every file under the classpath directory [dir]. */
    private fun extractFromDir(dir: File, resourceRoot: String, extractDir: File): List<File> {
        val extracted = mutableListOf<File>()
        val prefix = resourceRoot.removeSuffix("/")
        if (!dir.isDirectory) return extracted
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relativePath = file.absolutePath.removePrefix(dir.absolutePath).trimStart('/', '\\')
            val target = File(extractDir, relativePath)
            target.parentFile.mkdirs()
            file.copyTo(target, overwrite = true)
            extracted.add(target)
        }
        return extracted
    }

    /**
     * Queries the libvlc version through the low-level binding (no vlcj) after the download
     * cache / extraction is in place. Fails silently if the native library isn't loadable.
     */
    private fun cacheVersionFromExtracted() {
        try {
            val api = com.sun.jna.Native.load("libvlc", com.dreamdisplayx.media.player.managers.LibVlc.LibVlcNative::class.java)
            val verPtr = api.libvlc_get_version()
            val ver = if (verPtr == null) null else verPtr.getString(0)
            if (!ver.isNullOrBlank()) {
                libvlcVersion = ver
                logger.info("LibVLC version: {}", ver)
            }
        } catch (e: Exception) {
            logger.warn("Could not query libvlc version after extraction: ${e.message}")
        }
    }

    /**
     * Version query for a system-installed VLC fallback. No longer used: libvlc is always loaded
     * from the downloaded runtime, so this is a no-op retained only for API stability.
     */
    private fun cacheVersionFromSystem() {
        logger.debug("No bundled libvlc found; system-installed VLC is not queried via vlcj.")
    }
}