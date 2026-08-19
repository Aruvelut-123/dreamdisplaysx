package com.dreamdisplayx.platform.server.credentials

import com.dreamdisplayx.core.protocol.common.packets.PlatformCredentials

/**
 * Shared login/logout logic for the `/display login` and `/display logout` commands, used by both
 * the Paper and vanilla command paths so they can never drift.
 */
object CredentialActions {
    /** Stores a credential for [playerUuid] on [platform] and returns the fresh snapshot. */
    fun login(playerUuid: String, platform: String, token: String): PlatformCredentials {
        CredentialStore.set(playerUuid, platform, token.trim())
        return snapshotFor(playerUuid)
    }

    /** Removes the credential for [playerUuid] on [platform] and returns the fresh snapshot. */
    fun logout(playerUuid: String, platform: String): PlatformCredentials {
        CredentialStore.clear(playerUuid, platform)
        return snapshotFor(playerUuid)
    }

    /** The current credential snapshot for [playerUuid], for handshakes and re-sends. */
    fun snapshotFor(playerUuid: String): PlatformCredentials = PlatformCredentials(
        bilibiliSessdata = CredentialStore.get(playerUuid, "bilibili") ?: "",
    )

    /** True when [platform] is a supported login platform. */
    fun isSupported(platform: String): Boolean = platform.equals("bilibili", ignoreCase = true)
}
