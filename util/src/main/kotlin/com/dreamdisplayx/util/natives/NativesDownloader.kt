package com.dreamdisplayx.util.natives

import com.dreamdisplayx.util.OsInfo
import com.dreamdisplayx.util.net.DreamHttpClient
import com.dreamdisplayx.util.net.DreamHttpClient.RequestOptions
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files

/**
 * Downloads the native LibVLC runtime from a pinned GitHub Release into the
 * working directory's `dreamdisplayx/natives/<os>/<arch>/` folder.
 *
 * The manifest (`dreamdisplayx/natives-manifest.json` bundled in the jar) maps
 * each platform to a release asset name. Downloads are proxied through
 * gh-proxy.com (with a direct GitHub fallback) so that users behind restrictive
 * networks can still fetch the binaries.
 *
 * sqlite-jdbc is no longer downloaded: on NeoForge it ships as a jar-in-jar and
 * on Fabric/Paper the stock native binaries inside the fat loader jar are used
 * directly. On Android the stock jar has no usable native (its Linux-Android
 * builds are absent from the published artifact), so [SqliteAndroidCompat]
 * extracts a bundled Bionic build and points sqlite-jdbc at it via the
 * `org.sqlite.lib.path` / `org.sqlite.lib.name` system properties.
 */
object NativesDownloader {

    private val logger = LoggerFactory.getLogger("DreamDisplaysX/NativesDownloader")

    private const val BASE_DIR = "./dreamdisplayx/natives"

    /**
     * Cache root for the native runtime. On Android the game working directory is usually on
     * noexec emulated storage, so the cache is redirected into app-internal storage where
     * dlopen works (see [AndroidPaths]).
     */
    private val baseDir: String by lazy {
        if (OsInfo.isAndroid) AndroidPaths.nativesCacheRoot() else BASE_DIR
    }

    // ── Manifest ──────────────────────────────────────────────────────────

    private data class Manifest(
        val version: Int,
        val release_base: String,
        val libvlc: Map<String, String>,
    )

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Called once during mod initialisation. Downloads and extracts any missing
     * native runtime, then sets the system properties that LibVLC needs to
     * discover it.
     *
     * Safe to call multiple times; subsequent calls are a no-op once the
     * runtime has been fetched successfully.
     */
    @JvmStatic
    fun ensure() {
        if (downloaded) return
        synchronized(this) {
            if (downloaded) return
            try {
                downloadAndExtract()
                downloaded = true
            } catch (e: Exception) {
                logger.error("Failed to download natives", e)
            }
        }
    }

    /** Whether the runtime has been successfully downloaded at least once. */
    private var downloaded = false

    // ── Platform detection ─────────────────────────────────────────────────

    private val platformKey: String by lazy {
        val os = when {
            OsInfo.isAndroid -> "android"
            OsInfo.isWindows -> "windows"
            OsInfo.isMac -> "macos"
            else -> "linux"
        }
        val arch = when {
            OsInfo.isArm64 -> "aarch64"
            OsInfo.isX86 -> "x86"
            // CI / manifest uses x64 (not x86_64) in platform keys.
            else -> "x64"
        }
        "$os-$arch"
    }

    private val osDir: String by lazy {
        when {
            OsInfo.isAndroid -> "Android"
            OsInfo.isWindows -> "Windows"
            OsInfo.isMac -> "Mac"
            else -> "Linux"
        }
    }

    private val archDir: String by lazy {
        when {
            OsInfo.isArm64 -> "aarch64"
            OsInfo.isX86 -> "x86"
            else -> "x86_64"
        }
    }

    // ── Download & extract ─────────────────────────────────────────────────

