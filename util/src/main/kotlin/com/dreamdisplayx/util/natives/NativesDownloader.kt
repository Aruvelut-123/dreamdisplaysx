package com.dreamdisplayx.util.natives

import com.dreamdisplayx.util.log.DreamLog
import com.dreamdisplayx.util.OsInfo
import com.dreamdisplayx.util.net.DreamHttpClient
import com.dreamdisplayx.util.net.DreamHttpClient.RequestOptions
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
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

    private val logger = DreamLog.of("NativesDownloader")

    private const val BASE_DIR = "./dreamdisplayx/natives"

    /**
     * System property carrying the extracted FFmpeg runtime directory.
     *
     * Deliberately a property rather than a direct call into the media module:
     * `:util` must not depend on JavaCPP (the media module owns that dependency),
     * so the consumer reads this property and registers the libraries itself.
     */
    const val FFMPEG_DIR_PROPERTY = "dreamdisplayx.ffmpeg.dir"

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
        val ffmpeg: Map<String, String>,
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
            if (!hasLibVlc(libvlcDir) || !isCacheCurrent(libvlcDir, manifest)) {
                logger.info("LibVLC natives not cached at {}; downloading {}", libvlcDir, libvlcAsset)
                downloadAndExtractTarGz(manifest.release_base, libvlcAsset, libvlcDir)
                writeStamp(libvlcDir, manifest)
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

        // FFmpeg
        //
        // The files are extracted into `ffmpeg/` and the directory is published
        // through a system property; the media module (the only one that depends
        // on JavaCPP) reads it and registers the libraries with
        // `Loader.foundLibraries`. See FfmpegNatives for why the usual
        // java.library.path / cachedir routes do not work.
        val ffmpegAsset = manifest.ffmpeg[platformKey]
        if (ffmpegAsset != null) {
            val ffmpegDir = File(nativesDir, "ffmpeg")
            if (!hasFfmpeg(ffmpegDir) || !isCacheCurrent(ffmpegDir, manifest)) {
                logger.info("FFmpeg natives not cached at {}; downloading {}", ffmpegDir, ffmpegAsset)
                downloadAndExtractTarGz(manifest.release_base, ffmpegAsset, ffmpegDir)
                writeStamp(ffmpegDir, manifest)
            } else {
                logger.info("FFmpeg natives already cached at {}", ffmpegDir)
            }
            System.setProperty(FFMPEG_DIR_PROPERTY, ffmpegDir.absolutePath)
        } else {
            logger.info("No FFmpeg asset for platform {}; keeping the libvlc video path", platformKey)
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
        // "ffmpeg" is optional so an older manifest still parses; an empty map
        // simply means this release carries no FFmpeg runtime.
        val ffmpeg = runCatching { parseJsonMap(json, "ffmpeg") }.getOrDefault(emptyMap())
        return Manifest(version, releaseBase, libvlc, ffmpeg)
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

    /**
     * Android companions that belong in the AAR-era cache. Anything else is stale.
     *
     * Deliberately NOT whitelisted: `libvlcjni.so`. The pure-JNA pipeline never needs it
     * (libvlc.so's DT_NEEDED references only libc++/libm/libEGL/etc; the ten "libvlcjni"
     * strings inside libvlc.so are build-path macros, not runtime dlopens), and if it is
     * ever System.load'ed its JNI_OnLoad immediately fails: it calls FindClass on
     * "android/os/Build$VERSION", which does not exist on Pojav-style game JVMs
     * (desktop OpenJDK, no android.* framework classes) → NoClassDefFoundError → the JVM
     * dlcloses the library, but JNI_OnLoad has already spawned native worker threads whose
     * start routines point into libvlcjni's own text → the threads crash in
     * `__pthread_start` once the library is unmapped (observed crash pc in the unmapped gap
     * right after an "Unloaded shared library" event). Keeping libvlcjni.so out of the
     * cache means it can never be (re)loaded.
     */
    private val androidWhitelist = setOf("libvlc.so", "libc++_dreamdisplayx.so")

    /**
     * Removes every `.so` in [dir] that is not part of the current Android runtime format.
     *
     * Stale APK-era companions (`libc++_shared.so` / `libmla.so`) must never be preloaded
     * next to the renamed `libc++_dreamdisplayx.so`: two libc++ copies loaded RTLD_GLOBAL
     * into one process corrupt each other's vtable / thread-start pointers (observed as
     * `SIGSEGV in __pthread_start`). The AAR-era `libvlcjni.so` is excluded for the same
     * reason — its JNI_OnLoad crashes on desktop-style game JVMs and it is not a dependency
     * of libvlc.so (see [androidWhitelist]). This is called on every Android startup so
     * even a cache that was previously mixed heals itself without a full re-download.
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

    // ── Cache versioning ───────────────────────────────────────────────────

    /**
     * Whether the runtime already on disk came from the release this jar was built against.
     *
     * Without this, [hasLibVlc] alone pins a user to whatever binaries they first downloaded: a
     * mod update that ships a newer natives release would keep loading the old libvlc for every
     * existing install while clean installs got the fix. The stamp file records the release tag
     * so the two can be compared.
     *
     * A cache written before stamping existed has no stamp and therefore never matches — the
     * first launch after upgrading re-downloads once, which is the intended cost of clearing a
     * silently stale cache.
     */
    private fun isCacheCurrent(dir: File, manifest: Manifest): Boolean {
        val expected = NativesCacheStamp.tagOf(manifest.release_base)
        val stampFile = File(dir, NativesCacheStamp.FILE_NAME)
        if (!stampFile.isFile) {
            if (NativesCacheStamp.legacyCacheIsFresh(expected)) return true
            logger.info(
                "LibVLC cache at {} predates cache stamping; re-downloading once to pick up {}",
                dir,
                expected,
            )
            return false
        }
        val stamped = try {
            stampFile.readText().trim().ifEmpty { null }
        } catch (e: Exception) {
            logger.warn("Could not read {}; treating the cache as stale", stampFile, e)
            null
        }
        val fresh = NativesCacheStamp.isFresh(stamped, expected)
        if (!fresh) {
            logger.info(
                "LibVLC cache at {} is stale (stamped {}, manifest {}); re-downloading",
                dir,
                stamped,
                expected,
            )
        }
        return fresh
    }

    /** Records which natives release the extracted runtime came from. Best-effort. */
    private fun writeStamp(dir: File, manifest: Manifest) {
        val tag = NativesCacheStamp.tagOf(manifest.release_base) ?: return
        try {
            File(dir, NativesCacheStamp.FILE_NAME).writeText(tag + "\n")
        } catch (e: Exception) {
            // A missing stamp only costs one extra download next launch, never correctness.
            logger.warn("Could not write the natives cache stamp in {}", dir, e)
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

    /**
     * Whether [dir] holds a usable FFmpeg runtime.
     *
     * The probe is the JNI shim rather than the codec library itself: JavaCPP only
     * ever loads `libavcodec` *through* `libjniavcodec`, so a runtime missing the
     * shims cannot be used no matter how complete the codec side looks.
     */
    private fun hasFfmpeg(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val probeName = when {
            OsInfo.isWindows -> "jniavcodec.dll"
            OsInfo.isMac -> "libjniavcodec.dylib"
            else -> "libjniavcodec.so"
        }
        return File(dir, probeName).isFile
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