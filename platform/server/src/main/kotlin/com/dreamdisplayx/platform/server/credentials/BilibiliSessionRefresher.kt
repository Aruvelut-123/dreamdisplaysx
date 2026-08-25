package com.dreamdisplayx.platform.server.credentials

import com.dreamdisplayx.util.asJsonObjectOrNull
import com.dreamdisplayx.util.obj
import com.dreamdisplayx.util.optInt
import com.dreamdisplayx.util.optString
import com.dreamdisplayx.util.json.DreamJson
import com.dreamdisplayx.util.net.DreamHttpClient
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Server-side Bilibili session refresher. Periodically extends the SESSDATA of every logged-in
 * player using the stored refresh token, so the credential stays valid on the server without
 * requiring the client to re-login.
 *
 * Bilibili's refresh flow:
 * 1. POST `/x/passport-login/web/cookie/refresh` with `csrf=<bili_jct>&refresh_token=<token>`
 * 2. GET `/x/passport-login/web/cookie/refresh/confirm` with the new refresh token + csrf
 * 3. The confirm endpoint sets updated cookies via Set-Cookie headers
 *
 * The refresh request **must** include a `buvid3` cookie — Bilibili's passport API uses it as a
 * device fingerprint. Without it the endpoint returns `-400 请求错误`. The `buvid3` is generated
 * once on first login (matching PiliPlus's format: `UUIDv4.toUpperCase() + random5digits + "infoc"`)
 * and persisted alongside the credential.
 */
object BilibiliSessionRefresher {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/BilibiliSessionRefresher")

    private val HEADERS = DreamHttpClient.headersOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Accept" to "application/json",
        "Referer" to "https://www.bilibili.com",
        "Origin" to "https://www.bilibili.com",
    )

    /** Outcome of a session refresh attempt. */
    data class RefreshResult(
        /** Whether the refresh was accepted by Bilibili. */
        val success: Boolean,
        /** The updated cookie string (SESSDATA=xxx; bili_jct=yyy; ...), or the old one on failure. */
        val cookie: String?,
        /** The updated refresh token, or the old one when the response did not roll it. */
        val refreshToken: String?,
        /** The updated buvid3 (if the server returned a new one), or the old one on failure. */
        val buvid3: String?,
        /** User-facing message for diagnostics. */
        val message: String,
    )

    /**
     * Generates a persistent device fingerprint in the same format PiliPlus uses.
     * Format: `UUIDv4.toUpperCase() + random5digits + "infoc"`.
     * Example: `XZ8A6C1E-4B2D-4F6A-8C9E-0F1A2B3C4D5E12345infoc`
     */
    fun generateBuvid3(): String =
        java.util.UUID.randomUUID().toString().uppercase() +
            String.format("%05d", (0 until 100000).random()) +
            "infoc"

    /**
     * Refreshes a Bilibili session using the stored [refreshToken] and the current [cookie].
     * @param buvid3  a persistent device fingerprint; when provided it is appended to the Cookie
     *                header of the refresh request, which Bilibili's passport API requires.
     * Returns the new cookie string, refresh token, and buvid3, or the old values on failure.
     */
    fun refresh(refreshToken: String, cookie: String, buvid3: String? = null): RefreshResult {
        if (refreshToken.isEmpty()) return RefreshResult(false, cookie, null, buvid3, "No refresh token stored.")
        if (cookie.isEmpty()) return RefreshResult(false, cookie, null, buvid3, "No cookie stored.")

        // Step 1: POST /cookie/refresh to get a new refresh token
        val csrf = extractCsrf(cookie)
        if (csrf == null) {
            logger.warn("Cannot refresh Bilibili session: no bili_jct in cookie.")
            return RefreshResult(false, cookie, refreshToken, buvid3, "Missing bili_jct in cookie.")
        }

        // Refresh tokens (and CSRF) may carry characters that break form encoding — always
        // percent-encode, otherwise Bilibili rejects the whole POST with "请求错误".
        val encodedCsrf = urlEncode(csrf)
        val encodedRefresh = urlEncode(refreshToken)

        // Ensure a buvid3 exists — Bilibili's passport API requires it as both a form parameter
        // and a cookie.  Generate one on-the-fly if missing (first-time refresh after upgrade).
        val effectiveBuvid3 = buvid3 ?: extractBuvid3(cookie) ?: generateBuvid3()
        val encodedBuvid3 = urlEncode(effectiveBuvid3)
        val form = "csrf=$encodedCsrf&refresh_token=$encodedRefresh&buvid3=$encodedBuvid3"

        // Build the cookie header: existing cookie + buvid3 (Bilibili passport API requires it).
        val cookieWithBuvid = if (!cookie.contains("buvid3=")) {
            "$cookie; buvid3=$effectiveBuvid3"
        } else {
            cookie
        }
        val refreshResult = postForm("https://passport.bilibili.com/x/passport-login/web/cookie/refresh", form, cookieWithBuvid)
        val code = refreshResult?.optInt("code") ?: -1
        if (code != 0) {
            // The message alone ("请求错误") hides why the refresh failed; the numeric code
            // distinguishes expired refresh_token (-400) from a bad csrf (-392) etc.
            val msg = refreshResult?.optString("message") ?: "Unknown error (code=$code)"
            logger.warn("Bilibili session refresh rejected: code={} message={}", code, msg)
            return RefreshResult(false, cookie, refreshToken, effectiveBuvid3, "$msg (code=$code)")
        }

        val newRefreshToken = refreshResult?.obj("data")?.optString("refresh_token") ?: refreshToken

        // Step 2: GET /cookie/refresh/confirm to have the server set the new cookies
        // The confirm endpoint may respond with Set-Cookie headers that contain the new SESSDATA.
        val confirmUrl = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh/confirm" +
                "?refresh_token=${urlEncode(newRefreshToken)}&csrf=$encodedCsrf"
        val confirmResponse = runCatching {
            DreamHttpClient.execute(
                confirmUrl,
                DreamHttpClient.RequestOptions(
                    headers = HEADERS + (if (cookieWithBuvid.isEmpty()) emptyMap() else DreamHttpClient.headersOf("Cookie" to cookieWithBuvid)),
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = 10_000,
                    followRedirects = false,
                ),
            )
        }.getOrNull()

        // Try to extract the new cookie from Set-Cookie headers of the confirm response
        var newCookie = cookie
        var newBuvid3 = effectiveBuvid3
        if (confirmResponse != null) {
            val extracted = extractCookieFromHeaders(confirmResponse.headers)
            if (extracted != null) newCookie = extracted
            // Also capture any buvid3/buvid4 the server may return
            confirmResponse.headers.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }
                .mapNotNull { cookieLine ->
                    val kv = cookieLine.substringBefore(';').trim()
                    if (kv.startsWith("buvid3=")) kv.substringAfter("buvid3=") else null
                }
                .firstOrNull()?.let { newBuvid3 = it }
        }

        logger.info("Bilibili session refreshed for stored credential (new refresh token={}, cookie changed={})",
            newRefreshToken != refreshToken, newCookie != cookie)
        return RefreshResult(true, newCookie, newRefreshToken, newBuvid3, "OK")
    }

    /** Extracts the `bili_jct` value from a SESSDATA cookie string. */
    private fun extractCsrf(cookie: String): String? = cookie.split(';').mapNotNull { part ->
        val trimmed = part.trim()
        if (trimmed.startsWith("bili_jct=")) trimmed.substringAfter("bili_jct=") else null
    }.firstOrNull()

    /** Extracts the `buvid3` value from a cookie string, or null if absent. */
    private fun extractBuvid3(cookie: String): String? = cookie.split(';').mapNotNull { part ->
        val trimmed = part.trim()
        if (trimmed.startsWith("buvid3=")) trimmed.substringAfter("buvid3=") else null
    }.firstOrNull()

    /** Extracts SESSDATA + bili_jct + DedeUserID + buvid3 from Set-Cookie response headers. */
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

    /** Percent-encodes a value for use in form bodies or query strings. */
    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun postForm(url: String, form: String, cookie: String? = null): JsonObject? = runCatching {
        val headers = mutableListOf("Content-Type" to "application/x-www-form-urlencoded")
        if (!cookie.isNullOrEmpty()) headers += "Cookie" to cookie
        val response = DreamHttpClient.execute(
            url,
            DreamHttpClient.RequestOptions(
                method = "POST",
                headers = HEADERS + DreamHttpClient.headersOf(*headers.toTypedArray()),
                body = form.toByteArray(Charsets.UTF_8),
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000,
            ),
        )
        DreamJson.compact.parseToJsonElement(response.bodyString()).asJsonObjectOrNull()
    }.onFailure { e -> logger.debug("Bilibili POST failed for {}: {}.", url, e.message) }.getOrNull()
}