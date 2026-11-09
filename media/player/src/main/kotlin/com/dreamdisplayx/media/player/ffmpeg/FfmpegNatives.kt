package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.util.OsInfo
import com.dreamdisplayx.util.log.DreamLog
import com.dreamdisplayx.util.natives.NativesDownloader
import org.bytedeco.javacpp.Loader
import java.io.File
import java.net.URL

/**
 * Hands the downloaded FFmpeg runtime to JavaCPP.
 *
 * Why this class exists at all
 * ---------------------------
 * The mod does not ship FFmpeg inside its jar; `NativesDownloader` fetches a
 * per-platform archive on first launch and extracts it next to the LibVLC
 * runtime. JavaCPP, however, only ever finds a library through one of these
 * routes, and every other one was measured and rejected:
 *
 * | route                                   | measured result                        |
 * |-----------------------------------------|----------------------------------------|
 * | `platform.library.path`                 | compile/link only, never searched      |
 * | `platform.preloadpath`                  | never searched                         |
 * | `org.bytedeco.javacpp.cachedir`         | only decides where extraction happens  |
 * | `System.setProperty("java.library.path")`| ignored: read once at JVM start       |
 * | reflecting ClassLoader.sys_paths/usr_paths| fields removed in JDK 9+             |
 * | reflecting the system loader's `ucp`    | works, but needs `--add-opens`         |
 * | `System.load(absolutePath)`             | loads the object, Loader still misses  |
 * | **writing `Loader.foundLibraries`**     | **works, no JVM flags required**       |
 *
 * The last one is used because `org.bytedeco.javacpp` is a plain, unnamed-module
 * jar: reflecting into its own package-private static needs no `--add-opens`, so
 * a mod can do it on any launcher without touching the user's JVM arguments.
 *
 * The map JavaCPP fills when it loads from the classpath was dumped and its key
 * spelling recorded, which is what [libraryKey] reproduces:
 *
 * ```
 * libjniavutil.so      -> jniavutil          (unversioned file)
 * libavcodec.so.62     -> avcodec@.62        (suffix kept verbatim)
 * libavcodec.62.dylib  -> avcodec@.62
 * avcodec-62.dll       -> avcodec@.62
 * ```
 *
 * A wrong key silently does nothing — JavaCPP simply reports "no jniavcodec in
 * java.library.path" — so [libraryKey] is unit-tested against real file names
 * from all three platforms.
 */
internal object FfmpegNatives {

    private val logger = DreamLog.of("FfmpegNatives")

    /** Guards against registering twice and against re-entrant probing. */
    @Volatile
    private var installed = false

    /**
     * Registers the downloaded runtime with JavaCPP, once.
     *
     * Returns `true` when a runtime directory was found and at least one library
     * registered. Returns `false` on a build that carries no downloaded runtime
     * (then the caller must stay on the libvlc path rather than fail).
     */
    fun install(): Boolean {
        if (installed) return true
        return synchronized(this) {
            if (installed) return true
            val dir = runtimeDir()
            if (dir == null) {
                logger.info(
                    "No extracted FFmpeg runtime ({} unset or empty); " +
                        "the libvlc video path stays in charge",
                    NativesDownloader.FFMPEG_DIR_PROPERTY,
                )
                return false
            }
            val count = try {
                register(dir)
            } catch (e: Throwable) {
                logger.warn("Could not register the FFmpeg runtime at {}", dir, e)
                return false
            }
            if (count == 0) {
                logger.warn("FFmpeg runtime at {} holds no loadable library", dir)
                return false
            }
            logger.info("Registered {} FFmpeg libraries from {}", count, dir)
            installed = true
            true
        }
    }

    /** The extracted runtime directory, or `null` when this build/platform has none. */
    private fun runtimeDir(): File? {
        val path = System.getProperty(NativesDownloader.FFMPEG_DIR_PROPERTY) ?: return null
        if (path.isBlank()) return null
        val dir = File(path)
        return if (dir.isDirectory) dir else null
    }

