package com.dreamdisplayx.util

import kotlinx.io.IOException

/** General utility functions for the mod. */
object GeneralUtil {
    /** Developer version. */
    private val DEV_VERSION = Regex("""^([0-9]+(?:\.[0-9]+)*)-dev$""")

    /** Preview version (uses .1, .2, etc. index) */
    private val PREVIEW_VERSION = Regex("""^([0-9]+(?:\.[0-9]+)*)-preview\.([0-9]+)$""")

    /** Release version */
    private val RELEASE_VERSION = Regex("""^[0-9]+(?:\.[0-9]+)*$""")

    /** Reads the classpath resource at [resourcePath] and returns its content as a UTF-8 string. */
    @Throws(IOException::class)
    fun readResource(resourcePath: String): String {
        val stream = GeneralUtil::class.java.getResourceAsStream(resourcePath)
            ?: throw IOException("Can't find the resource: $resourcePath.")
        return stream.bufferedReader().use { it.readText() }
    }

    /** Returns the mod version string from `version.txt`, or "unknown" if the resource is missing. */
    fun getModVersion(): String = cachedModVersion

    /**
     * Mod version read once from `version.txt`.
     *
     * [getPrettyModVersion] is called from `ModTitleLabel.draw` and therefore every rendered frame.
     * Resolving the classpath resource each time means opening a stream and reading a file on the
     * render thread 60+ times a second, which showed up as a visible FPS drop whenever the display
     * menu was open. The value is immutable for the lifetime of the process, so read it once.
     *
     * Named `cached*` rather than `modVersion` because a `modVersion` property would generate the
     * same JVM getter signature (`getModVersion()Ljava/lang/String;`) as [getModVersion].
     */
    private val cachedModVersion: String by lazy {
        runCatching { readResource("/assets/dreamdisplayx/version.txt").trim() }
            .getOrDefault("unknown")
    }

    /** Returns the short git commit hash from `commit.txt`, or "unknown" if the resource is missing. */
    fun getCommitId(): String =
        runCatching { readResource("/assets/dreamdisplayx/commit.txt").trim() }
            .getOrDefault("unknown")

    /** Formats version to human-readable label: `1.8.5-dev` -> `1.8.5 Developer`, `1.9.0-preview` -> `1.9.0 Preview`. */
    fun getPrettyModVersion(): String = cachedPrettyVersion

    /**
     * Already-formatted version label; see [cachedModVersion] for why this is cached (`prettyVersion`
     * would clash with this function's JVM signature).
     */
    private val cachedPrettyVersion: String by lazy {
        formatPrettyVersion(cachedModVersion)
    }

    /** Pure version-to-label mapping, split out so it can be unit-tested without classpath resources. */
    internal fun formatPrettyVersion(version: String): String {
        DEV_VERSION.matchEntire(version)?.let { return "${it.groupValues[1]} Developer" }
        PREVIEW_VERSION.matchEntire(version)?.let { return "${it.groupValues[1]} Preview ${it.groupValues[2]}" }
        if (RELEASE_VERSION.matches(version)) return "$version Release"
        return version
    }
}
