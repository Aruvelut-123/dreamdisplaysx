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

    /** Called on the client thread when login completes successfully; the screen can close itself. */
    @Volatile
    var onLoginSuccess: (() -> Unit)? = null

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
                status = "二维码已过期，正在自动刷新..."
                startQrLogin()
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
        // Clear local session state so the account is fully forgotten on this client too,
        // even if the server command is somehow not delivered.
        com.dreamdisplayx.media.source.bilibili.BilibiliAuth.refreshToken = ""
        com.dreamdisplayx.platform.client.managers.ClientStateManager.bilibiliSessdata = ""
        sendServerCommand("display logout bilibili")
        status = "正在退出登录..." // overwritten by the server response / client clear
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
        // Close the login screen on the client thread.
        onLoginSuccess?.invoke()
    }

    private fun sendServerCommand(command: String) {
        // All supported versions expose `connection.sendCommand(String)` for issuing a server
        // command from the client, including on a singleplayer integrated server. Guard on the
        // connection being non-null.
        val connection = Minecraft.getInstance().connection ?: return
        connection.sendCommand(command)
    }
}
