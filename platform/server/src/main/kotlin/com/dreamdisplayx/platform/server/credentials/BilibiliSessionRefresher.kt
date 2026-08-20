package com.dreamdisplayx.platform.server.credentials

import com.dreamdisplayx.util.asJsonObjectOrNull
import com.dreamdisplayx.util.obj
import com.dreamdisplayx.util.optInt
import com.dreamdisplayx.util.optString
import com.dreamdisplayx.util.json.DreamJson
import com.dreamdisplayx.util.net.DreamHttpClient
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.JsonObject

/**
 * Server-side Bilibili session refresher. Periodically extends the SESSDATA of every logged-in
 * player using the stored refresh token, so the credential stays valid on the server without
 * requiring the client to re-login.
 *
 * Bilibili's refresh flow:
 * 1. POST `/x/passport-login/web/cookie/refresh` with `csrf=<bili_jct>&refresh_token=<token>`
 * 2. GET `/x/passport-login/web/cookie/refresh/confirm` with the new refresh token + csrf
 * 3. The confirm endpoint sets updated cookies via Set-Cookie headers
 */
object BilibiliSessionRefresher {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/BilibiliSessionRefresher")

    private val HEADERS = DreamHttpClient.headersOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Accept" to "application/json",
        "Referer" to "https://www.bilibili.com",
    )

    /** Outcome of a session refresh attempt. */
    data class RefreshResult(
        /** Whether the refresh was accepted by Bilibili. */
        val success: Boolean,
        /** The updated cookie string (SESSDATA=xxx; bili_jct=yyy; ...), or the old one on failure. */
        val cookie: String?,
        /** The updated refresh token, or the old one when the response did not roll it. */
        val refreshToken: String?,
        /** User-facing message for diagnostics. */
        val message: String,
    )

    /**
     * Refreshes a Bilibili session using the stored [refreshToken] and the current [cookie].
     * Returns the new cookie string and refresh token, or the old values on failure.
     */
    fun refresh(refreshToken: String, cookie: String): RefreshResult {
        if (refreshToken.isEmpty()) return RefreshResult(false, cookie, null, "No refresh token stored.")
        if (cookie.isEmpty()) return RefreshResult(false, cookie, null, "No cookie stored.")

        // Step 1: POST /cookie/refresh to get a new refresh token
        val csrf = extractCsrf(cookie)
        if (csrf == null) {
            logger.warn("Cannot refresh Bilibili session: no bili_jct in cookie.")
            return RefreshResult(false, cookie, refreshToken, "Missing bili_jct in cookie.")
        }

        val form = "csrf=$csrf&refresh_token=$refreshToken"
        val refreshResult = postForm("https://passport.bilibili.com/x/passport-login/web/cookie/refresh", form)
        val code = refreshResult?.optInt("code") ?: -1
        if (code != 0) {
            val msg = refreshResult?.optString("message") ?: "Unknown error (code=$code)"
            logger.warn("Bilibili session refresh rejected: {}", msg)
            return RefreshResult(false, cookie, refreshToken, msg)
        }

        val newRefreshToken = refreshResult?.obj("data")?.optString("refresh_token") ?: refreshToken

        // Step 2: GET /cookie/refresh/confirm to have the server set the new cookies
        // The confirm endpoint may respond with Set-Cookie headers that contain the new SESSDATA.
        val confirmUrl = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh/confirm?refresh_token=$newRefreshToken&csrf=$csrf"
        val confirmResponse = runCatching {
            DreamHttpClient.execute(
                confirmUrl,
                DreamHttpClient.RequestOptions(
                    headers = HEADERS,
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = 10_000,
                    followRedirects = false,
                ),
            )
        }.getOrNull()

        // Try to extract the new cookie from Set-Cookie headers of the confirm response
        var newCookie = cookie
        if (confirmResponse != null) {
            val extracted = extractCookieFromHeaders(confirmResponse.headers)
            if (extracted != null) newCookie = extracted
        }

        logger.info("Bilibili session refreshed for stored credential (new refresh token={}, cookie changed={})",
            newRefreshToken != refreshToken, newCookie != cookie)
        return RefreshResult(true, newCookie, newRefreshToken, "OK")
    }

    /** Extracts the `bili_jct` value from a SESSDATA cookie string. */
    private fun extractCsrf(cookie: String): String? = cookie.split(';').mapNotNull { part ->
        val trimmed = part.trim()
        if (trimmed.startsWith("bili_jct=")) trimmed.substringAfter("bili_jct=") else null
    }.firstOrNull()

    /** Extracts SESSDATA + bili_jct + DedeUserID from Set-Cookie response headers. */
    private fun extractCookieFromHeaders(headers: Map<String, List<String>>): String? {
        val allCookies = linkedSetOf<String>()
        for ((name, values) in headers) {
            if (name.equals("Set-Cookie", ignoreCase = true)) {
                for (value in values) {
                    val cookie = value.substringBefore(';').trim()
                    if (cookie.contains('=')) allCookies.add(cookie)
                }
            }
        }
        val parts = allCookies.filter { cookie ->
            cookie.substringBefore('=') in setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
        }
        return parts.joinToString("; ").ifEmpty { null }
    }

    private fun postForm(url: String, form: String): JsonObject? = runCatching {
        val response = DreamHttpClient.execute(
            url,
            DreamHttpClient.RequestOptions(
                method = "POST",
                headers = HEADERS + DreamHttpClient.headersOf("Content-Type" to "application/x-www-form-urlencoded"),
                body = form.toByteArray(Charsets.UTF_8),
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000,
            ),
        )
        DreamJson.compact.parseToJsonElement(response.bodyString()).asJsonObjectOrNull()
    }.onFailure { e -> logger.debug("Bilibili POST failed for {}: {}.", url, e.message) }.getOrNull()
}