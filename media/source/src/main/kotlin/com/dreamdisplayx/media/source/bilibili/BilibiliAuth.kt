package com.dreamdisplayx.media.source.bilibili

import com.dreamdisplayx.util.asJsonObjectOrNull
import com.dreamdisplayx.util.obj
import com.dreamdisplayx.util.optInt
import com.dreamdisplayx.util.optString
import com.dreamdisplayx.util.json.DreamJson
import com.dreamdisplayx.util.net.DreamHttpClient
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * Bilibili account login for the client-side login UI: QR-code login (scan with the mobile app)
 * and phone-number + password login. Both produce a `SESSDATA` cookie value, which is what the
 * playback resolver needs for VIP / higher-quality streams.
 */
object BilibiliAuth {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/BilibiliAuth")

    private val HEADERS = DreamHttpClient.headersOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Accept" to "application/json",
        "Referer" to "https://www.bilibili.com",
    )

    /** Refresh token for extending the session, obtained from QR login. */
    @Volatile
    var refreshToken: String = ""

    /** A freshly generated QR login session. */
    data class QrCodeInfo(
        /** The QR image content the mobile app scans. */
        val url: String,
        /** Key used to poll for the scan result. */
        val qrcodeKey: String,
    )

    /** Outcome of one QR poll. */
    sealed interface PollResult {
        /** Login succeeded; [sessdata] is the credential to hand to the server. */
        data class Success(val sessdata: String, val refreshToken: String = "") : PollResult

        /** The QR code expired; a new one must be generated. */
        data object Expired : PollResult

        /** The code was scanned but not yet confirmed on the phone. */
        data object Scanned : PollResult

        /** Waiting for a scan. */
        data object Pending : PollResult

        /** Unexpected failure with a user-facing message. */
        data class Failure(val message: String) : PollResult
    }

    /** Outcome of a phone + password login. */
    sealed interface LoginResult {
        data class Success(val sessdata: String) : LoginResult
        data class Failure(val message: String) : LoginResult
    }

    /** Starts QR-code login; the returned [QrCodeInfo.url] is the image content to render. */
    fun generateQrCode(): QrCodeInfo? {
        val root = getJson("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
        val data = root?.obj("data") ?: return null
        val url = data.optString("url") ?: return null
        val key = data.optString("qrcode_key") ?: return null
        return QrCodeInfo(url, key)
    }

    /** Polls a QR login session; returns the outcome, including the `SESSDATA` once confirmed. */
    fun pollQrCode(qrcodeKey: String): PollResult {
        val root = getJson("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey")
        val data = root?.obj("data")
        // The QR-login status lives in `data.code`, NOT the top-level `code` (which is the HTTP
        // layer status and is 0 for every successful request). Reading the top-level code made us
        // treat "not yet scanned" (86101) and "scanned, awaiting confirmation" (86090) as success.
        // `data` is present on every state, so read the real status from it.
        val statusCode = data?.optInt("code") ?: root?.optInt("code") ?: -1
        logger.debug(
            "QR poll statusCode={} topCode={} hasData={} dataKeys={}",
            statusCode, root?.optInt("code"), data != null, data?.keys?.toList()
        )
        if (statusCode == 0) {
            // DEBUG + sanitized: the success payload contains the SESSDATA / refresh_token — never
            // log them at INFO (the QR poll loop repeats every couple of seconds while logging in).
            logger.debug("QR poll success (payload keys={})", data?.keys?.toList())
        }
        return when (statusCode) {
            0 -> {
                val cookie = data?.let { extractBilibiliCookie(it) }
                    ?: data?.optString("url")?.let { url -> fetchCrossDomainCookies(url) }
                val refreshTokenVal = data?.optString("refresh_token").orEmpty()
                if (cookie != null) {
                    logger.debug("QR login succeeded, cookie extracted (len={})", cookie.length)
                    if (refreshTokenVal.isNotEmpty()) {
                        refreshToken = refreshTokenVal
                        logger.debug("Bilibili refresh token stored (len={})", refreshTokenVal.length)
                    }
                    PollResult.Success(cookie, refreshTokenVal)
                } else {
                    // Rarely, Bilibili reports code=0 before the cookies are available. Keep polling
                    // so the next attempt picks up the real SESSDATA instead of faking a success.
                    logger.debug("QR poll code=0 but no usable cookie yet; will keep polling")
                    PollResult.Pending
                }
            }

            86038 -> PollResult.Expired
            86090 -> PollResult.Scanned
            else -> PollResult.Pending
        }
    }

    /**
     * Logs in with a phone number (or email) and password. The password is RSA-encrypted with
     * Bilibili's public key like the website does. When Bilibili demands a CAPTCHA this fails with a
     * message pointing the player at the QR flow.
     */
    fun loginWithPassword(username: String, password: String): LoginResult {
        val keyRoot = getJson("https://passport.bilibili.com/x/passport-login/web/key")
        val keyData = keyRoot?.obj("data") ?: return LoginResult.Failure("无法获取 Bilibili 加密密钥。")
        val hash = keyData.optString("hash").orEmpty()
        val publicKey = keyData.optString("key") ?: return LoginResult.Failure("无法获取 Bilibili 加密密钥。")

        val encrypted = rsaEncrypt(hash + password, publicKey)
            ?: return LoginResult.Failure("密码加密失败。")
        val form = buildString {
            append("username=").append(urlEncode(username))
            append("&password=").append(urlEncode(encrypted))
            append("&keep=true&source=main_web")
        }
        val root = postForm("https://passport.bilibili.com/x/passport-login/web/login", form)
        val code = root?.optInt("code")
        val data = root?.obj("data")
        return when {
            code == 0 && data != null -> {
                val cookie = extractBilibiliCookie(data)
                if (cookie != null) LoginResult.Success(cookie)
                else LoginResult.Failure("登录成功，但响应里没有 SESSDATA。")
            }

            else -> LoginResult.Failure(
                root?.optString("message")
                    ?: "登录失败（可能需要图形验证码，请改用扫码登录）。",
            )
        }
    }

    /**
     * Extracts a Bilibili login cookie string (e.g. `SESSDATA=...; bili_jct=...`) from a login
     * response's `data` object, or null.
     *
     * Bilibili's modern login endpoints return the cookies either as a `data.cookies` JSON object
     * (preferred), or nested in the redirect URL (as separate query params or a single URL-encoded
     * value). Both forms are handled here.
     */
    private fun extractBilibiliCookie(data: JsonObject): String? {
        // Preferred: a `cookies` object like {"SESSDATA": "...", "bili_jct": "...", "DedeUserID": "..."}
        data.obj("cookies")?.let { cookies ->
            val parts = mutableListOf<String>()
            for (key in listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")) {
                val value = cookies.optString(key)
                if (!value.isNullOrEmpty()) parts += "$key=$value"
            }
            // A login is only useful once we actually hold the SESSDATA. If the response only carried
            // auxiliary cookies (bili_jct, DedeUserID...) without SESSDATA, treat it as a failed
            // extraction instead of returning a broken cookie string that would claim "login success"
            // while the resolver has nothing to authenticate with.
            val hasSessdata = !cookies.optString("SESSDATA").isNullOrEmpty()
            if (hasSessdata && parts.isNotEmpty()) return parts.joinToString("; ")
            // No usable cookies object -> fall through to the redirect-URL parser.
        }

        // Fallback: parse the redirect URL (older / third-party flows).
        return extractBilibiliCookieFromUrl(data.optString("url"))
    }

    /** Extracts the Bilibili cookie string from a login redirect URL, or null. */
    private fun extractBilibiliCookieFromUrl(url: String?): String? {
        if (url == null) return null

        fun collect(from: String): Map<String, String> {
            val cookies = LinkedHashMap<String, String>()
            for (part in from.split('&')) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val key = part.substring(0, idx)
                val raw = part.substring(idx + 1)
                val value = runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8) }.getOrDefault(raw)
                if (key == "SESSDATA" || key == "bili_jct" || key == "DedeUserID" || key == "DedeUserID__ckMd5") {
                    if (value.isNotEmpty()) cookies[key] = value
                }
            }
            return cookies
        }

        // Pass 1: plain `&`-separated query params on the URL itself.
        var cookies = collect(url)

        // Pass 2: Bilibili sometimes URL-encodes the entire query once more; the SESSDATA value
        // then carries the other cookies as a decoded `&`-chain (e.g. `xxx&bili_jct=yyy`).
        if (cookies.isEmpty() || cookies["SESSDATA"]?.contains('&') == true) {
            val decoded = runCatching { URLDecoder.decode(url, StandardCharsets.UTF_8) }.getOrDefault(url)
            val fromDecoded = collect(decoded)
            if (fromDecoded.isNotEmpty()) cookies = fromDecoded
        }

        return cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }.ifEmpty { null }
    }

    /**
     * Fetches a Bilibili cross-domain ticket URL to collect the actual Set-Cookie headers that
     * contain SESSDATA, bili_jct, DedeUserID, etc. Called after the QR poll returns code=0 with a
     * crossDomain URL (whose JSON response has no cookies itself — they come from the Set-Cookie
     * headers of that endpoint).
     */
    private fun fetchCrossDomainCookies(url: String): String? = runCatching {
        val response = DreamHttpClient.execute(
            url,
            DreamHttpClient.RequestOptions(
                headers = HEADERS,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000,
                followRedirects = false,
            ),
        )
        // Collect Set-Cookie headers from all redirect hops.
        val allCookies = linkedSetOf<String>()
        fun collectCookies(headers: Map<String, List<String>>) {
            for ((name, values) in headers) {
                if (name.equals("Set-Cookie", ignoreCase = true)) {
                    for (value in values) {
                        val cookie = value.substringBefore(';').trim()
                        if (cookie.contains('=')) allCookies.add(cookie)
                    }
                }
            }
        }
        collectCookies(response.headers)
        // Follow the (usually 302) redirect once to collect the cookies on the next hop too.
        val location = response.headers.entries
            .firstOrNull { it.key.equals("Location", ignoreCase = true) }
            ?.value?.firstOrNull()
        if (location != null) {
            runCatching {
                val follow = DreamHttpClient.execute(
                    location,
                    DreamHttpClient.RequestOptions(
                        headers = HEADERS,
                        connectTimeoutMs = 10_000,
                        readTimeoutMs = 10_000,
                        followRedirects = true,
                    ),
                )
                collectCookies(follow.headers)
            }
        }
        // Build the cookie string from the meaningful cookies.
        val parts = allCookies.filter { cookie ->
            cookie.substringBefore('=') in setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
        }
        parts.joinToString("; ").ifEmpty { null }
    }.onFailure { e ->
        logger.debug("Bilibili crossDomain cookie fetch failed for {}: {}.", url, e.message)
    }.getOrNull()

    /**
     * Attempts to extend the Bilibili session using the stored [refreshToken]. Returns true when the
     * refresh call was accepted. Should be called periodically (e.g. every hour) to keep the login
     * alive; when it returns false the stored session is likely expired and the user must log in again.
     */
    fun refreshSession(): Boolean {
        if (refreshToken.isEmpty()) return false
        val csrf = BilibiliApi.cookie.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.startsWith("bili_jct=")) trimmed.substringAfter("bili_jct=") else null
        }.firstOrNull() ?: return false

        val form = "csrf=$csrf&refresh_token=$refreshToken"
        val result = postForm("https://passport.bilibili.com/x/passport-login/web/cookie/refresh", form)
        val code = result?.optInt("code") ?: -1
        if (code != 0) {
            logger.warn("Bilibili session refresh rejected: code={} message={}", code, result?.optString("message"))
            return false
        }
        val newRefresh = result?.obj("data")?.optString("refresh_token")
        if (newRefresh != null) refreshToken = newRefresh
        logger.debug("Bilibili session refreshed; new refresh token stored={}", newRefresh != null)
        return true
    }

    /** RSA-encrypts [plain] with Bilibili's PEM public key (PKCS#1 padding, base64 output). */
    private fun rsaEncrypt(plain: String, publicKeyPem: String): String? = runCatching {
        val clean = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .trim()
        val keySpec = X509EncodedKeySpec(Base64.getDecoder().decode(clean))
        val publicKey: PublicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }.onFailure { logger.debug("RSA encrypt failed: {}.", it.message) }.getOrNull()

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** GETs [url] as JSON, or null on failure. */
    private fun getJson(url: String): kotlinx.serialization.json.JsonObject? = runCatching {
        val response = DreamHttpClient.execute(
            url,
            DreamHttpClient.RequestOptions(headers = HEADERS, connectTimeoutMs = 10_000, readTimeoutMs = 10_000),
        )
        DreamJson.compact.parseToJsonElement(response.bodyString()).asJsonObjectOrNull()
    }.onFailure { logger.debug("Bilibili login GET failed for {}: {}.", url, it.message) }.getOrNull()

    /** POSTs a URL-encoded form to [url] and parses the JSON response, or null on failure. */
    private fun postForm(url: String, form: String): kotlinx.serialization.json.JsonObject? = runCatching {
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
    }.onFailure { logger.debug("Bilibili login POST failed for {}: {}.", url, it.message) }.getOrNull()
}
