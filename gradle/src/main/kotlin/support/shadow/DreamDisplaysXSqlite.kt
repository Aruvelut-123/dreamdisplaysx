package support.shadow

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.File

/** Maps a native platform key (e.g. `linux-x64`) to the sqlite-jdbc native resource sub-path. */
fun sqliteNativeSubPath(platformKey: String): String? = when (platformKey) {
    "linux-x64" -> "Linux/x86_64"
    "linux-aarch64" -> "Linux/aarch64"
    "macos-x64" -> "Mac/x86_64"
    "macos-aarch64" -> "Mac/aarch64"
    "windows-x64" -> "Windows/x86_64"
    "windows-aarch64" -> "Windows/aarch64"
    else -> null
}

/**
 * Adds the CI-rebuilt sqlite-jdbc native libraries into the shadow jar under `org/sqlite/native/...`
 * (the path `SQLiteJDBCLoader` hard-codes). shadow already excludes the `org/sqlite/native` tree from
 * relocation, so these stay put. When the CI native bundle is absent (local dev) this is a no-op and
 * the driver's stock native binaries are used.
 */
fun ShadowJar.includeRebuiltSqliteNatives(nativeBundleDir: File) {
    if (!nativeBundleDir.isDirectory) return
    nativeBundleDir.listFiles()?.filter { it.isDirectory }?.forEach { platformDir ->
        val subPath = sqliteNativeSubPath(platformDir.name) ?: return@forEach
        val libs = File(platformDir, subPath).listFiles()?.filter { it.isFile } ?: emptyList()
        if (libs.isEmpty()) return@forEach
        from(libs) {
            into("org/sqlite/native/$subPath")
        }
    }
}
