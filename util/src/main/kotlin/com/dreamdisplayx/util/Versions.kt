package com.dreamdisplayx.util

/**
 * Parses and orders Dream DisplaysX version strings.
 *
 * Mod and plugin versions are four-part (`major.minor.patch.build`, e.g. `1.10.0.3`) with an
 * optional prerelease suffix such as `-dev` or `-preview.5`. semver4j's `Semver.coerce` cannot
 * represent this scheme: it silently drops the fourth part (`1.10.0.3` -> `1.10.0`) and treats
 * `-dev` as build metadata, so ordering and stability checks silently break. This class keeps the
 * numeric parts intact and orders stable builds before prereleases of the same build number.
 */
data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: Int,
    /** Pre-release suffix without the leading `-`, e.g. `dev` or `preview.5`; null for stable builds. */
    val prerelease: String? = null,
) : Comparable<Version> {

    /** True when this is a stable (non-prerelease) build. */
    val isStable: Boolean get() = prerelease == null

    /** True when this is a dev or preview build. */
    val isPreRelease: Boolean get() = prerelease != null

    override fun compareTo(other: Version): Int {
        var c = major.compareTo(other.major); if (c != 0) return c
        c = minor.compareTo(other.minor); if (c != 0) return c
        c = patch.compareTo(other.patch); if (c != 0) return c
        c = build.compareTo(other.build); if (c != 0) return c
        // Stable beats prerelease of the same build; prereleases compare lexically, ignoring case.
        if (prerelease == null && other.prerelease != null) return 1
        if (prerelease != null && other.prerelease == null) return -1
        return (prerelease ?: "").compareTo(other.prerelease ?: "", ignoreCase = true)
    }

    override fun toString(): String {
        val base = "$major.$minor.$patch.$build"
        return if (prerelease == null) base else "$base-$prerelease"
    }

    companion object {
        /** Comfortably above any real version string; caps the work a single input can trigger. */
        private const val MAX_VERSION_LENGTH = 64

        /**
         * Parses [raw] into a [Version], or null if it does not look like one. Accepts an optional
         * `v` / `V` prefix, one to four numeric parts, and an optional `-suffix` prerelease.
         */
        fun parse(raw: String): Version? {
            var s = raw.trim().trimStart('v', 'V').trim()
            if (s.isEmpty() || s.length > MAX_VERSION_LENGTH) return null

            // Split off the prerelease suffix (`-dev`, `-preview.5`).
            val dash = s.indexOf('-')
            val prerelease: String?
            if (dash >= 0) {
                prerelease = s.substring(dash + 1)
                    .takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '.' } }
                    ?: return null
                s = s.substring(0, dash)
            } else {
                prerelease = null
            }
            // Our scheme never uses `+` build metadata.
            if (s.contains('+')) return null

            val parts = s.split('.').map { it.toIntOrNull() }
            if (parts.size !in 1..4 || parts.any { it == null || it < 0 }) return null
            val p = parts.map { it!! }
            return Version(
                major = p.getOrElse(0) { 0 },
                minor = p.getOrElse(1) { 0 },
                patch = p.getOrElse(2) { 0 },
                build = p.getOrElse(3) { 0 },
                prerelease = prerelease,
            )
        }
    }
}

/** Compares two raw version strings; falls back to plain lexicographic order when either side fails to parse. */
fun compareVersions(a: String, b: String): Int {
    val av = Version.parse(a)
    val bv = Version.parse(b)
    if (av != null && bv != null) return av.compareTo(bv)
    return a.compareTo(b)
}
