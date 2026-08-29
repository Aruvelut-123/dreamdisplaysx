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
import kotlin.math.roundToInt
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
    /** VIP badge image URL from the API (e.g. img_label_uri_hans_static), or null. */
    val vipBadgeUrl: String?,
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

    /** Backoff after a failed fetch: retry no sooner than 30s (was: every draw call). */
    private val FAIL_TTL = 30.seconds

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
        // A failed fetch must still record the attempt time: without a backoff, draw() would retry
        // the network call every frame while the API is unreachable. Retry no sooner than FAIL_TTL.
        if (lastFetch != null && now - lastFetch!! < FAIL_TTL) return cached
        return try {
            val root = fetchNav()
            val data = root?.obj("data") ?: return null
            val info = BilibiliAccountInfo(
                nickname = data.optString("uname") ?: data.optString("name") ?: "?",
                avatarUrl = data.optString("face") ?: "",
                vipType = data.obj("vip")?.optInt("type") ?: 0,
                vipStatus = data.obj("vip")?.optInt("status") ?: 0,
                level = data.obj("level_info")?.optInt("current_level") ?: 0,
                vipBadgeUrl = data.obj("vip")?.obj("label")?.optString("img_label_uri_hans_static"),
            )
            this.cachedInfo = info
            lastFetch = now
            info
        } catch (e: Exception) {
            // Retry later rather than next frame; the account info is cosmetic, not worth a
            // per-frame network hit (and a WARN log) while the nav API is unreachable.
            lastFetch = now
            logger.debug("Failed to fetch Bilibili account info: ${e.message}.")
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
        val gap = 4

        // VIP badge width: use the actual image aspect ratio when loaded, fall back to avatarSize
        val vipBadgeUrl = if (info.isVip) info.vipBadgeUrl else null
        val vipBadgeActualW = if (vipBadgeUrl != null) {
            val dims = Thumbnails.dimensions(vipBadgeUrl)
            if (dims != null) (avatarSize.toFloat() * dims.first / dims.second).roundToInt() else avatarSize
        } else 0
        val badgeWidth = if (info.isVip) vipBadgeActualW else 0
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

        // VIP badge — use the official Bilibili image when available, fall back to coloured text
        if (info.isVip) {
            val badgeX = nameX + nameWidth + gap
            val badgeUrl = info.vipBadgeUrl
            if (badgeUrl != null) {
                val tex = Thumbnails.get(badgeUrl)
                if (tex != null) {
                    // Scale the badge to match the avatar height, preserving its aspect ratio
                    val dims = Thumbnails.dimensions(badgeUrl)
                    val badgeW = if (dims != null) {
                        (avatarSize.toFloat() * dims.first / dims.second).roundToInt()
                    } else avatarSize
                    //? if >=1.21.11 {
                    g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, badgeX, y, 0f, 0f, badgeW, avatarSize, badgeW, avatarSize)
                    //?} else
                    /*g.blit(tex, badgeX, y, 0f, 0f, badgeW, avatarSize, badgeW, avatarSize)*/
                } else {
                    Thumbnails.request(badgeUrl, badgeUrl)
                    // Draw fallback text while loading
                    val badgeColor = if (info.vipType >= 2) 0xFFFFD700.toInt() else 0xFFFB7299.toInt()
                    g.fill(badgeX, y, badgeX + avatarSize, y + avatarSize, badgeColor)
                    val vipLabel = if (info.vipType >= 2) "大" else "V"
                    val labelW = font.width(vipLabel)
                    g.drawText(font, Component.literal(vipLabel), badgeX + (avatarSize - labelW) / 2,
                        y + (avatarSize - fontHeight) / 2, 0xFFFFFFFF.toInt(), true)
                }
            } else {
                val badgeColor = if (info.vipType >= 2) 0xFFFFD700.toInt() else 0xFFFB7299.toInt()
                g.fill(badgeX, y, badgeX + avatarSize, y + avatarSize, badgeColor)
                val vipLabel = if (info.vipType >= 2) "大" else "V"
                val labelW = font.width(vipLabel)
                g.drawText(font, Component.literal(vipLabel), badgeX + (badgeWidth - labelW) / 2,
                    y + (avatarSize - fontHeight) / 2, 0xFFFFFFFF.toInt(), true)
            }
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