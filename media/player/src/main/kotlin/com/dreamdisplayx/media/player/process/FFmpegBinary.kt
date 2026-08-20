package com.dreamdisplayx.media.player.process

import com.dreamdisplayx.media.player.util.daemon
import com.dreamdisplayx.media.runtime.system.Processes
import com.dreamdisplayx.util.OsInfo
import com.dreamdisplayx.util.net.DreamHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** `FFmpeg` binary downloader. **/
object FFmpegBinary {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/FFmpeg")
    private const val BTBN_BASE = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest"

    /**
     * Cache directory for the downloaded ffmpeg binary.
     * On Android, the game CWD is on emulated (noexec) storage, so we try several locations in
     * order until one accepts the binary (app-internal storage is the only place exec is allowed).
     * On other platforms, the CWD-based default is fine.
     */
    private val CACHE_ROOT: String = if (OsInfo.isAndroid) {
        androidCacheRoot()
    } else {
        "./dreamdisplayx/ffmpeg"
    }

    /** Tries candidate directories on Android, returning the first that is writable. */
    private fun androidCacheRoot(): String {
        val userDir = System.getProperty("user.dir", "")
        // Internal data dir derived from user.dir's package name (exec isn't needed here — the
        // bundled native libav .so files are dlopen'd, not exec'd — but a writable dir is).
        val pkg = Regex("/data/user/\\d+/([^/]+)").find(userDir)?.groupValues?.get(1)
            ?: Regex("/Android/data/([^/]+)").find(userDir)?.groupValues?.get(1)
        val candidates = buildList {
            add(System.getProperty("java.io.tmpdir", ""))
            add(System.getProperty("user.home", ""))
            if (pkg != null) add("/data/user/0/$pkg/cache")
        }.filter { it.isNotBlank() && !it.contains("/storage/emulated/") && !it.contains("/Android/data/") }
            .map { it.trimEnd('/') + "/dreamdisplayx/ffmpeg" }
        val root = candidates.firstOrNull { runCatching { File(it).mkdirs() }.isSuccess && File(it).canWrite() }
            ?: System.getProperty("java.io.tmpdir", "./dreamdisplayx/ffmpeg").trimEnd('/')
                .let { "$it/dreamdisplayx/ffmpeg" }
        logger.info("Android cache root: $root")
        return root
    }

    @Volatile
    private var cachedPath: String? = null

    /** Returns the path to a usable `FFmpeg` binary, resolving and caching it on the first call. */
    fun getPath(): String? {
        cachedPath?.let { return it }
        synchronized(this) {
            cachedPath?.let { return it }
            cachedPath = resolve()
            return cachedPath
        }
    }

    /**
     * Resolves the `FFmpeg` binary in the background to minimize latency on first use, and probes
     * its optional filters while it is there — otherwise that probe spawns its own `ffmpeg -filters`
     * synchronously inside the first playback launch, right where latency is most visible.
     */
    fun prewarmAsync() {
        daemon({
            runCatching {
                val path = getPath()
                if (path != null) FFmpegCapabilities.hasFilter(path, "scale_vt")
            }.onFailure { e -> logger.warn("Prewarm failed", e) }
        }, "FFmpeg-prewarm").start()
    }

