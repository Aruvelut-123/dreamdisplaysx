package com.dreamdisplayx.media.source.direct

/**
 * Bounds and de-duplicates HLS master children without performing network probes.
 *
 * Child URLs are validated lazily when a rendition is actually attached to libvlc. Eagerly walking
 * every child would turn a large or hostile master playlist into a serial redirect-probe fan-out.
 */
internal object DirectHlsChildSelector {
    /** Maximum number of video and audio renditions exposed from one master playlist. */
    const val MAX_RENDITIONS = 128

    /** Keeps distinct video playlists while preserving the master's order. */
    fun variants(parsed: DirectHlsPlaylist.Parsed): List<DirectHlsPlaylist.Variant> =
        parsed.variants.distinctBy { it.url }.take(MAX_RENDITIONS)

    /** Keeps only audio groups referenced by an exposed video variant. */
    fun audioRenditions(
        parsed: DirectHlsPlaylist.Parsed,
        variants: List<DirectHlsPlaylist.Variant>,
    ): List<DirectHlsPlaylist.AudioRendition> {
        val referencedGroups = variants.flatMapTo(linkedSetOf()) { it.audioGroupIds }
        if (referencedGroups.isEmpty()) return emptyList()
        return parsed.audioRenditions
            .asSequence()
            .filter { it.groupId in referencedGroups }
            .distinctBy { it.url }
            .take(MAX_RENDITIONS)
            .toList()
    }
}