    private fun downloadAndExtract() {
        val manifest = loadManifest() ?: run {
            logger.warn("natives-manifest.json not found on classpath; skipping download.")
            return
        }

        val libvlcAsset = manifest.libvlc[platformKey]

        val nativesDir = File("$baseDir/$osDir/$archDir")
        nativesDir.mkdirs()

        // LibVLC
        if (libvlcAsset != null) {
            val libvlcDir = File(nativesDir, "libvlc")
            if (OsInfo.isAndroid) {
                // A previous release shipped four APK-era companions (libc++_shared.so /
                // libmla.so / libvlcjni.so). Since the AAR switch those files are stale and
                // must never linger next to the new libc++_dreamdisplayx.so: two libc++
                // runtimes in the same process is a guaranteed native crash (VLC thread
                // start routines jump to unmapped addresses). Heal existing dirty caches
                // even when hasLibVlc() below reports the cache as valid.
                cleanAndroidStaleNatives(libvlcDir)
            }
            if (!hasLibVlc(libvlcDir)) {
                logger.info("LibVLC natives not cached at {}; downloading {}", libvlcDir, libvlcAsset)
                downloadAndExtractTarGz(manifest.release_base, libvlcAsset, libvlcDir)
            } else {
                logger.info("LibVLC natives already cached at {}", libvlcDir)
            }
            // Set system properties for vlcj
            System.setProperty("jna.library.path", libvlcDir.absolutePath)
            val pluginsDir = File(libvlcDir, "plugins")
            if (pluginsDir.isDirectory) {
                System.setProperty("VLC_PLUGIN_PATH", pluginsDir.absolutePath)
            }
        } else {
            logger.warn("No LibVLC asset for platform {}", platformKey)
        }
    }

    // ── Manifest loading ───────────────────────────────────────────────────

    private fun loadManifest(): Manifest? {
        val url = javaClass.getResource("/dreamdisplayx/natives-manifest.json")
            ?: return null
        return try {
            val json = url.readText()
            // Simple manual JSON parsing — no external JSON library dependency needed
            // for this small, known structure. We parse the JSON by hand to avoid
            // pulling in a JSON library or relying on Minecraft's Gson which varies
            // between versions.
            parseManifest(json)
        } catch (e: Exception) {
            logger.error("Failed to read natives-manifest.json", e)
            null
        }
    }

    /**
     * Minimal JSON parser for the known manifest structure.
     * We cannot depend on a platform JSON library here because the mod targets
     * multiple Minecraft versions (Fabric / NeoForge / Paper) each with their
     * own JSON library classpath.
     */
    private fun parseManifest(json: String): Manifest {
        val version = parseJsonInt(json, "version")
        val releaseBase = parseJsonString(json, "release_base")
        val libvlc = parseJsonMap(json, "libvlc")
        return Manifest(version, releaseBase, libvlc)
    }

    // ── Tar.gz extraction ──────────────────────────────────────────────────

    private fun downloadAndExtractTarGz(baseUrl: String, asset: String, destDir: File) {
        val url = "$baseUrl/$asset"
        val candidates = listOf("https://gh-proxy.com/$url", url)

        var lastError: Exception? = null
        for (candidate in candidates) {
            val tempFile = File.createTempFile("natives-", ".tar.gz")
            try {
                logger.info("Downloading {} -> {}", candidate, tempFile.name)
                DreamHttpClient.downloadToFile(
                    candidate,
                    tempFile.toPath(),
                    RequestOptions(
                        headers = DreamHttpClient.headersOf("User-Agent" to "DreamDisplaysX-natives-bootstrap"),
                        connectTimeoutMs = 20_000,
                        readTimeoutMs = 300_000,
                    ),
                )
                logger.info("Downloaded {} bytes, extracting to {}", tempFile.length(), destDir)

                // Extract to a staging dir first, then flatten the leading
                // <os>/<arch>/ directory that the release archives carry.
                val staging = File.createTempFile("natives-stage-", "").let {
                    it.delete(); File(it.parentFile, it.name + "-d").apply { mkdirs() }
                }
                try {
                    extractTarGz(tempFile, staging)
                    flattenPlatformDir(staging, destDir)
                } finally {
                    staging.deleteRecursively()
                }
                logger.info("Extraction complete: {} files in {}", destDir.list()?.size ?: 0, destDir)
                return
            } catch (e: Exception) {
                lastError = e
                logger.warn("Download failed via {}: {}", candidate, e.message)
                // Clean up partial temp
                try {
                    Files.deleteIfExists(tempFile.toPath())
                } catch (_: Exception) {
                }
                // Clean up partial extraction
                destDir.deleteRecursively()
            }
        }
        throw lastError ?: IOException("Failed to download $asset")
    }

