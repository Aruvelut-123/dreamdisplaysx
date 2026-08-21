package com.dreamdisplayx.platform.server.credentials

import com.dreamdisplayx.core.protocol.common.packets.PlatformCredentials
import org.slf4j.LoggerFactory

/**
 * Shared login/logout logic for the `/display login` and `/display logout` commands, used by both
 * the Paper and vanilla command paths so they can never drift.
 *
 * Bilibili credentials are stored in two keys:
 * - `"<uuid>:bilibili"` — the SESSDATA cookie string (per-player)
 * - `"<uuid>:bilibili_refresh"` — the refresh token (empty if not available)
 * - `"__global__:bilibili"` — the global SESSDATA cookie string (shared across all players)
 * - `"__global__:bilibili_refresh"` — the global refresh token
 *
 * When a global credential is set, it is broadcast to all online players.
 */
object CredentialActions {
    private const val REFRESH_DELIMITER = "||"

    private val logger = LoggerFactory.getLogger("DreamDisplaysX/CredentialActions")

    /** Stores a global credential for [platform] and returns the snapshot. */
    fun globalLogin(platform: String, token: String): PlatformCredentials {
        val (sessdata, refresh) = splitToken(token.trim())
        CredentialStore.setGlobal(platform, sessdata)
        if (refresh.isNotEmpty()) {
            CredentialStore.setGlobal("${platform}_refresh", refresh)
        }
        return globalSnapshot()
    }

    /** Removes the global credential for [platform] and returns the snapshot. */
    fun globalLogout(platform: String): PlatformCredentials {
        CredentialStore.clearGlobal(platform)
        CredentialStore.clearGlobal("${platform}_refresh")
        return globalSnapshot()
    }

    /** The current global credential snapshot. */
    fun globalSnapshot(): PlatformCredentials = PlatformCredentials(
        bilibiliSessdata = CredentialStore.getGlobal("bilibili") ?: "",
        bilibiliRefreshToken = CredentialStore.getGlobal("bilibili_refresh") ?: "",
    )

    /** Stores a credential for [playerUuid] on [platform] and returns the fresh snapshot. */
    fun login(playerUuid: String, platform: String, token: String): PlatformCredentials {
        val (sessdata, refresh) = splitToken(token.trim())
        CredentialStore.set(playerUuid, platform, sessdata)
        if (refresh.isNotEmpty()) {
            CredentialStore.set(playerUuid, "${platform}_refresh", refresh)
        }
        return snapshotFor(playerUuid)
    }

    /** Removes the credential for [playerUuid] on [platform] and returns the fresh snapshot. */
    fun logout(playerUuid: String, platform: String): PlatformCredentials {
        CredentialStore.clear(playerUuid, platform)
        CredentialStore.clear(playerUuid, "${platform}_refresh")
        return snapshotFor(playerUuid)
    }

    /**
     * Returns the credential snapshot for [playerUuid]: first checks the global credential,
     * then falls back to the per-player credential.
     */
    fun snapshotFor(playerUuid: String): PlatformCredentials {
        val globalSessdata = CredentialStore.getGlobal("bilibili")
        if (!globalSessdata.isNullOrEmpty()) {
            return PlatformCredentials(
                bilibiliSessdata = globalSessdata,
                bilibiliRefreshToken = CredentialStore.getGlobal("bilibili_refresh") ?: "",
            )
        }
        return PlatformCredentials(
            bilibiliSessdata = CredentialStore.get(playerUuid, "bilibili") ?: "",
            bilibiliRefreshToken = CredentialStore.get(playerUuid, "bilibili_refresh") ?: "",
        )
    }

    /** True when [platform] is a supported login platform. */
    fun isSupported(platform: String): Boolean = platform.equals("bilibili", ignoreCase = true)

    /**
     * Splits a combined `"<sessdata>||<refresh_token>"` token into its parts.
     * When no delimiter is present the entire string is treated as the SESSDATA and the refresh
     * token is empty — this maintains backward compatibility with older clients that do not send
     * a refresh token.
     */
    private fun splitToken(token: String): Pair<String, String> {
        val idx = token.indexOf(REFRESH_DELIMITER)
        return if (idx >= 0) token.substring(0, idx) to token.substring(idx + 2)
        else token to ""
    }

    /**
     * Refreshes the single global Bilibili session using its refresh token. On success the new
     * SESSDATA and refresh token are stored and broadcast to every online player via
     * [broadcastToAll]. Per-player refresh has been removed.     */
    fun refreshAllBilibili(
        pushToPlayer: (playerUuid: String, credentials: PlatformCredentials) -> Unit,
        broadcastToAll: ((PlatformCredentials) -> Unit)? = null,
    ) {
        // Refresh global credential first
        val globalRefreshToken = CredentialStore.getGlobal("bilibili_refresh")
        val globalSessdata = CredentialStore.getGlobal("bilibili")
        if (!globalRefreshToken.isNullOrEmpty() && !globalSessdata.isNullOrEmpty()) {
            val result = BilibiliSessionRefresher.refresh(globalRefreshToken, globalSessdata)
            if (result.success) {
                if (result.cookie != null && result.cookie != globalSessdata) {
                    CredentialStore.setGlobal("bilibili", result.cookie)
                }
                val newRefresh = result.refreshToken
                if (newRefresh != null && newRefresh != globalRefreshToken) {
                    CredentialStore.setGlobal("bilibili_refresh", newRefresh)
                }
                val fresh = PlatformCredentials(
                    bilibiliSessdata = result.cookie ?: globalSessdata,
                    bilibiliRefreshToken = newRefresh ?: globalRefreshToken,
                )
                broadcastToAll?.invoke(fresh)
                return
            } else {
                logger.warn("Global Bilibili session refresh failed: {}", result.message)
            }
        }

    }
}