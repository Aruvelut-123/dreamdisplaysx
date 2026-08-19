package com.dreamdisplayx.media.source.bilibili

import com.dreamdisplayx.util.asJsonObjectOrNull
import com.dreamdisplayx.util.obj
import com.dreamdisplayx.util.optInt
import com.dreamdisplayx.util.optString
import com.dreamdisplayx.util.json.DreamJson
import com.dreamdisplayx.util.net.DreamHttpClient
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
        data class Success(val sessdata: String) : PollResult

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
        val code = data?.optInt("code") ?: -1
        val url = data?.optString("url")
        return when (code) {
            0 -> {
                val sessdata = extractBilibiliCookie(url)
                if (sessdata != null) PollResult.Success(sessdata)
                else PollResult.Failure("登录成功，但响应里没有 SESSDATA。")
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
                val sessdata = extractBilibiliCookie(data.optString("url"))
                if (sessdata != null) LoginResult.Success(sessdata)
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
     * redirect URL, or null. Bilibili nests the cookies either as plain query params or as a single
     * URL-encoded value; both forms are handled here.
     */
    private fun extractBilibiliCookie(url: String?): String? {
        if (url == null) return null
        val cookies = LinkedHashMap<String, String>()
        // First split on '&' at the top level (cookies are usually separate query params).
        for (part in url.split('&')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val key = part.substring(0, idx)
            val raw = part.substring(idx + 1)
            val value = runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8) }.getOrDefault(raw)
            if (key == "SESSDATA" || key == "bili_jct" || key == "DedeUserID" || key == "DedeUserID__ckMd5") {
                if (value.isNotEmpty()) cookies[key] = value
            }
        }
        // If nothing was found at top level, Bilibili sometimes returns the whole thing URL-encoded
        // once more (the value of the `url` param contains the cookies).
        if (cookies.isEmpty()) {
            val decoded = runCatching { URLDecoder.decode(url, StandardCharsets.UTF_8) }.getOrDefault(url)
            for (part in decoded.split('&')) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val key = part.substring(0, idx)
                val raw = part.substring(idx + 1)
                val value = runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8) }.getOrDefault(raw)
                if (key == "SESSDATA" || key == "bili_jct" || key == "DedeUserID" || key == "DedeUserID__ckMd5") {
                    if (value.isNotEmpty()) cookies[key] = value
                }
            }
        }
        return cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }.ifEmpty { null }
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
