package com.dreamdisplayx.util.natives

/**
 * Decides whether a cached native runtime is still the one this jar was built against.
 *
 * [NativesDownloader] used to answer only "are the files on disk?" — [NativesDownloader] then
 * reused that cache forever. A user who upgraded the mod across a natives release kept the *old*
 * libvlc binaries, because the file probe succeeded and nothing ever compared releases. The
 * symptom is subtle and ugly: a bug that was fixed by shipping a newer libvlc stays reproducible
 * for existing users while a clean install behaves correctly.
 *
 * The fix is a stamp file written next to the binaries, holding the release tag the cache came
 * from. A cache is reusable only when the stamp matches the tag the bundled manifest points at.
 *
 * This object is deliberately free of file and network IO so the decision itself is unit-testable;
 * [NativesDownloader] owns the reading and writing.
 */
object NativesCacheStamp {

    /** Name of the stamp file, written inside the platform's `libvlc/` directory. */
    const val FILE_NAME = "natives-stamp.txt"

    /**
     * The release tag a manifest's `release_base` URL points at.
     *
     * `release_base` looks like
     * `https://github.com/<owner>/<repo>/releases/download/natives-7f303c9-1787719531`, so the tag
     * is the last path segment. Returning the whole URL would also work as a cache key, but the
     * tag alone keeps the stamp file readable when a user opens it by hand.
     *
     * Returns `null` for anything that does not carry a usable tag (blank input, a trailing slash,
     * a URL pointing at a bare `.../releases` page), which callers must treat as "cannot verify"
     * rather than "matches".
     */
    fun tagOf(releaseBase: String): String? {
        val trimmed = releaseBase.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val last = trimmed.substringAfterLast('/')
        // A bare ".../releases" or ".../download" page has no tag segment.
        if (last.isEmpty() || last == "releases" || last == "download" || last == "latest") return null
        // Guard against a scheme-only or host-only string being accepted as a tag.
        if (last.contains(':')) return null
        return last
    }

    /**
     * Whether the cached runtime described by [stampedTag] can serve [expectedTag].
     *
     * Biased towards re-downloading: an unknown stamp (a cache predating this file, or an
     * unreadable one) never matches, because silently keeping stale binaries is exactly the bug
     * this exists to prevent. The cost of a wrong "stale" is one extra download; the cost of a
     * wrong "fresh" is a user reporting a bug that was already fixed.
     *
     * When [expectedTag] cannot be derived from the manifest we cannot know what is current, so
     * the previous behaviour (trust the on-disk files) is kept instead of re-downloading on every
     * single launch behind a broken or unusual manifest URL.
     */
    fun isFresh(stampedTag: String?, expectedTag: String?): Boolean {
        if (expectedTag == null) return true
        return stampedTag == expectedTag
    }

    /** Whether a cache with no stamp file at all may be reused. */
    fun legacyCacheIsFresh(expectedTag: String?): Boolean = expectedTag == null
}
