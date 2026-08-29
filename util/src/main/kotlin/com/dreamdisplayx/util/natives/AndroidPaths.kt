package com.dreamdisplayx.util.natives

import java.io.File

/**
 * Resolves Android-safe storage locations for the native runtime cache.
 *
 * On Android (PojavLauncher / FCL / Zalith and friends) the game working directory usually
 * lives on emulated storage (`/storage/emulated/`) or the app's external files dir
 * (`/Android/data/<pkg>/files/`), both of which are mounted `noexec` — `dlopen` of an .so
 * placed there is rejected by SELinux. App-internal storage (`/data/user/0/<pkg>/...`)
 * is the only location the linker will map executables from, so the natives cache must be
 * redirected there before the first extraction.
 */
object AndroidPaths {

    /**
     * Picks the first writable, exec-friendly directory for the natives cache, mirroring the
     * candidate order of the pre-1.9.5 Android support: `java.io.tmpdir` (already inside the
     * app cache dir under most launchers), `user.home`, then the app-internal cache dir
     * derived from the package name found in `user.dir`. Emulated-storage paths are skipped
     * so a directory that can never be dlopen'd from is not chosen.
     */
    fun nativesCacheRoot(): String {
        val userDir = System.getProperty("user.dir", "")
        val pkg = Regex("/data/user/\\d+/([^/]+)").find(userDir)?.groupValues?.get(1)
            ?: Regex("/Android/data/([^/]+)").find(userDir)?.groupValues?.get(1)
        val candidates = buildList {
            add(System.getProperty("java.io.tmpdir", ""))
            add(System.getProperty("user.home", ""))
            if (pkg != null) add("/data/user/0/$pkg/cache")
        }.filter { it.isNotBlank() && !it.contains("/storage/emulated/") && !it.contains("/Android/data/") }
            .map { it.trimEnd('/') + "/dreamdisplayx/natives" }
        val root = candidates.firstOrNull { runCatching { File(it).mkdirs() }.isSuccess && File(it).canWrite() }
            ?: System.getProperty("java.io.tmpdir", "./dreamdisplayx/natives").trimEnd('/')
                .let { "$it/dreamdisplayx/natives" }
        return root
    }
}