    /**
     * Moves the extracted content into [destDir]. Release archives contain a
     * leading `<os>/<arch>/` directory (e.g. `Linux/x86_64/`); if that structure
     * is present we flatten it, otherwise the content is copied as-is.
     *
     * The destination is wiped first so stale files from an older archive format
     * (e.g. APK-era `libc++_shared.so` / `libmla.so`) can never linger beside new
     * companions. `copyRecursively(overwrite=true)` only overwrites same-named
     * files, so without the wipe a mixed cache (two libc++ in one process) could
     * crash native code with SIGSEGV on Android.
     */
    private fun flattenPlatformDir(staging: File, destDir: File) {
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()
        val nested = File(staging, "$osDir/$archDir")
        val sourceRoot = if (nested.isDirectory) nested else staging
        sourceRoot.listFiles()?.forEach { child ->
            child.copyRecursively(File(destDir, child.name), overwrite = true)
        }
    }

    private fun extractTarGz(archive: File, destDir: File) {
        GzipCompressorInputStream(FileInputStream(archive).buffered()).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry: TarArchiveEntry? = tar.nextEntry
                while (entry != null) {
                    if (entry.isDirectory) {
                        val dir = File(destDir, entry.name)
                        dir.mkdirs()
                    } else {
                        val file = File(destDir, entry.name)
                        file.parentFile.mkdirs()
                        file.outputStream().buffered().use { out -> tar.transferTo(out) }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }

    // ── Cache validation ───────────────────────────────────────────────────

    /** Android companions that belong in the AAR-era cache. Anything else is stale. */
    private val androidWhitelist = setOf("libvlc.so", "libvlcjni.so", "libc++_dreamdisplayx.so")

    /**
     * Removes every `.so` in [dir] that is not part of the current Android runtime format.
     *
     * Stale APK-era companions (`libc++_shared.so` / `libmla.so`) must never be preloaded
     * next to the renamed `libc++_dreamdisplayx.so`: two libc++ copies loaded RTLD_GLOBAL
     * into one process corrupt each other's vtable / thread-start pointers (observed as
     * `SIGSEGV in __pthread_start`). This is called on every Android startup so even a
     * cache that was previously mixed heals itself without a full re-download.
     */
    private fun cleanAndroidStaleNatives(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".so") && file.name !in androidWhitelist) {
                logger.warn("Removing stale Android native companion {}", file.name)
                file.delete()
            }
        }
    }

    private fun hasLibVlc(dir: File): Boolean {
        if (!dir.isDirectory) return false
        // Android archives ship a single monolithic libvlc.so (plugins statically linked in),
        // so the plain libvlc.so probe works there too.
        val probeName = when {
            OsInfo.isWindows -> "libvlc.dll"
            OsInfo.isMac -> "libvlc.dylib"
            else -> "libvlc.so"
        }
        if (!File(dir, probeName).isFile) return false
        // Cache format check: since the Android AAR switch (unique libc++ SONAME), a valid
        // cache MUST carry the renamed libc++ companion. Old APK-based caches (libc++_shared.so
        // + libmla.so) are invalid and must be re-downloaded, otherwise the stale libc++ would
        // keep libvlc.so's dlopen failing with "cannot locate symbol".
        if (OsInfo.isAndroid) {
            return File(dir, "libc++_dreamdisplayx.so").isFile
        }
        return true
    }

    // ── Minimal JSON helpers ───────────────────────────────────────────────

    /**
     * Extracts the string value of a top-level JSON key.
     * Assumes the format: `"key": "value"` (no escaping).
     */
    private fun parseJsonString(json: String, key: String): String {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]+)\""
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1)
            ?: throw IOException("Missing JSON string key: $key")
    }

    /** Extracts the integer value of a top-level JSON key. */
    private fun parseJsonInt(json: String, key: String): Int {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)"
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1)?.toInt()
            ?: throw IOException("Missing JSON int key: $key")
    }

    /**
     * Parses a JSON object map at the top level (e.g. `"libvlc": {...}`).
     * Returns a map of string → string, assuming all values are strings.
     */
    private fun parseJsonMap(json: String, key: String): Map<String, String> {
        val pattern = "\"$key\"\\s*:\\s*\\{"
        val startMatch = Regex(pattern).find(json) ?: throw IOException("Missing JSON map key: $key")
        val start = startMatch.range.last + 1
        // Find matching closing brace
        var depth = 1
        var end = start
        while (end < json.length && depth > 0) {
            when (json[end]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth > 0) end++
        }
        val body = json.substring(start, end)
        val result = mutableMapOf<String, String>()
        val entryRegex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"")
        for (match in entryRegex.findAll(body)) {
            result[match.groupValues[1]] = match.groupValues[2]
        }
        return result
    }
}