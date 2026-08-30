package com.dreamdisplayx.media.player.util

import com.dreamdisplayx.util.natives.NativesDownloader
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
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
     * The library name (desktop) or absolute .so path (Android) that JNA should load for libvlc.
     *
     * On Android the JVM reports `os.name=Linux-Android`, so JNA walks its generic-Linux name
     * mapping and turns `Native.load("libvlc", ...)` into a `dlopen("liblibvlc.so")` lookup — a
     * doubled `lib` prefix the downloaded file never satisfies. Passing the absolute path to the
     * extracted `libvlc.so` makes JNA `dlopen` that exact file and skips name mapping entirely.
     * Returns null on desktop (the plain `"libvlc"` name is used there, matching every platform's
     * actual file name via `jna.library.path`).
     */
    @JvmStatic
    fun jnaLoadTarget(): String? {
        if (!com.dreamdisplayx.util.OsInfo.isAndroid) return null
        val candidates = listOfNotNull(resolveDownloadDir(), extractedDir)
        for (dir in candidates) {
            val so = File(dir, "libvlc.so")
            if (so.isFile) {
                preloadAndroidCompanions(dir)
                return so.absolutePath
            }
        }
        return null
    }

    /**
     * Preloads every companion `.so` next to `libvlc.so` into the process global symbol
     * namespace on Android.
     *
     * The Android linker does NOT search a dlopen'd library's own directory for its
     * `DT_NEEDED` dependencies, so `libvlc.so` — which links against the NDK libc++ and the
     * VLC-Android helper libs — fails to open with `cannot locate symbol
     * "_ZTTNSt6__ndk118basic_stringstream..."`. `System.load(path)` dlopens with
     * `RTLD_LAZY | RTLD_GLOBAL`, so each companion's symbols become globally visible and are
     * resolved when `libvlc.so` is opened afterwards.
     *
     * SONAME hardening: the shipped libc++ is renamed to `libc++_dreamdisplayx.so` at
     * packaging time (unique SONAME; `native/libvlc/build.sh` also rewrites libvlc.so's
     * `DT_NEEDED` to match). Pojav-style launchers (Zalith/FCL) load their own
     * `libc++_shared.so` before our code runs, and the Android linker deduplicates by
     * SONAME — so a same-named preload would silently bind to their library (often missing
     * the `_ZTTNSt6__ndk118basic_stringstream...` vtable symbol). The unique SONAME makes
     * the linker bind only to the copy we ship here.
     *
     * Identity-loading a plain native file cannot mutate any app state; failures are logged
     * and skipped. No-op on desktop.
     */
    private fun preloadAndroidCompanions(dir: File) {
        if (!com.dreamdisplayx.util.OsInfo.isAndroid) return
        val companions = (dir.listFiles() ?: return).asSequence()
            .filter { it.isFile && it.name.endsWith(".so") && it.name != "libvlc.so" }
            .sortedWith(
                // libc++ first: nothing else can link before the C++ runtime is in place.
                compareBy<File> {
                    when (it.name) {
                        "libc++_dreamdisplayx.so", "libc++_shared.so" -> 0
                        else -> 1
                    }
                }.thenBy { it.name },
            )
            .toList()
        // Multi-pass: companions may depend on each other (e.g. libvlcjni references
        // libvlc), so keep retrying the still-unloaded ones until no progress is made.
        var remaining = companions
        while (remaining.isNotEmpty()) {
            val (ok, failed) = remaining.partition { f ->
                try {
                    System.load(f.absolutePath)
                    true
                } catch (t: Throwable) {
                    false
                }
            }
            ok.forEach { logger.info("Preloaded Android companion native: {}", it.name) }
            if (failed.size == remaining.size) {
                // No progress this pass — the leftover companions need libvlc itself
                // (loaded later) or a missing dependency. Report and stop.
                failed.forEach { f ->
                    logger.warn(
                        "Could not preload Android companion native {} before libvlc.so; " +
                            "it will be resolved when libvlc loads", f.name,
                    )
                }
                break
            }
            remaining = failed
        }
    }

    /**
     * Injects the JVM's JavaVM pointer into the loaded `libvlc.so` on Android by invoking its
     * `JNI_OnLoad` export directly.
     *
     * VLC-Android's monolithic `libvlc.so` expects to be initialised through the vlcjni JNI
     * path, which calls `JNI_OnLoad` with the real JavaVM so the `libvlc` module can reach
     * the Android app context (cache dirs, OpenSL ES, MediaCodec). Plain JNA `Native.load`
     * just `dlopen`s the library and never triggers `JNI_OnLoad`, so on Android the VLC
     * AndroidBridge stays uninitialised and `libvlc_new` returns null (or VLC spawns worker
     * threads whose start routines dereference the missing bridge → SIGSEGV in
     * `__pthread_start`). This mirrors what squi2rel/VideoPlayer's `libvlc_jvm_bridge.so`
     * does, but through JNA alone:
     *
     *   1. find `libjvm.so` via `java.home` and call `JNI_GetCreatedJavaVMs` to obtain the
     *      live JavaVM pointer;
     *   2. call `libvlc.so`'s exported `JNI_OnLoad(JavaVM*, void*)` with that pointer.
     *
     * Best-effort: any failure is logged and swallowed — the library is already dlopen'd, so
     * a broken bridge must not prevent playback from being attempted.
     */
    @JvmStatic
    fun injectAndroidJniOnLoad() {
        if (!com.dreamdisplayx.util.OsInfo.isAndroid) return
        try {
            // 1. Locate libjvm.so (the running JVM exports JNI_GetCreatedJavaVMs).
            val javaHome = File(System.getProperty("java.home") ?: return)
            val jvmCandidates = listOf(
                File(javaHome, "lib/server/libjvm.so"),
                File(javaHome, "lib/libjvm.so"),
                File(javaHome, "jre/lib/server/libjvm.so"),
            )
            val libjvm = jvmCandidates.firstOrNull { it.isFile }
                ?: run {
                    logger.warn("JNI bridge: libjvm.so not found under {}", javaHome)
                    return
                }
            val jvmLib = NativeLibrary.getInstance(libjvm.absolutePath)
            val getCreatedJavaVMs = jvmLib.getFunction("JNI_GetCreatedJavaVMs")
            // jint JNI_GetCreatedJavaVMs(JavaVM** vmBuf, jsize bufLen, jsize* nVMs)
            val vmBuf = Memory(Native.POINTER_SIZE.toLong())
            val nVMs = Memory((Integer.SIZE / 8).toLong())
            val rc = getCreatedJavaVMs.invokeInt(arrayOf(vmBuf, 1, nVMs))
            val javaVM = vmBuf.getPointer(0)
            if (rc != 0 || javaVM == null || Pointer.nativeValue(javaVM) == 0L) {
                logger.warn("JNI bridge: JNI_GetCreatedJavaVMs failed (rc={}, vm={})", rc, javaVM)
                return
            }

            // 2. Call libvlc.so's JNI_OnLoad with the real JavaVM. The library is already
            //    loaded by JNA (Native.load), so NativeLibrary.getInstance reuses the handle.
            val libvlcSo = libvlcSoPath() ?: return
            val vlcLib = NativeLibrary.getInstance(libvlcSo)
            val jniOnLoad = vlcLib.getFunction("JNI_OnLoad")
            val jniRc = jniOnLoad.invokeInt(arrayOf(javaVM, null as Pointer?))
            logger.info("Injected JavaVM into libvlc.so JNI_OnLoad (rc={})", jniRc)
        } catch (t: Throwable) {
            // Never let a bridge failure take down the mod.
            logger.warn("JNI bridge injection failed (non-fatal): {}", t.message)
        }
    }

    /** Resolves the absolute path of the extracted libvlc.so, or null if unavailable. */
    private fun libvlcSoPath(): String? =
        listOfNotNull(resolveDownloadDir(), extractedDir)
            .map { File(it, "libvlc.so") }
            .firstOrNull { it.isFile }
            ?.absolutePath

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
            // Extract to a stable cache directory. On Android the classpath fallback must NOT
            // land in the (often noexec) game directory — use the same exec-friendly root as
            // NativesDownloader (java.io.tmpdir inside the app cache).
            val cacheRoot = if (com.dreamdisplayx.util.OsInfo.isAndroid) {
                com.dreamdisplayx.util.natives.AndroidPaths.nativesCacheRoot()
            } else {
                File(System.getProperty("user.home"), ".dreamdisplayx/libvlc/cache").absolutePath
            }
            val cacheDir = File(cacheRoot)
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
        // Android detection must come first: os.name reports "Linux" there, and the natives
        // cache lives in a different (exec-friendly) location.
        if (com.dreamdisplayx.util.OsInfo.isAndroid) {
            return NativePlatform("Android", if (com.dreamdisplayx.util.OsInfo.isArm64) "aarch64" else "x86_64")
        }
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
        val root = if (com.dreamdisplayx.util.OsInfo.isAndroid) {
            com.dreamdisplayx.util.natives.AndroidPaths.nativesCacheRoot()
        } else {
            "./dreamdisplayx/natives"
        }
        val dir = File("$root/${platform.os}/${platform.arch}/libvlc")
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
            val api = com.sun.jna.Native.load(
                jnaLoadTarget() ?: "libvlc",
                com.dreamdisplayx.media.player.managers.LibVlc.LibVlcNative::class.java,
            )
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