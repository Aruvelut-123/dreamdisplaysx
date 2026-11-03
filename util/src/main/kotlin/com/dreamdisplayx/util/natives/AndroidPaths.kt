package com.dreamdisplayx.util.natives

import java.io.File

/**
 * Resolves Android-safe storage locations for the native runtime cache and for logs.
 *
 * On Android (PojavLauncher / FCL / Zalith and friends) the game working directory usually
 * lives on emulated storage (`/storage/emulated/`) or the app's external files dir
 * (`/Android/data/<pkg>/files/`), both of which are mounted `noexec` — `dlopen` of an .so
 * placed there is rejected by SELinux. App-internal storage (`/data/user/0/<pkg>/...`)
 * is the only location the linker will map executables from, so the natives cache must be
 * redirected there before the first extraction.
 *
 * ## Why `java.io.tmpdir` is no longer preferred
 *
 * Android treats the app **cache** directory as disposable: the system trims it under storage
 * pressure and launchers (Zalith Launcher 2 in particular) clear it on exit. `java.io.tmpdir`
 * usually points straight into that cache dir, so caching the LibVLC runtime there meant every
 * clean-up forced a full re-download — reported as "ZL2 clears its cache and libvlc has to be
 * downloaded again". [nativesCacheRoot] therefore prefers the app-internal **files** directory,
 * which Android never trims automatically, and only falls back to the cache when nothing else
 * is writable.
 */
object AndroidPaths {

    /**
     * Package name of the host app, recovered from the paths the launcher exposes.
     *
     * Populated lazily from `user.dir` / `java.home` / `java.io.tmpdir` / `user.home`, and settable
     * by the loader once the real application id is known. Returns `null` when nothing matches,
     * which only means the package-specific fallbacks are skipped.
     */
    @Volatile
    var appId: String? = null

