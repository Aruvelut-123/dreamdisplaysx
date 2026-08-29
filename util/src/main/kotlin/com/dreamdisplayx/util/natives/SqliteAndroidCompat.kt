package com.dreamdisplayx.util.natives

import com.dreamdisplayx.util.OsInfo
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Makes `sqlite-jdbc` usable on Android.
 *
 * The stock `org.xerial:sqlite-jdbc` jar bundles native binaries for the desktop
 * platforms it publishes (Linux/Windows/macOS, plus musl and FreeBSD) but NOT for
 * Android — the `Linux-Android` natives are built for its releases and shipped in
 * the `-sources` artifact, yet are absent from the runtime jar. Its `SQLiteJDBCLoader`
 * maps `os.name=Linux-Android` to a jar resource that does not exist and gives up,
 * so any `jdbc:sqlite:` connect on Android fails with `NativeLibraryNotFoundException`.
 *
 * The loader deliberately honours two system properties before falling back to jar
 * extraction: `org.sqlite.lib.path` (directory) + `org.sqlite.lib.name` (file name).
 * This class resolves the platform-appropriate Bionic build of `libsqlitejdbc.so`
 * bundled inside this jar, extracts it into the exec-friendly natives cache (see
 * [AndroidPaths]) and sets those properties, so the first connection loads our copy.
 * Desktop platforms are untouched — the loader keeps using the stock jar natives.
 */
object SqliteAndroidCompat {

    private val logger = LoggerFactory.getLogger("DreamDisplaysX/SqliteAndroidCompat")

    /** Bundled resource key → the file name we write on disk. */
    private const val RESOURCE_ROOT = "/dreamdisplayx/natives/sqlitejdbc"
    private const val LIB_FILE_NAME = "libsqlitejdbc.so"

    @Volatile
    private var configured = false

    /**
     * Installs the Android sqlite native before the first JDBC connection is opened.
     * Idempotent and a fast no-op on desktop. Call this as early as possible — the
     * SQLite pool is created lazily on the server bootstrap, so invoking it from a
     * companion/object initializer of any class that touches storage is sufficient.
     */
    @JvmStatic
    fun ensure() {
        if (configured || !OsInfo.isAndroid) return
        synchronized(this) {
            if (configured) return
            try {
                install()
                configured = true
            } catch (e: Exception) {
                logger.warn("Failed to install Android SQLite native; sqlite will be unavailable.", e)
            }
        }
    }

    /** Writes the bundled [LIB_FILE_NAME] into the natives cache and points the loader at it. */
    private fun install() {
        val resource = when {
            OsInfo.isArm64 -> "$RESOURCE_ROOT/android-aarch64/$LIB_FILE_NAME"
            OsInfo.isX64 -> "$RESOURCE_ROOT/android-x64/$LIB_FILE_NAME"
            else -> error("Unsupported Android arch: ${System.getProperty("os.arch")}")
        }
        val url = javaClass.getResource(resource) ?: error("Bundled sqlite native missing: $resource")
        val libDir = File(AndroidPaths.nativesCacheRoot(), "sqlitejdbc")
        libDir.mkdirs()
        val target = File(libDir, LIB_FILE_NAME)
        url.openStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        // The loader calls System.load(path) directly with these two properties, which is
        // classloader-independent and therefore works even where sqlite-jdbc ships as a
        // NeoForge jar-in-jar (a different module layer than our own classes).
        System.setProperty("org.sqlite.lib.path", libDir.absolutePath)
        System.setProperty("org.sqlite.lib.name", LIB_FILE_NAME)
        logger.info("Installed Android SQLite native at {}", target.absolutePath)
    }
}
