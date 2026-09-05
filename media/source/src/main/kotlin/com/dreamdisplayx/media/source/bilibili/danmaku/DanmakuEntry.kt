package com.dreamdisplayx.media.source.bilibili.danmaku

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * One Bilibili danmaku (comment) message as returned by the segment API, mirroring
 * squi2rel/VideoPlayer's `DanmakuEntry`.
 *
 * [mode] follows Bilibili's convention: 1-3 = scrolling (1 normal, 2 top roll, 3 bottom roll),
 * 4 = fixed bottom, 5 = fixed top, 6 = reverse (left-to-right) roll.
 *
 * @since 1.10.x
 */
@DreamDisplaysXUnstableApi
data class DanmakuEntry(
    /** Numeric message id (VOD segments; 0 for live messages). */
    val id: Long,
    /** String message id (protobuf `idStr`), preferred for dedup when present. */
    val idStr: String,
    /** Timeline position in milliseconds, or -1 for live messages. */
    val progressMs: Long,
    /** Bilibili display mode (1-6). */
    val mode: Int,
    /** Font size in Bilibili units (25 = normal). */
    val fontSize: Int,
    /** 24-bit RGB text color. */
    val color: Int,
    /** The comment text. */
    val content: String,
    /** Pool: 0 = normal, 1 = subtitle pool. */
    val pool: Int,
) {
    /** True when this message has drawable content in a supported mode. */
    fun renderable(): Boolean = content.isNotBlank() && mode in 1..6

    /** True for the scrolling modes (1, 2, 3, 6). */
    fun rolling(): Boolean = mode == 1 || mode == 2 || mode == 3 || mode == 6

    /** True for the reverse (left-to-right) scroll mode (6). */
    fun leftToRight(): Boolean = mode == 6

    /** True for the fixed-top mode (5). */
    fun fixedTop(): Boolean = mode == 5

    /** True for the fixed-bottom mode (4). */
    fun fixedBottom(): Boolean = mode == 4

    /** Opaque ARGB with the message color. */
    fun argb(): Int = 0xFF000000.toInt() or (color and 0x00FFFFFF)

    /** Render scale derived from the Bilibili font size (25 = 1.0). Most comments report 25, and the few
     *  larger sizes are clamped to a narrow band so adjacent lines don't jump between very different
     *  heights (VideoPlayer's wider 0.75-1.8 band reads as erratic sizes on the wall). */
    fun scale(): Float {
        val base = (if (fontSize <= 0) 25 else fontSize) / 25.0f
        return base.coerceIn(0.9f, 1.15f) * 1.5f
    }

    /** Dedup key: the string id when present, else the numeric id, else a content-based fallback. */
    fun key(): String =
        if (idStr.isNotBlank()) idStr
        else if (id > 0) id.toString()
        else "$progressMs:$mode:$color:$content"

    companion object {
        /** Creates a live (real-time) message with [mode], [fontSize], [color], and [content]. */
        fun live(mode: Int, fontSize: Int, color: Int, content: String): DanmakuEntry =
            DanmakuEntry(0, "", -1, mode, fontSize, color, content, 0)
    }
}
