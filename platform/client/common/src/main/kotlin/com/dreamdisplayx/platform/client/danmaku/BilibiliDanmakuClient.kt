package com.dreamdisplayx.platform.client.danmaku

import com.dreamdisplayx.util.asJsonObjectOrNull
import com.dreamdisplayx.util.obj
import com.dreamdisplayx.util.optInt
import com.dreamdisplayx.util.optString
import com.dreamdisplayx.util.json.DreamJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okio.ByteString
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream

/**
 * Bilibili live-room danmaku client.
 *
 * Protocol overview:
 * 1. GET `https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=<roomId>` to obtain
 *    `token` and `ws_url`.
 * 2. Open a WebSocket to that URL (fallback `wss://broadcastlv.chat.bilibili.com/sub`).
 * 3. Send auth packet: zlib-compressed JSON `{"uid":0,"roomid":<roomId>,"token":"...","protover":3,"platform":"web","type":2}`
 * 4. Send heartbeat every 30s (op 2 packet).
 * 5. Receive messages (op 5, protover 3): zlib-compressed JSON array `["cmd", {...}]`.
 *    `DANMAKU_MSG` -> text at `info[1][10]`, color at `info[0][3]`, sender at `info[1][9]`.
 *
 * Only the minimal subset needed for scrolling danmaku is parsed here.
 */
class BilibiliDanmakuClient(
    private val roomId: Long,
    private val onMessage: (text: String, sender: String, color: Int) -> Unit,
    private val onStatusChanged: (String) -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/DanmakuClient")
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    @Volatile
    var isConnected = false
        private set

    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dreamdisplayx-danmaku-heartbeat").apply { isDaemon = true }
    }
    @Volatile
    private var closed = false

    fun connect() {
        onStatusChanged("正在连接弹幕服务器…")
        fetchWsInfo { wsUrl, token ->
            if (closed) return@fetchWsInfo
            val request = Request.Builder().url(wsUrl).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (closed) return
                    isConnected = true
                    onStatusChanged("弹幕已连接")
                    logger.info("Danmaku WS opened for room {}", roomId)
                    sendAuth(token)
                    heartbeatExecutor.scheduleAtFixedRate({ sendHeartbeat() }, 10, 30, TimeUnit.SECONDS)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    // Server sometimes sends plain-text heartbeat acks; nothing to parse.
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    handleBinary(bytes)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    onStatusChanged("弹幕连接关闭: $code")
                    disconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    onStatusChanged("弹幕连接失败: ${t.message}")
                    logger.warn("Danmaku WS failure for room {}: {}", roomId, t.message)
                    disconnect()
                }
            })
        }
    }

    fun disconnect() {
        closed = true
        isConnected = false
        heartbeatExecutor.shutdownNow()
        runCatching { webSocket?.cancel() }
        webSocket = null
    }

    private fun fetchWsInfo(callback: (String, String) -> Unit) {
        val url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=$roomId"
        val body = runCatching {
            val response = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://live.bilibili.com")
                    .build()
            ).execute()
            response.use { it.body?.string().orEmpty() }
        }.getOrNull()
        if (body.isNullOrBlank()) {
            onStatusChanged("无法获取弹幕信息")
            return
        }
        runCatching {
            val root = DreamJson.compact.parseToJsonElement(body).asJsonObjectOrNull() ?: return@runCatching
            val data = root.obj("data") ?: return@runCatching
            val token = data.optString("token").orEmpty()
            var wsUrl = data.optString("ws_url").orEmpty()
            if (!wsUrl.startsWith("ws")) wsUrl = "wss://broadcastlv.chat.bilibili.com/sub"
            callback(wsUrl, token)
        }.onFailure { e ->
            onStatusChanged("弹幕信息解析失败: ${e.message}")
            logger.warn("Danmaku info parse failed for room {}: {}", roomId, e.message)
        }
    }

    private fun sendAuth(token: String) {
        val body = buildString {
            append("{\"uid\":0,\"roomid\":$roomId,\"token\":\"").append(token)
            append("\",\"protover\":3,\"platform\":\"web\",\"type\":2}")
        }
        sendPacket(body.toByteArray(Charsets.UTF_8), protover = 3, op = 7)
    }

    private fun sendHeartbeat() {
        sendPacket(ByteArray(0), protover = 1, op = 2)
    }

    private fun sendPacket(body: ByteArray, protover: Int, op: Int) {
        val totalLen = 16 + body.size
        val header = ByteArray(16)
        writeInt(header, 0, totalLen)
        writeShort(header, 4, 16)
        writeShort(header, 6, protover)
        writeInt(header, 8, op)
        writeInt(header, 12, 1)
        webSocket?.send(ByteString.of(*header, *body))
    }

    private fun handleBinary(bytes: ByteString) {
        var offset = 0
        while (offset + 16 <= bytes.size) {
            val totalLen = readInt(bytes, offset)
            val headerLen = readShort(bytes, offset + 4).toInt()
            val protover = readShort(bytes, offset + 6).toInt()
            val op = readInt(bytes, offset + 8)
            if (totalLen < 16 || offset + totalLen > bytes.size) break
            val body = bytes.substring(offset + headerLen, offset + totalLen)
            when (op) {
                3 -> Unit // heartbeat ack
                5 -> if (protover == 3) parseZlib(body)
                8 -> onStatusChanged("弹幕认证成功")
            }
            offset += totalLen
        }
    }

    private fun parseZlib(body: ByteString) {
        val json = runCatching { inflate(body.toByteArray()).toString(Charsets.UTF_8) }.getOrNull() ?: return
        runCatching { parseDanmakuJson(json) }
            .onFailure { logger.debug("Danmaku parse failed: {}", it.message) }
    }

    private fun parseDanmakuJson(json: String) {
        val arr = DreamJson.compact.parseToJsonElement(json) as? JsonArray ?: return
        if (arr.size < 2) return
        val cmd = (arr[0] as? JsonPrimitive)?.content ?: return
        val cmdObj = arr[1] as? JsonObject ?: return
        if (cmd != "DANMAKU_MSG") return

        val info = cmdObj["info"] as? JsonArray ?: return
        // info[0] = level etc, info[1] = metadata array where text lives at index 10.
        val meta = info.getOrNull(1) as? JsonArray ?: return
        val text = (meta.getOrNull(10) as? JsonPrimitive)?.content?.trim()?.htmlUnescape() ?: return
        if (text.isEmpty() || text.length > 200) return

        val colorRaw = (meta.getOrNull(3) as? JsonPrimitive)?.content?.toIntOrNull() ?: 0xFFFFFF
        val color = if (colorRaw in 0..0xFFFFFF) colorRaw else 0xFFFFFF
        val sender = (meta.getOrNull(9) as? JsonPrimitive)?.content ?: "匿名"
        onMessage(text, sender, color)
    }

    private fun inflate(data: ByteArray): ByteArray = runCatching {
        InflaterInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }.getOrNull() ?: ByteArray(0)

    private fun writeInt(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 24).toByte(); buf[off + 1] = (v ushr 16).toByte()
        buf[off + 2] = (v ushr 8).toByte(); buf[off + 3] = v.toByte()
    }

    private fun writeShort(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 8).toByte(); buf[off + 1] = v.toByte()
    }

    private fun readInt(buf: ByteString, off: Int): Int =
        ((buf[off].toInt() shl 24) or (buf[off + 1].toInt() shl 16) or (buf[off + 2].toInt() shl 8) or buf[off + 3].toInt())

    private fun readShort(buf: ByteString, off: Int): Int =
        ((buf[off].toInt() shl 8) or buf[off + 1].toInt())
}