    /**
     * Writes every shared object in [dir] into `Loader.foundLibraries`.
     *
     * The map is JavaCPP's own record of "where this library lives"; filling it
     * is exactly what a classpath load would have done, except the bytes come
     * from the downloader's directory instead of a jar entry.
     */
    private fun register(dir: File): Int {
        val field = Loader::class.java.getDeclaredField("foundLibraries")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val found = field.get(null) as MutableMap<String, Array<URL>>

        val files = dir.listFiles() ?: return 0
        var count = 0
        for (file in files) {
            if (!file.isFile) continue
            val key = libraryKey(file.name) ?: continue
            found[key] = arrayOf(file.toURI().toURL())
            count++
        }
        return count
    }

    /**
     * Maps a shared-object file name to the key JavaCPP uses.
     *
     * Returns `null` for anything that is not a loadable library (the bytedeco
     * archives also carry `ffmpeg` / `ffprobe` executables and json descriptors).
     *
     * Pure and side-effect free on purpose: the media module's unit tests cannot
     * load native code, but they can (and do) exercise this mapping.
     */
    internal fun libraryKey(fileName: String): String? {
        val name = fileName.substringAfterLast('/')
        return when {
            name.endsWith(".dll") -> keyForDll(name)
            name.endsWith(".dylib") -> keyForDylib(name)
            name.contains(".so") -> keyForElf(name)
            else -> null
        }
    }

    /** `libjniavutil.so` -> `jniavutil`, `libavcodec.so.62` -> `avcodec@.62`. */
    private fun keyForElf(name: String): String? {
        val idx = name.indexOf(".so")
        if (idx <= 0) return null
        val head = stripLibPrefix(name.substring(0, idx))
        if (head.isEmpty()) return null
        val suffix = name.substring(idx + 3)
        // ".so" is only the ELF marker when nothing follows it or when what
        // follows is a numeric ABI version (".so.62"). A file such as
        // "config.something" contains ".so" too, and reporting it as the library
        // "config" would register a descriptor as if it were loadable.
        return when {
            suffix.isEmpty() -> head
            // JavaCPP's own key was recorded as "avcodec@.62": dot kept verbatim.
            suffix.startsWith(".") && suffix.substring(1).all { it.isDigit() || it == '.' } -> "$head@$suffix"
            else -> null
        }
    }

    /** `libavcodec.62.dylib` -> `avcodec@.62`, `libjniavcodec.dylib` -> `jniavcodec`. */
    private fun keyForDylib(name: String): String? {
        val base = name.removeSuffix(".dylib")
        val dot = base.lastIndexOf('.')
        if (dot > 0 && base.substring(dot + 1).all { it.isDigit() }) {
            val head = stripLibPrefix(base.substring(0, dot))
            if (head.isEmpty()) return null
            return "$head@.${base.substring(dot + 1)}"
        }
        val head = stripLibPrefix(base)
        return head.ifEmpty { null }
    }

    /** `avcodec-62.dll` -> `avcodec@.62`, `libwinpthread-1.dll` -> `libwinpthread@.1`. */
    private fun keyForDll(name: String): String? {
        val base = name.removeSuffix(".dll")
        val dash = base.lastIndexOf('-')
        if (dash > 0 && base.substring(dash + 1).all { it.isDigit() }) {
            // Windows archives are not lib-prefixed, but strip it anyway so a
            // hypothetical lib-prefixed DLL keys the same way as the others.
            val head = stripLibPrefix(base.substring(0, dash))
            if (head.isEmpty()) return null
            return "$head@.${base.substring(dash + 1)}"
        }
        val head = stripLibPrefix(base)
        return head.ifEmpty { null }
    }

    private fun stripLibPrefix(s: String): String = if (s.startsWith("lib")) s.substring(3) else s

    /**
     * Platform string JavaCPP uses to locate a library inside a jar, e.g.
     * `linux-arm64`. Mirrors [Loader.getPlatform] for the platforms this mod
     * supports, but is derived from [OsInfo] so it stays consistent with the
     * directory names the downloader writes.
     */
    internal fun platformDirName(): String {
        val os = when {
            OsInfo.isAndroid -> "android"
            OsInfo.isWindows -> "windows"
            OsInfo.isMac -> "macosx"
            else -> "linux"
        }
        val arch = when {
            OsInfo.isArm64 -> "arm64"
            OsInfo.isX86 -> "x86"
            else -> "x86_64"
        }
        return "$os-$arch"
    }
}
