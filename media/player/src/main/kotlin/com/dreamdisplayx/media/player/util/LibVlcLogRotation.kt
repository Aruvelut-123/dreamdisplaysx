package com.dreamdisplayx.media.player.util

import com.dreamdisplayx.util.log.LogRotation
import java.io.File
import java.time.Instant

/**
 * Rotates a verbose libvlc log into a timestamped gzip archive before a new run starts.
 *
 * A thin delegate: the policy itself lives in [LogRotation], shared with the file mirror in
 * `DreamLog` so the fork has exactly one "rotate into `.gz`, keep the newest N" implementation.
 * Keeping this name matters because the libvlc log predates the shared helper and its test drives
 * the rotation through this type.
 */
internal object LibVlcLogRotation {
    /**
     * Compresses [file] when it contains an older run, removes the original only after the
     * archive is complete, and keeps at most [maxArchives] compressed runs beside it.
     */
    fun rotate(
        file: File,
        now: Instant = Instant.now(),
        maxArchives: Int = LogRotation.DEFAULT_MAX_ARCHIVES,
    ): File? = LogRotation.rotate(file, now, maxArchives)
}