/**
 * Decodes HTML entities Bilibili embeds in danmaku text (live rooms send raw `&lt;` / `&gt;` /
 * `&amp;` etc. in the JSON payload). Handles the named subset the API actually emits plus numeric
 * `&#NN;` references; anything unknown is left as-is.
 */
internal fun String.htmlUnescape(): String {
    if ('&' !in this) return this
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '&') {
            sb.append(c); i++; continue
        }
        val semi = indexOf(';', i + 1)
        if (semi < 0 || semi - i > 10) {
            sb.append(c); i++; continue
        }
        val token = substring(i + 1, semi)
        val decoded: Char? = when {
            token.startsWith("#") -> {
                val num = token.drop(1)
                val code = if (num.startsWith("x") || num.startsWith("X"))
                    num.drop(1).toIntOrNull(16) else num.toIntOrNull(10)
                code?.takeIf { it in 0..0x10FFFF }?.let { String(Character.toChars(it)).firstOrNull() }
            }
            else -> NAMED_ENTITIES[token]
        }
        if (decoded != null) {
            sb.append(decoded); i = semi + 1
        } else {
            sb.append(c); i++
        }
    }
    return sb.toString()
}

private val NAMED_ENTITIES: Map<String, Char> = mapOf(
    "amp" to '&', "lt" to '<', "gt" to '>', "quot" to '"', "apos" to '\'',
    "nbsp" to '\u00A0', "copy" to '\u00A9', "reg" to '\u00AE', "trade" to '\u2122',
    "hellip" to '\u2026', "mdash" to '\u2014', "ndash" to '\u2013',
    "lsquo" to '\u2018', "rsquo" to '\u2019', "ldquo" to '\u201C', "rdquo" to '\u201D',
    "bull" to '\u2022', "middot" to '\u00B7', "euro" to '\u20AC', "yen" to '\u00A5',
    "pound" to '\u00A3', "times" to '\u00D7', "divide" to '\u00F7',
)