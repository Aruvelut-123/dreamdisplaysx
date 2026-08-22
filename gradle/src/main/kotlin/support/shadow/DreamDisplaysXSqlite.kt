package support.shadow

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
 * Replaces the bundled (stock, original-symbol) sqlite-jdbc native binaries inside the shadow jar
 * with the CI-rebuilt ones that expose `Java_com_dreamdisplayx_libs_org_sqlite_core_NativeDB_`
 * JNI symbols matching the relocated class. The native resources must stay at their original
 * `org/sqlite/native/...` path (which `SQLiteJDBCLoader` hard-codes and shadow does not relocate).
 *
 * A zip rewrite in `doLast` is used instead of shadow's `from()` because shadow's default
 * DuplicatesStrategy.EXCLUDE would silently keep the stock binary and drop the rebuilt one when
 * they share the same path. When the CI native bundle is absent (local dev) this is a no-op and the
 * stock binaries remain.
 */
fun ShadowJar.includeRebuiltSqliteNatives(nativeBundleDir: File) {
    if (!nativeBundleDir.isDirectory) return
    val replacements = mutableMapOf<String, File>()
    nativeBundleDir.listFiles()?.filter { it.isDirectory }?.forEach { platformDir ->
        val subPath = sqliteNativeSubPath(platformDir.name) ?: return@forEach
        val libs = File(platformDir, subPath).listFiles()?.filter { it.isFile } ?: emptyList()
        libs.forEach { lib ->
            // The native resources must live under the RELOCATED org.sqlite package so that the
            // relocated SQLiteJDBCLoader's getNativeLibResourcePath() (which is derived from the
            // SQLiteJDBCLoader class package, now com.dreamdisplayx.libs.org.sqlite) can find them:
            //   /com/dreamdisplayx/libs/org/sqlite/native/<os>/<arch>/<lib>
            replacements["com/dreamdisplayx/libs/org/sqlite/native/$subPath/${lib.name}"] = lib
        }
    }
    if (replacements.isEmpty()) return

    doLast {
        val jar = archiveFile.get().asFile
        if (!jar.isFile) return@doLast
        val tmp = File(jar.parentFile, jar.name + ".sqlitetmp")
        ZipFile(jar).use { zin ->
            ZipOutputStream(tmp.outputStream().buffered()).use { zout ->
                // Copy every existing entry, replacing any sqlite native with the rebuilt lib.
                zin.entries().asSequence().forEach { entry ->
                    val name = entry.name
                    val replacement = replacements[name]
                    zout.putNextEntry(ZipEntry(name))
                    if (replacement != null) {
                        replacement.inputStream().use { it.copyTo(zout) }
                    } else {
                        zin.getInputStream(entry).use { it.copyTo(zout) }
                    }
                    zout.closeEntry()
                }
                // Add rebuilt sqlite native libs that the stock sqlite-jdbc never shipped
                // (e.g. Windows/aarch64), which would otherwise be missing from the jar.
                val existing = zin.entries().asSequence().map { it.name }.toMutableSet()
                replacements.forEach { (name, lib) ->
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
