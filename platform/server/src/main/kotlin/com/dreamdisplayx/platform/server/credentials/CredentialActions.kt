package com.dreamdisplayx.platform.server.credentials

import com.dreamdisplayx.core.protocol.common.packets.PlatformCredentials
import org.slf4j.LoggerFactory

/**
 * Shared login/logout logic for the `/display login` and `/display logout` commands, used by both
 * the Paper and vanilla command paths so they can never drift.
 *
 * Bilibili credentials are stored in two keys:
 * - `"<uuid>:bilibili"` — the SESSDATA cookie string
 * - `"<uuid>:bilibili_refresh"` — the refresh token (empty if not available)
 *
 * When the client logs in, it may send the token as `"<sessdata>||<refresh_token>"`; the
 * `||` delimiter is split here so the server stores both pieces separately.
 */
object CredentialActions {
    private const val REFRESH_DELIMITER = "||"

    private val logger = LoggerFactory.getLogger("DreamDisplaysX/CredentialActions")

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

    /** The current credential snapshot for [playerUuid], for handshakes and re-sends. */
    fun snapshotFor(playerUuid: String): PlatformCredentials = PlatformCredentials(
        bilibiliSessdata = CredentialStore.get(playerUuid, "bilibili") ?: "",
        bilibiliRefreshToken = CredentialStore.get(playerUuid, "bilibili_refresh") ?: "",
    )

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
     * Refreshes every stored Bilibili session using its refresh token; [pushToPlayer] receives
     * `(playerUuid, freshCredentials)` for each online player whose session was renewed.
     */
    fun refreshAllBilibili(pushToPlayer: (playerUuid: String, credentials: PlatformCredentials) -> Unit) {
        CredentialStore.forEachBilibili { playerUuid, sessdata, refreshToken ->
            if (refreshToken.isEmpty()) return@forEachBilibili
            val result = BilibiliSessionRefresher.refresh(refreshToken, sessdata)
            if (result.success) {
                if (result.cookie != null && result.cookie != sessdata) {
                    CredentialStore.set(playerUuid, "bilibili", result.cookie)
                }
                val newRefresh = result.refreshToken
                if (newRefresh != null && newRefresh != refreshToken) {
                    CredentialStore.set(playerUuid, "bilibili_refresh", newRefresh)
                }
                pushToPlayer(
                    playerUuid,
                    PlatformCredentials(
                        bilibiliSessdata = result.cookie ?: sessdata,
                        bilibiliRefreshToken = newRefresh ?: refreshToken,
                    ),
                )
            } else {
                logger.warn("Bilibili session refresh failed for {}: {}", playerUuid, result.message)
            }
        }
    }
}