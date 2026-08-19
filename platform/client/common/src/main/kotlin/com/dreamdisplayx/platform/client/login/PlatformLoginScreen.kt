package com.dreamdisplayx.platform.client.login

import com.dreamdisplayx.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplayx.platform.client.ui.drawText
import com.dreamdisplayx.platform.client.ui.kit.UiRect
import com.dreamdisplayx.platform.client.ui.kit.UiScreenBase
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import net.minecraft.client.Minecraft
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent
//?}
import net.minecraft.network.chat.Component

/**
 * Client-side Bilibili login screen: QR-code login (scan with the mobile app).
 * On success the `SESSDATA` is handed to [BilibiliLoginManager], which sends it to the
 * server for encrypted storage and playback use.
 *
 * Phone-number / password login was removed — use QR code or run
 * `/display login bilibili <sessdata>` directly if you already have a cookie.
 */
class PlatformLoginScreen : UiScreenBase(Component.literal("Bilibili Login")) {
    private var qrMatrix: BitMatrix? = null
    private var tickCount = 0
    private var refreshRect = UiRect(0, 0, 0, 0)

    /** QR matrix edge length and rendered scale. */
    private val qrSize = 200
    private val qrScale = 2

    // ARGB colors (Kotlin hex literals above Int.MAX_VALUE are Long; keep them as Int).
    private val colorWhite = 0xFFFFFFFF.toInt()
    private val colorBlack = 0xFF000000.toInt()
    private val colorGray = 0xFFAAAAAA.toInt()
    private val colorLightGray = 0xFFCCCCCC.toInt()
    private val colorDark = 0xFF333333.toInt()
    private val colorBlue = 0xFF2266CC.toInt()

    init {
        BilibiliLoginManager.startQrLogin()
        refreshQrMatrix()
    }

    override fun minContentSize(): Pair<Int, Int>? = 340 to 320

    override fun tick() {
        super.tick()
        tickCount++
        if (tickCount % 40 == 0) {
            // Poll the QR login every two seconds.
            BilibiliLoginManager.pollQr()
            refreshQrMatrix()
        }
    }

    private fun refreshQrMatrix() {
        val content = BilibiliLoginManager.qrContent
        qrMatrix = if (content != null) {
            runCatching { QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, qrSize, qrSize) }.getOrNull()
        } else {
            null
        }
    }

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawScreenBackground(g)
        val font = Minecraft.getInstance().font
        val title = "Bilibili 扫码登录"
        g.drawText(font, title, width / 2 - font.width(title) / 2, 24, colorWhite, true)

        drawQrCode(g, font)

        val status = BilibiliLoginManager.status
        if (status.isNotEmpty()) {
            g.drawText(font, status, width / 2 - font.width(status) / 2, 264, colorLightGray, true)
        }

        // Hint + cookie fallback at the bottom.
        val hint = "使用 Bilibili App 扫描二维码登录"
        g.drawText(font, hint, width / 2 - font.width(hint) / 2, 286, colorGray, true)

        val cookieHint = "已有 SESSDATA？直接输入 /display login bilibili <sessdata>"
        g.drawText(font, cookieHint, width / 2 - font.width(cookieHint) / 2, 302, colorGray, true)

        drawChildren(g, mouseX, mouseY, partialTick)
    }

    private fun drawQrCode(g: GuiGraphicsCompat, font: net.minecraft.client.gui.Font) {
        val matrix = qrMatrix
        val qrWidth = qrSize * qrScale
        val x0 = width / 2 - qrWidth / 2
        val y0 = 64

        // Refresh button above the QR code.
        refreshRect = UiRect(x0, y0 - 28, qrWidth, 20)
        g.fill(refreshRect.x, refreshRect.y, refreshRect.right, refreshRect.bottom, colorBlue)
        g.drawText(font, "刷新二维码", refreshRect.centerX - font.width("刷新二维码") / 2, refreshRect.y + 6, colorWhite, false)

        if (matrix != null) {
            // White card behind the QR code.
            g.fill(x0 - 6, y0 - 6, x0 + qrWidth + 6, y0 + qrWidth + 6, colorWhite)
            for (x in 0 until qrSize) {
                for (y in 0 until qrSize) {
                    if (matrix.get(x, y)) {
                        g.fill(x0 + x * qrScale, y0 + y * qrScale, x0 + (x + 1) * qrScale, y0 + (y + 1) * qrScale, colorBlack)
                    }
                }
            }
        } else {
            val msg = "加载二维码中..."
            g.drawText(font, msg, width / 2 - font.width(msg) / 2, y0 + qrWidth / 2 - 8, colorWhite, true)
        }
    }

    private fun handleClick(x: Int, y: Int): Boolean {
        if (refreshRect.contains(x, y)) {
            BilibiliLoginManager.startQrLogin()
            refreshQrMatrix()
            return true
        }
        return false
    }

    //? if >=1.21.11 {
    override fun onMouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (handleClick(event.x().toInt(), event.y().toInt())) return true
        return super.onMouseClicked(event, doubleClick)
    }
    //?} else
    /*override fun onMouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (handleClick(mouseX.toInt(), mouseY.toInt())) return true
        return super.onMouseClicked(mouseX, mouseY, button)
    }*/
}