    /**
     * Checks the cache directory for an existing binary, downloads and extracts one if not found,
     * and falls back to the system `FFmpeg` on any failure.
     */
    private fun resolve(): String? {
        // Manual override: `-Ddreamdisplayx.ffmpeg.path=...` or DREAMDISPLAYX_FFMPEG_PATH.
        // Useful on Android launchers (PojavLauncher / FCL / ZL) where the user has a working
        // static ffmpeg (e.g. from Termux) but the bundled download cannot be executed.
        val override = System.getProperty("dreamdisplayx.ffmpeg.path")?.takeIf { it.isNotBlank() }
            ?: System.getenv("DREAMDISPLAYX_FFMPEG_PATH")?.takeIf { it.isNotBlank() }
        if (override != null) {
            logger.info("Using FFmpeg from override: $override")
            return override
        }

        val p = detectPlatform() ?: run {
            logger.warn("No bundled binary URL for this OS / arch; trying system FFmpeg.")
            return findSystemFfmpeg()
        }

        val cacheDir = File("$CACHE_ROOT/${p.key}")
        val binary = File(cacheDir, p.binaryName)

        if (binary.isFile && binary.length() > 0 && binary.canExecute()) {
            logger.info("Using binary: ${binary.absolutePath}.")
            return binary.absolutePath
        }

        return runCatching {
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw IOException("Cannot create cache dir: $cacheDir.")
            }
            downloadAndExtract(p, binary)
            if (!binary.isFile || binary.length() == 0L) {
                throw IOException("Extracted binary is missing or empty.")
            }
            Processes.markExecutable(binary.toPath())
            Processes.removeMacQuarantine(binary.toPath())
            // Android: the glibc Linux builds cannot run on Bionic even with exec bits set, and a
            // `noexec`/SELinux policy can refuse them entirely — verify the binary actually runs
            // before trusting it, and fall back cleanly instead of surfacing raw EACCES later.
            if (OsInfo.isAndroid && !executable(binary)) {
                logger.warn("Bundled FFmpeg (${binary.name}) cannot execute on Android; trying system ffmpeg.")
                binary.delete()
                return findSystemFfmpeg()
            }
            logger.info("Ready to work.")
            binary.absolutePath
        }.getOrElse { e ->
            logger.error("Download failed, falling back to system ffmpeg", e)
            findSystemFfmpeg()
        }
    }

    /** True if [binary] is actually executable: spawning `-version` exits 0 within a short timeout. */
    private fun executable(binary: File): Boolean {
        val cmd = ProcessBuilder(binary.absolutePath, "-version").redirectErrorStream(true)
        return try {
            val p = cmd.start()
            daemon({
                try {
                    p.inputStream.transferTo(OutputStream.nullOutputStream())
                } catch (_: Exception) {
                }
            }, "FFmpeg-exec-check").start()
            if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                logger.info("Bundled FFmpeg verified executable: ${binary.absolutePath}.")
                true
            } else {
                p.destroyForcibly()
                logger.warn(
                    "Bundled FFmpeg did not respond to -version at {} (exit={}); " +
                        "canExecute()={}, posix rwx={}",
                    binary.absolutePath, p.exitValue(), binary.canExecute(),
                    runCatching { java.nio.file.Files.getPosixFilePermissions(binary.toPath()) }.getOrNull(),
                )
                false
            }
        } catch (e: Exception) {
            logger.warn(
                "Bundled FFmpeg could not be started at {}: {} (canExecute()={})",
                binary.absolutePath, e.message, binary.canExecute(),
            )
            false
        }
    }

    /** Downloads the archive for [p] to a temp file, extracts the binary to [destBinary], and cleans up the temp file. */
    @Throws(IOException::class)
    private fun downloadAndExtract(p: Platform, destBinary: File) {
        logger.info("Downloading ${p.url}...")
        if (p.isDirectBinary) {
            downloadWithRedirects(p.url, destBinary)
            logger.info("Downloaded ${destBinary.length()} bytes (direct binary).")
            return
        }
        val parent = destBinary.parentFile
        val tempArchive = File(parent, "_download" + if (p.isTarXz) ".tar.xz" else ".zip")
        try {
            downloadWithRedirects(p.url, tempArchive)
            logger.info("Downloaded ${tempArchive.length()} bytes, extracting '${p.entrySuffix}'...")
            if (p.isTarXz) extractFromTarXz(tempArchive, p.entrySuffix, destBinary)
            else extractFromZip(tempArchive, p.entrySuffix, destBinary)
        } finally {
            if (tempArchive.exists() && !tempArchive.delete()) tempArchive.deleteOnExit()
        }
    }

    /** Downloads [url] to [dest], following up to 10 HTTP redirects manually (GitHub releases use multiple hops). */
    @Throws(IOException::class)
    private fun downloadWithRedirects(url: String, dest: File) {
        // GitHub-hosted binaries are routed through gh-proxy.com first (some networks cannot reach
        // github.com directly), falling back to the original URL if the mirror fails.
        val candidates = if (url.startsWith("https://github.com/")) {
            listOf("https://gh-proxy.com/$url", url)
        } else {
            listOf(url)
        }
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                DreamHttpClient.downloadToFile(
                    candidate,
                    dest.toPath(),
                    DreamHttpClient.RequestOptions(
                        headers = DreamHttpClient.headersOf("User-Agent" to "DreamDisplaysX-ffmpeg-bootstrap"),
                        connectTimeoutMs = 20_000,
                        readTimeoutMs = 300_000,
                    ),
                )
                return
            } catch (e: Exception) {
                lastError = e
                if (dest.exists() && !dest.delete()) dest.deleteOnExit()
                val retry = candidate != candidates.last()
                logger.warn(
                    "FFmpeg download failed via {} ({});{}",
                    candidate, e.message, if (retry) " trying next source." else " giving up.",
                )
            }
        }
        throw lastError ?: IOException("FFmpeg download failed.")
    }

    /** Extracts the first ZIP entry whose name ends with [suffix] from [archive] to [dest]. */
    @Throws(IOException::class)
    private fun extractFromZip(archive: File, suffix: String, dest: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.endsWith(suffix)) {
                    BufferedOutputStream(FileOutputStream(dest)).use { out -> zis.transferTo(out) }
                    return
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        throw IOException("'$suffix' not found in ${archive.name}.")
    }

    /** Extracts the first tar.xz entry whose name ends with [suffix] from [archive] to [dest]. */
    @Throws(IOException::class)
    private fun extractFromTarXz(archive: File, suffix: String, dest: File) {
        BufferedInputStream(FileInputStream(archive)).use { fis ->
            XZCompressorInputStream(fis).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var e = tar.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name.endsWith(suffix)) {
                            BufferedOutputStream(FileOutputStream(dest)).use { out -> tar.transferTo(out) }
                            return
                        }
                        e = tar.nextEntry
                    }
                }
            }
        }
        throw IOException("'$suffix' not found in ${archive.name}.")
    }

    /** Scans well-known system paths for a working `ffmpeg` binary; returns null if none responds with exit 0. */
    private fun findSystemFfmpeg(): String? {
        val candidates = if (OsInfo.isAndroid) {
            // Termux-installed ffmpeg is the only viable source on stock Android 10+.
            // SELinux may still block exec from another app's process; the user can set
            // DREAMDISPLAYX_FFMPEG_PATH as a fallback.  Alternatively, the CI-built jar
            // bundles Android native libav (dreamdisplayx_lav.so + FFmpeg shared libs)
            // which uses dlopen/JNI and works without exec.
            arrayOf("ffmpeg", "/data/data/com.termux/files/usr/bin/ffmpeg")
        } else {
            arrayOf("ffmpeg", "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg")
        }
        for (candidate in candidates) {
            try {
                val p = ProcessBuilder(candidate, "-version").redirectErrorStream(true).start()
                daemon({
                    try {
                        p.inputStream.transferTo(OutputStream.nullOutputStream())
                    } catch (_: Exception) {
                    }
                }, "FFmpeg-version-drain").start()
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    logger.info("Using system ffmpeg: $candidate...")
                    return candidate
                }
                p.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        if (OsInfo.isAndroid) {
            logger.error("FFmpeg not found. Install Termux (pkg install ffmpeg) and set DREAMDISPLAYX_FFMPEG_PATH, or use the CI build with bundled native libav.")
        } else {
            logger.error("FFmpeg not found (no download succeeded, no system binary).")
        }
        return null
    }

    /** Returns a [Platform] descriptor for the current OS and architecture, or null if no bundled build is available. */
    private fun detectPlatform(): Platform? {
        // Android: BtbN/FFmpeg-Builds stopped shipping android / musl builds, and even eugeneware's
        // musl-static binaries cannot be executed on Android 10+ (noexec /data + SELinux).
        // The CI-built native libav (dreamdisplayx_lav.so + bundled FFmpeg libs) is the only
        // viable path — it uses dlopen/JNI, not execve. Without it, the user must install
        // Termux's ffmpeg (`pkg install ffmpeg`) and set DREAMDISPLAYX_FFMPEG_PATH.
        if (OsInfo.isAndroid) {
            logger.info("Android detected; skipping bundled FFmpeg binary (cannot execute on Android 10+).")
            return null
        }
        val isArm = OsInfo.isArm
        return when {
            OsInfo.isWindows -> if (isArm) null else
                Platform(
                    "windows-x64",
                    "$BTBN_BASE/ffmpeg-master-latest-win64-gpl.zip",
                    "ffmpeg.exe",
                    "/bin/ffmpeg.exe",
                    false
                )

            OsInfo.isMac -> if (isArm)
                Platform("macos-aarch64", "https://www.osxexperts.net/ffmpeg71arm.zip", "ffmpeg", "ffmpeg", false)
            else
                Platform("macos-x64", "https://evermeet.cx/ffmpeg/getrelease/zip", "ffmpeg", "ffmpeg", false)

            else -> if (isArm)
                Platform(
                    "linux-aarch64",
                    "$BTBN_BASE/ffmpeg-master-latest-linuxarm64-gpl.tar.xz",
                    "ffmpeg",
                    "/bin/ffmpeg",
                    true
                )
            else
                Platform(
                    "linux-x64",
                    "$BTBN_BASE/ffmpeg-master-latest-linux64-gpl.tar.xz",
                    "ffmpeg",
                    "/bin/ffmpeg",
                    true
                )
        }
    }

    private data class Platform(
        val key: String,
        val url: String,
        val binaryName: String,
        val entrySuffix: String,
        val isTarXz: Boolean,
        /** True when [url] points directly at the executable (no archive to extract). */
        val isDirectBinary: Boolean = false,
    )
}
