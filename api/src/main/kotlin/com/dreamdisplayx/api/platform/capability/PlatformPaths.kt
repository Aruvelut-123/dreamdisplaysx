package com.dreamdisplayx.api.platform.capability

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import java.nio.file.Path

/**
 * Platform-resolved filesystem locations used by API consumers and shared modules.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
interface PlatformPaths {
    /** User-editable configuration directory. */
    val configDir: Path

    /** Cache directory for disposable derived files. */
    val cacheDir: Path

    /** Persistent data directory owned by the mod / plugin. */
    val dataDir: Path

    /** Directory where installed mods / plugins are located. */
    val modDir: Path
}
