package com.dreamdisplays.platform.client.ui.kit

import net.minecraft.client.gui.Font

/**
 * Font-aware text measurement helpers shared by the UI: ellipsis trimming and word wrapping.
 * Pulled out of the individual screens so the logic exists once.
 */
object UiText {
    private const val ELLIPSIS = "..."
    private const val EMOJI_SEPARATOR = " • "

    private val MULTI_SPACE = Regex("\\s{2,}")

    /** Drops emoji the Minecraft font can't draw. */
    fun stripEmoji(s: String): String {
        if (s.none { it.isSurrogate() || isEmoji(it.code) }) return s
        val sb = StringBuilder(s.length)
        var pendingSeparator = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (isEmoji(cp)) {
                pendingSeparator = true
                continue
            }
            if (pendingSeparator && !Character.isWhitespace(cp)) {
                while (sb.isNotEmpty() && sb.last().isWhitespace()) sb.setLength(sb.length - 1)
                if (sb.isNotEmpty()) {
                    val joinsWords = sb.last().isLetterOrDigit() && Character.isLetterOrDigit(cp)
                    sb.append(if (joinsWords) EMOJI_SEPARATOR else " ")
                }
                pendingSeparator = false
            }
            sb.appendCodePoint(cp)
        }
        return sb.toString().trim().replace(MULTI_SPACE, " ")
    }

    /** Returns [s] unchanged if it fits in [maxW] pixels, otherwise trims it and appends an ellipsis. */
    fun trim(font: Font, s: String, maxW: Int): String {
        val text = stripEmoji(s)
        if (font.width(text) <= maxW) return text
        val dotsW = font.width(ELLIPSIS)
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val next = i + Character.charCount(text.codePointAt(i))
            if (font.width(sb.toString() + text.substring(i, next)) + dotsW > maxW) break
            sb.append(text, i, next)
            i = next
        }
        return "$sb$ELLIPSIS"
    }

    /** Word-wraps [s] into at most [maxLines] lines of [maxW] pixels; over-long words are ellipsis-trimmed. */
    fun wrap(font: Font, s: String, maxW: Int, maxLines: Int): List<String> {
        val out = ArrayList<String>()
        val words = stripEmoji(s).split(Regex("\\s+"))
        val cur = StringBuilder()
        for (word in words) {
            val trial = if (cur.isEmpty()) word else "$cur $word"
            if (font.width(trial) <= maxW) {
                cur.setLength(0); cur.append(trial)
            } else {
                if (cur.isNotEmpty()) {
                    out.add(cur.toString())
                    if (out.size == maxLines) break
                    cur.setLength(0)
                }
                if (font.width(word) > maxW) {
                    out.add(trim(font, word, maxW))
                    if (out.size == maxLines) break
                } else {
                    cur.append(word)
                }
            }
        }
        if (cur.isNotEmpty() && out.size < maxLines) out.add(cur.toString())
        if (out.isEmpty()) out.add("")
        return out
    }

    /** Formats [nanos] of playback time as `mm:ss` or `h:mm:ss`. */
    fun formatTime(nanos: Long): String {
        if (nanos <= 0) return "00:00"
        val s = nanos / 1_000_000_000L
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }

    private fun isEmoji(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0xE0000..0xE007F ||
                cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D || cp == 0x20E3
}
