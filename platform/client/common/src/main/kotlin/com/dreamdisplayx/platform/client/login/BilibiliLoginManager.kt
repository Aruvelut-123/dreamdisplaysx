package com.dreamdisplayx.platform.client.login

import com.dreamdisplayx.media.source.bilibili.BilibiliAuth
import com.dreamdisplayx.media.source.bilibili.BilibiliAuth.LoginResult
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
                completeLogin(result.sessdata)
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

    /** Attempts a phone-number (or email) + password login. */
    fun loginWithPassword(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            status = "请输入手机号和密码"
            return
        }
        status = "正在登录..."
        when (val result = BilibiliAuth.loginWithPassword(username, password)) {
            is LoginResult.Success -> completeLogin(result.sessdata)
            is LoginResult.Failure -> status = result.message
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

    private fun completeLogin(sessdata: String) {
        loggedIn = true
        _qrKey = null
        qrContent = null
        sendServerCommand("display login bilibili $sessdata")
        status = "登录成功！凭据已加密保存到服务器"
        logger.info("Bilibili login succeeded; SESSDATA sent to the server.")
    }

    private fun sendServerCommand(command: String) {
        val connection = Minecraft.getInstance().connection ?: return
        //? if >=26 {
        connection.sendCommand(command)
        //?} else
        /*connection.sendCommand(command)*/
    }
}
