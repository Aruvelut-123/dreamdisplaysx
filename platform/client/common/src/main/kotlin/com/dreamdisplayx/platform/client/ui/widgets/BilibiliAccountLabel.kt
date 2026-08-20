package com.dreamdisplayx.platform.client.ui.widgets

import com.dreamdisplayx.media.source.bilibili.BilibiliApi
import com.dreamdisplayx.platform.client.render.Thumbnails
import com.dreamdisplayx.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplayx.platform.client.ui.drawText
import com.dreamdisplayx.platform.client.ui.kit.UiTheme
import com.dreamdisplayx.util.asJsonObjectOrNull
import com.dreamdisplayx.util.json.DreamJson
import com.dreamdisplayx.util.net.DreamHttpClient
import com.dreamdisplayx.util.obj
import com.dreamdisplayx.util.optInt
import com.dreamdisplayx.util.optString
import kotlinx.serialization.json.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Cached Bilibili account info fetched from the nav API.
 */
data class BilibiliAccountInfo(
    val nickname: String,
    val avatarUrl: String,
    /** 0 = none, 1 = monthly, 2 = annual. */
    val vipType: Int,
    /** 1 = active VIP. */
    val vipStatus: Int,
    val level: Int,
) {
    val isVip: Boolean get() = vipType > 0 && vipStatus == 1
}

/**
 * Fetches (and caches) the logged-in Bilibili user's account info once per 5 minutes.
 * Renders nothing when not logged in.
 */
object BilibiliAccountLabel {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/BilibiliAccount")

    @Volatile
    private var cachedInfo: BilibiliAccountInfo? = null

    @Volatile
    private var lastFetch: Instant? = null

    private val TTL = 5.minutes

    /** Invalidates the cache so the next draw re-fetches. */
    fun invalidate() { cachedInfo = null; lastFetch = null }

    /** Returns the cached account info, or null when not logged in / fetch failed. */
    fun getInfo(): BilibiliAccountInfo? {
        val now = Clock.System.now()
        val cached = cachedInfo
        if (cached != null && lastFetch != null && now - lastFetch!! < TTL) return cached
        if (BilibiliApi.cookie.isBlank()) {
            cachedInfo = null; lastFetch = null; return null
        }
        return try {
            val root = fetchNav()
            val data = root?.obj("data") ?: return null
            val info = BilibiliAccountInfo(
                nickname = data.optString("uname") ?: data.optString("name") ?: "?",
                avatarUrl = data.optString("face") ?: "",
                vipType = data.obj("vip")?.optInt("vipType") ?: 0,
                vipStatus = data.obj("vip")?.optInt("vipStatus") ?: 0,
                level = data.obj("level_info")?.optInt("current_level") ?: 0,
            )
            this.cachedInfo = info
            lastFetch = now
            info
        } catch (e: Exception) {
            logger.warn("Failed to fetch Bilibili account info: ${e.message}.")
            null
        }
    }

    /** Draws the account label at the top-right of the screen. */
    fun draw(g: GuiGraphicsCompat, screenWidth: Int, fontHeight: Int) {
        val info = getInfo() ?: return
        val font = Minecraft.getInstance().font
        val avatarSize = fontHeight + 2
        val name = info.nickname
        val nameWidth = font.width(name)
        val badgeWidth = if (info.isVip) fontHeight else 0
        val gap = 4
        val totalW = avatarSize + gap + nameWidth + (if (badgeWidth > 0) gap + badgeWidth else 0)
        val x = screenWidth - UiTheme.SCREEN_PADDING - totalW
        val y = 6

        // Avatar — draw a placeholder circle while loading
        val avatarUrl = info.avatarUrl
        if (avatarUrl.isNotBlank()) {
            val tex = Thumbnails.get(avatarUrl)
            if (tex != null) {
                //? if >=1.21.11 {
                g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, x, y, 0f, 0f, avatarSize, avatarSize, avatarSize, avatarSize)
                //?} else
                /*g.blit(tex, x, y, 0f, 0f, avatarSize, avatarSize, avatarSize, avatarSize)*/
            } else {
                Thumbnails.request(avatarUrl, avatarUrl)
                g.fill(x, y, x + avatarSize, y + avatarSize, 0xFF888888.toInt())
            }
        }

        // Name
        val nameX = x + avatarSize + gap
        g.drawText(font, Component.literal(name), nameX, y + (avatarSize - fontHeight) / 2, 0xFFFFFFFF.toInt(), true)

        // VIP badge
        if (info.isVip) {
            val badgeX = nameX + nameWidth + gap
            val badgeColor = if (info.vipType >= 2) 0xFFFFD700.toInt() else 0xFFFB7299.toInt()
            g.fill(badgeX, y, badgeX + badgeWidth, y + avatarSize, badgeColor)
            val vipLabel = if (info.vipType >= 2) "大" else "V"
            val labelW = font.width(vipLabel)
            g.drawText(font, Component.literal(vipLabel), badgeX + (badgeWidth - labelW) / 2,
                y + (avatarSize - fontHeight) / 2, 0xFFFFFFFF.toInt(), true)
        }
    }

    private fun fetchNav(): JsonObject? {
        return runCatching {
            val headers = DreamHttpClient.headersOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
                "Accept" to "application/json",
                "Referer" to "https://www.bilibili.com",
                "Cookie" to BilibiliApi.cookie,
            )
            val body = DreamHttpClient.readText(
                "https://api.bilibili.com/x/web-interface/nav",
                DreamHttpClient.RequestOptions(
                    headers = headers,
                    connectTimeoutMs = 8_000,
                    readTimeoutMs = 8_000,
                ),
            )
            DreamJson.compact.parseToJsonElement(body).asJsonObjectOrNull()
        }.onFailure { logger.debug("Bilibili nav fetch failed: ${it.message}.") }.getOrNull()
    }
}