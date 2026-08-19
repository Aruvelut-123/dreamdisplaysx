package com.dreamdisplayx.platform.client.login

import com.dreamdisplayx.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplayx.platform.client.ui.drawText
import com.dreamdisplayx.platform.client.ui.kit.UiRect
import com.dreamdisplayx.platform.client.ui.kit.UiScreenBase
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent
//?}
import net.minecraft.network.chat.Component

/**
 * Client-side Bilibili login screen: QR-code login (scan with the mobile app) or phone number +
 * password. On success the `SESSDATA` is handed to [BilibiliLoginManager], which sends it to the
 * server for encrypted storage and playback use.
 */
class PlatformLoginScreen : UiScreenBase(Component.literal("Bilibili Login")) {
    private var mode = 0 // 0 = QR login, 1 = phone + password
    private var qrMatrix: BitMatrix? = null
    private var usernameBox: EditBox? = null
    private var passwordBox: EditBox? = null
    private var tickCount = 0
    private var switchRect = UiRect(0, 0, 0, 0)
    private var actionRect = UiRect(0, 0, 0, 0)

    /** QR matrix edge length and rendered scale. */
    private val qrSize = 160
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

    override fun minContentSize(): Pair<Int, Int>? = 300 to 280

    override fun init() {
        super.init()
        val font = Minecraft.getInstance().font
        usernameBox = EditBox(font, width / 2 - 90, 92, 180, 20, Component.literal("phone"))
            .also { it.setHint(Component.literal("手机号或邮箱")); it.setMaxLength(64) }
        passwordBox = EditBox(font, width / 2 - 90, 122, 180, 20, Component.literal("password"))
            .also { it.setHint(Component.literal("密码")); it.setMaxLength(128) }
        usernameBox?.let { addRenderableWidget(it) }
        passwordBox?.let { addRenderableWidget(it) }
    }

    override fun tick() {
        super.tick()
        tickCount++
        if (mode == 0 && tickCount % 40 == 0) {
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
        val title = "Bilibili Login"
        g.drawText(font, title, width / 2 - font.width(title) / 2, 24, colorWhite, true)

        if (mode == 0) {
            drawQrLogin(g, font)
        } else {
            drawPasswordLogin(g, font)
        }

        val status = BilibiliLoginManager.status
        if (status.isNotEmpty()) {
            g.drawText(font, status, width / 2 - font.width(status) / 2, 252, colorLightGray, true)
        }

        drawChildren(g, mouseX, mouseY, partialTick)
    }

    private fun drawQrLogin(g: GuiGraphicsCompat, font: net.minecraft.client.gui.Font) {
        val matrix = qrMatrix
        if (matrix != null) {
            val size = qrSize * qrScale
            val x0 = width / 2 - size / 2
            val y0 = 64
            // White card behind the QR code.
            g.fill(x0 - 6, y0 - 6, x0 + size + 6, y0 + size + 6, colorWhite)
            for (x in 0 until qrSize) {
                for (y in 0 until qrSize) {
                    if (matrix.get(x, y)) {
                        g.fill(x0 + x * qrScale, y0 + y * qrScale, x0 + (x + 1) * qrScale, y0 + (y + 1) * qrScale, colorBlack)
                    }
                }
            }
        } else {
            val msg = "加载二维码中..."
            g.drawText(font, msg, width / 2 - font.width(msg) / 2, 130, colorWhite, true)
        }

        // "Scan with Bilibili App" hint + switch button + refresh button.
        val hint = "使用 Bilibili App 扫码登录"
        g.drawText(font, hint, width / 2 - font.width(hint) / 2, 232, colorGray, true)

        switchRect = UiRect(width / 2 - 110, 258, 105, 20)
        g.fill(switchRect.x, switchRect.y, switchRect.right, switchRect.bottom, colorDark)
        g.drawText(font, "手机号登录", switchRect.centerX - font.width("手机号登录") / 2, switchRect.y + 6, colorWhite, false)

        actionRect = UiRect(width / 2 + 5, 258, 105, 20)
        g.fill(actionRect.x, actionRect.y, actionRect.right, actionRect.bottom, colorBlue)
        g.drawText(font, "刷新", actionRect.centerX - font.width("刷新") / 2, actionRect.y + 6, colorWhite, false)
    }

    private fun drawPasswordLogin(g: GuiGraphicsCompat, font: net.minecraft.client.gui.Font) {
        val label = "手机号或邮箱 + 密码登录"
        g.drawText(font, label, width / 2 - font.width(label) / 2, 66, colorGray, true)

        actionRect = UiRect(width / 2 - 90, 150, 180, 20)
        g.fill(actionRect.x, actionRect.y, actionRect.right, actionRect.bottom, colorBlue)
        g.drawText(font, "登录", actionRect.centerX - font.width("登录") / 2, actionRect.y + 6, colorWhite, false)

        switchRect = UiRect(width / 2 - 110, 178, 220, 20)
        g.fill(switchRect.x, switchRect.y, switchRect.right, switchRect.bottom, colorDark)
        g.drawText(font, "切换为扫码登录", switchRect.centerX - font.width("切换为扫码登录") / 2, switchRect.y + 6, colorWhite, false)
    }

    private fun handleClick(x: Int, y: Int): Boolean {
        if (switchRect.contains(x, y)) {
            mode = 1 - mode
            if (mode == 0) {
                BilibiliLoginManager.startQrLogin()
                refreshQrMatrix()
            }
            return true
        }
        if (actionRect.contains(x, y)) {
            if (mode == 0) {
                BilibiliLoginManager.startQrLogin()
                refreshQrMatrix()
            } else {
                BilibiliLoginManager.loginWithPassword(
                    usernameBox?.value.orEmpty(),
                    passwordBox?.value.orEmpty(),
                )
            }
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
