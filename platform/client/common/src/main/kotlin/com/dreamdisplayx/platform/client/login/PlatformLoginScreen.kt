package com.dreamdisplayx.platform.client.login

import com.dreamdisplayx.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplayx.platform.client.ui.drawText
import com.dreamdisplayx.platform.client.ui.kit.UiScreenBase
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import net.minecraft.client.Minecraft
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent
//?}
import net.minecraft.network.chat.Component
import kotlin.math.min

/**
 * Client-side Bilibili login screen: QR-code login (scan with the mobile app).
 * Auto-scales to fit the window. No refresh button; QR auto-refreshes on expiry.
 * On success the `SESSDATA` is handed to [BilibiliLoginManager], which sends it to the
 * server for encrypted storage and playback use.
 */
class PlatformLoginScreen : UiScreenBase(Component.literal("Bilibili Login")) {
    private var qrMatrix: BitMatrix? = null
    private var tickCount = 0

    /** QR matrix edge length (fixed; rendered scale is computed per frame). */
    private val qrMatrixSize = 200

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
            // Poll the QR login every two seconds; expired QR auto-refreshes in the manager.
            BilibiliLoginManager.pollQr()
            refreshQrMatrix()
        }
    }

    private fun refreshQrMatrix() {
        val content = BilibiliLoginManager.qrContent
        qrMatrix = if (content != null) {
            runCatching { QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, qrMatrixSize, qrMatrixSize) }.getOrNull()
        } else {
            null
        }
    }

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawScreenBackground(g)
        val font = Minecraft.getInstance().font
        val title = "Bilibili 扫码登录"
        g.drawText(font, title, width / 2 - font.width(title) / 2, 24, colorWhite, true)

        // Auto-scale the QR code to fit the window while keeping padding.
        val padding = 24
        val availableW = width - padding * 2
        val availableH = height - 160 // reserve space for status + hints below
        val qrWidth = min(availableW, availableH).coerceAtLeast(120)

        drawQrCode(g, font, qrWidth)

        // Status text at the bottom of the QR code area.
        val qrBottom = 64 + qrWidth + 6
        val status = BilibiliLoginManager.status
        if (status.isNotEmpty()) {
            g.drawText(font, status, width / 2 - font.width(status) / 2, qrBottom + 8, colorLightGray, true)
        }

        // Hint + cookie fallback at the bottom of the screen.
        val hintY = qrBottom + 30
        val hint = "使用 Bilibili App 扫描二维码登录"
        g.drawText(font, hint, width / 2 - font.width(hint) / 2, hintY, colorGray, true)

        val cookieHint = "已有 SESSDATA？直接输入 /display login bilibili <sessdata>"
        g.drawText(font, cookieHint, width / 2 - font.width(cookieHint) / 2, hintY + 16, colorGray, true)

        drawChildren(g, mouseX, mouseY, partialTick)
    }

    private fun drawQrCode(g: GuiGraphicsCompat, font: net.minecraft.client.gui.Font, qrWidth: Int) {
        val matrix = qrMatrix
        val x0 = width / 2 - qrWidth / 2
        val y0 = 64
        val scale = qrWidth / qrMatrixSize.toFloat()

        if (matrix != null) {
            // White card behind the QR code.
            g.fill(x0 - 6, y0 - 6, x0 + qrWidth + 6, y0 + qrWidth + 6, colorWhite)
            for (x in 0 until qrMatrixSize) {
                for (y in 0 until qrMatrixSize) {
                    if (matrix.get(x, y)) {
                        val x1 = x0 + (x * scale).toInt()
                        val y1 = y0 + (y * scale).toInt()
                        val x2 = x0 + ((x + 1) * scale).toInt()
                        val y2 = y0 + ((y + 1) * scale).toInt()
                        g.fill(x1, y1, x2, y2, colorBlack)
                    }
                }
            }
        } else {
            val msg = "加载二维码中..."
            g.drawText(font, msg, width / 2 - font.width(msg) / 2, y0 + qrWidth + 10, colorWhite, true)
        }
    }

    //? if >=1.21.11 {
    override fun onMouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        return super.onMouseClicked(event, doubleClick)
    }
    //?} else
    /*override fun onMouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        return super.onMouseClicked(mouseX, mouseY, button)
    }*/
}