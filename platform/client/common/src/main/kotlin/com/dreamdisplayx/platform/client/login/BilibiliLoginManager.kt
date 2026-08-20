package com.dreamdisplayx.platform.client.login

import com.dreamdisplayx.media.source.bilibili.BilibiliAuth
import com.dreamdisplayx.media.source.bilibili.BilibiliAuth.PollResult
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

/**
 * Drives the client-side Bilibili login flow for the [PlatformLoginScreen]: QR-code login (scan
 * with the mobile app) or phone + password. On success the `SESSDATA` is sent to the server via
 * `/display login bilibili <sessdata>`, which stores it encrypted and pushes it back to this
 * client's resolver.
 */
object BilibiliLoginManager {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/BilibiliLogin")

    /** The QR image content currently displayed, or null when no QR session is active. */
    @Volatile
    var qrContent: String? = null
        private set

    /** Human-readable status line for the UI. */
    @Volatile
    var status: String = ""
        private set

    /** True once a login has succeeded this session. */
    @Volatile
    var loggedIn: Boolean = false
        private set

    private val qrKey: String?
        get() = _qrKey

    @Volatile
    private var _qrKey: String? = null

    /** Starts a QR-code login session. */
    fun startQrLogin() {
        loggedIn = false
        val info = BilibiliAuth.generateQrCode()
        if (info == null) {
            status = "无法获取二维码，请检查网络后重试。"
            _qrKey = null
            qrContent = null
            return
        }
        _qrKey = info.qrcodeKey
        qrContent = info.url
        status = "请使用 Bilibili App 扫描二维码登录"
    }

    /** Polls the active QR session; returns true when the login completed. */
    fun pollQr(): Boolean {
        val key = _qrKey ?: return false
        return when (val result = BilibiliAuth.pollQrCode(key)) {
            is PollResult.Success -> {
                completeLogin(result.sessdata, result.refreshToken)
                true
            }

            is PollResult.Expired -> {
                status = "二维码已过期，请点击刷新"
                false
            }

            is PollResult.Scanned -> {
                status = "已扫码，请在手机上确认"
                false
            }

            is PollResult.Pending -> {
                status = "等待扫码..."
                false
            }

            is PollResult.Failure -> {
                status = result.message
                false
            }
        }
    }

    /** Logs out by clearing the server-side credential. */
    fun logout() {
        loggedIn = false
        _qrKey = null
        qrContent = null
        sendServerCommand("display logout bilibili")
        status = "已退出登录"
    }

    private fun completeLogin(sessdata: String, refreshToken: String = "") {
        loggedIn = true
        _qrKey = null
        qrContent = null
        // Send the refresh token along so the server can renew the session when it expires.
        val credential = if (refreshToken.isNotEmpty()) "$sessdata||$refreshToken" else sessdata
        sendServerCommand("display login bilibili $credential")
        // Note: quality refresh happens on receipt of PlatformCredentials packet from the server,
        // which echoes back the credential and sets BilibiliApi.cookie locally.
        status = "登录成功！凭据已加密保存到服务器"
        logger.info("Bilibili login succeeded; SESSDATA sent to the server (refresh token included={}).", refreshToken.isNotEmpty())
    }

    private fun sendServerCommand(command: String) {
        val connection = Minecraft.getInstance().connection ?: return
        //? if >=26 {
        connection.sendCommand(command)
        //?} else
        /*connection.sendCommand(command)*/
    }
}
