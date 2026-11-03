package com.dreamdisplayx.util.natives

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins where the fork is allowed to put things on Android.
 *
 * Two requirements hang off this object, both reported from a Zalith Launcher 2 device:
 * - the LibVLC runtime used to live in `java.io.tmpdir`, which a launcher wipes, so every clean-up
 *   forced a full re-download;
 * - the diagnostic logs used to land in `./dreamdisplayx/logs`, i.e. nowhere the log viewer reads.
 *
 * The rules are therefore: never pick a `noexec` emulated path for natives, and put logs in the
 * directory that already owns `latest.log`.
 */
class AndroidPathsTest {

    private val directories = ArrayList<File>()
    private val savedProperties = HashMap<String, String?>()

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { directories += it }

    private fun override(key: String, value: String?) {
        if (!savedProperties.containsKey(key)) savedProperties[key] = System.getProperty(key)
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }

    @AfterTest
    fun cleanUp() {
        savedProperties.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
        AndroidPaths.registerGameDirectory(null)
        AndroidPaths.appId = null
        directories.forEach { runCatching { it.deleteRecursively() } }
    }

    @Test
    fun `an explicit natives cache override wins verbatim`() {
        val target = File(tempDir("androidpaths-override"), "custom/natives")
        override("dreamdisplayx.nativesCacheRoot", target.absolutePath + "/")

        assertEquals(target.absolutePath, AndroidPaths.nativesCacheRoot())
    }

    @Test
    fun `the registered game directory is the first game dir candidate`() {
        val gameDir = tempDir("androidpaths-gamedir")
        AndroidPaths.registerGameDirectory(gameDir)

        assertEquals(gameDir.absolutePath, AndroidPaths.gameDirCandidates().first())
    }

    @Test
    fun `the live logs directory is preferred over a stale sibling`() {
        val gameDir = tempDir("androidpaths-logs-live")
        val logs = File(gameDir, "logs")
        assertTrue(logs.mkdirs())
        File(logs, "latest.log").writeText("vanilla\n", Charsets.UTF_8)
        AndroidPaths.registerGameDirectory(gameDir)

        val resolved = AndroidPaths.logsDirectory()

        assertNotNull(resolved)
        assertEquals(logs.absolutePath, resolved.absolutePath)
    }

    @Test
    fun `logs directory falls back to creating the logs child`() {
        val gameDir = tempDir("androidpaths-logs-create")
        AndroidPaths.registerGameDirectory(gameDir)

        val resolved = AndroidPaths.logsDirectory()

        assertNotNull(resolved)
        assertEquals(File(gameDir, "logs").absolutePath, resolved.absolutePath)
        assertTrue(resolved.isDirectory, "the fallback must create the directory it returns")
    }

    @Test
    fun `app-internal storage is reported as persistent`() {
        assertTrue(AndroidPaths.isPersistent("/data/user/0/com.example/files/dreamdisplayx/natives"))
        assertTrue(AndroidPaths.isPersistent("/data/data/com.example/files"))
        assertFalse(AndroidPaths.isPersistent("/storage/emulated/0/Android/data/com.example/files"))
        assertFalse(AndroidPaths.isPersistent("/sdcard/dreamdisplayx"))
    }

    @Test
    fun `the natives cache never resolves to emulated storage`() {
        // The bug report's shape: a launcher points `user.home` at emulated storage, and the cache
        // used to be created there. Emulated/external storage is `noexec`, so `dlopen` of the LibVLC
        // runtime fails from it even when the extraction itself "succeeded".
        val emulated = File(tempDir("androidpaths-emulated"), "minecraft").absolutePath
        override("dreamdisplayx.home", emulated)
        override("dreamdisplayx.nativesCacheRoot", null)

        val root = AndroidPaths.nativesCacheRoot()

        assertFalse(
            root.contains("/storage/emulated/") || root.contains("/Android/data/"),
            "a noexec path can never back the natives cache, was $root",
        )
    }

    @Test
    fun `an emulated game directory is still usable for logs`() {
        // Only executables need app-internal storage; logs beside `latest.log` are the whole point.
        val emulated = File(tempDir("androidpaths-logs-emulated"), ".minecraft")
        assertTrue(File(emulated, "logs").mkdirs())
        AndroidPaths.registerGameDirectory(emulated)
        AndroidPaths.appId = null

        assertEquals(
            File(emulated, "logs").absolutePath,
            AndroidPaths.logsDirectory()?.absolutePath,
        )
    }

    @Test
    fun `the package name is exposed once a loader provides it`() {
        AndroidPaths.appId = null

        AndroidPaths.appId = "com.example.loader"

        assertEquals("com.example.loader", AndroidPaths.packageName())
    }
}