    /** Package name of the host app, or `null` when no path reveals it. */
    fun packageName(): String? {
        appId?.let { return it }
        val probes = listOf(
            registeredGameDir ?: "",
            System.getProperty("user.dir", ""),
            System.getProperty("java.home", ""),
            System.getProperty("java.io.tmpdir", ""),
            System.getProperty("user.home", ""),
        )
        for (probe in probes) {
            if (probe.isBlank()) continue
            Regex("/data/user/\\d+/([^/]+)").find(probe)?.groupValues?.get(1)?.let { return it }
            Regex("/data/data/([^/]+)").find(probe)?.groupValues?.get(1)?.let { return it }
            Regex("/Android/data/([^/]+)").find(probe)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    /**
     * Picks the first writable, exec-friendly directory for the natives cache.
     *
     * Order: app-internal **files** directories (persistent, survive a cache wipe) → app-internal
     * **cache** directories (disposable, the previous behaviour) → `java.io.tmpdir`. Emulated
     * storage paths are skipped entirely: a directory that can never be `dlopen`'d from is useless
     * no matter how writable it is.
     *
     * `-Ddreamdisplayx.nativesCacheRoot=<dir>` overrides the whole search, for a launcher that
     * reports an unexpected `user.home`.
     */
    fun nativesCacheRoot(): String {
        override("dreamdisplayx.nativesCacheRoot")?.let { return it.trimEnd('/') }
        val pkg = packageName()
        val root = (persistentCandidates(pkg) + cacheCandidates(pkg) + tmpCandidates())
            .distinct()
            .filter { isExecFriendly(it) }
            .map { it.trimEnd('/') + "/dreamdisplayx/natives" }
            .firstOrNull { it.canCreate() }
            ?: System.getProperty("java.io.tmpdir", "./dreamdisplayx/natives").trimEnd('/')
                .let { "$it/dreamdisplayx/natives" }
        return root
    }

    /**
     * True when [root] lives in app-internal storage, i.e. neither the OS nor the launcher will
     * trim it away. Callers use this only to decide whether to warn, never to change behaviour.
     */
    fun isPersistent(root: String): Boolean =
        root.startsWith("/data/user/0/") || root.startsWith("/data/data/")

    /**
     * The game directory as resolved by the running client, once it knows it.
     *
     * [gameDirCandidates] can only guess from `user.dir` and friends; Minecraft itself knows the
     * authoritative value (`Minecraft.gameDirectory`), but that class lives in the client modules,
     * which this pure-JVM module cannot depend on. The client initializer therefore registers the
     * real directory here and every later resolution prefers it.
     */
    @Volatile
    private var registeredGameDir: String? = null

    /** Registers the authoritative game directory (normally `Minecraft.gameDirectory`). */
    fun registerGameDirectory(directory: File?) {
        val path = directory?.absolutePath?.takeIf { it.isNotBlank() } ?: return
        registeredGameDir = path
    }

    /**
     * Game directory candidates on Android, best first, used to place logs next to `latest.log`.
     *
     * `user.dir` is the process working directory, which a launcher sets to its game directory.
     * Emulated storage is fine for logs — only executables need app-internal storage.
     */
    fun gameDirCandidates(): List<String> {
        val out = LinkedHashSet<String>()
        registeredGameDir?.let { out += it }
        override("dreamdisplayx.gameDir")?.let { out += it }
        val userDir = System.getProperty("user.dir", "")
        if (userDir.isNotBlank()) out += userDir
        // Zalith / Pojav layout: the working dir is often <root>/versions/<name>; the game root
        // (the directory that owns `logs/`) is its parent.
        Regex("^(.*?)/versions/[^/]+").find(userDir)?.groupValues?.get(1)?.let { out += it }
        generateSequence(userDir.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile }) { it.parentFile }
            .take(6)
            .filter { it.isDirectory }
            .forEach { out += it.absolutePath }
        packageName()?.let { pkg ->
            out += "/storage/emulated/0/Android/data/$pkg/files/.minecraft"
            out += "/storage/emulated/0/Android/data/$pkg/files"
        }
        return out.filter { it.isNotBlank() }
    }

    /**
     * The `logs` directory the game itself writes `latest.log` into, best first.
     *
     * This is what requirement "put the libvlc log next to Minecraft's own logs" needs: the mod log
     * must sit in the same folder the launcher's log viewer already reads. The candidate that
     * *already* contains a `latest.log` wins, because that is the only proof the directory is the
     * live one and not a stale `versions/<name>` sibling.
     *
     * Returns `<candidate>/logs` for the first candidate that either has an existing `logs` child or
     * is itself writable, and `null` only when no candidate is usable at all.
     */
    fun logsDirectory(): File? {
        val candidates = gameDirCandidates()
        // A directory that already holds a live latest.log is authoritative — prefer it outright.
        for (candidate in candidates) {
            val logs = File(candidate, "logs")
            if (File(logs, "latest.log").isFile) return logs
        }
        for (candidate in candidates) {
            val logs = File(candidate, "logs")
            if (logs.isDirectory || runCatching { logs.mkdirs() }.getOrDefault(false)) return logs
        }
        return null
    }

    private fun persistentCandidates(pkg: String?): List<String> = buildList {
        add(System.getProperty("dreamdisplayx.home", ""))
        // App-internal `files` first: on Android this is the only location that is both exec-friendly
        // and never trimmed by the OS or a launcher. `user.home` is only a fallback because a
        // launcher may point it into its own (disposable) working directory.
        if (pkg != null) {
            add("/data/user/0/$pkg/files")
            add("/data/data/$pkg/files")
        }
        add(System.getProperty("user.home", ""))
    }

    private fun cacheCandidates(pkg: String?): List<String> = buildList {
        if (pkg != null) {
            add("/data/user/0/$pkg/cache")
            add("/data/data/$pkg/cache")
        }
    }

    private fun tmpCandidates(): List<String> = listOfNotNull(
        System.getProperty("java.io.tmpdir", "").takeIf { it.isNotBlank() },
    )

    private fun override(property: String): String? =
        System.getProperty(property)?.takeIf { it.isNotBlank() }

    /**
     * Rejects emulated / external storage, which is mounted `noexec` on Android and therefore
     * cannot back a directory the linker maps `.so` files from.
     */
    private fun isExecFriendly(path: String): Boolean =
        path.isNotBlank() &&
            !path.contains("/storage/emulated/") &&
            !path.contains("/Android/data/") &&
            !path.contains("/Android/obb/")

    private fun String.canCreate(): Boolean =
        runCatching { File(this).let { it.isDirectory || it.mkdirs() } && File(this).canWrite() }
            .getOrDefault(false)
}