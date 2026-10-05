package com.dreamdisplayx.media.player.subtitle

/** Dependency-free WebVTT/SRT text reader. Styles are intentionally not interpreted. */
object WebVttParser {
    private val timing = Regex("""^\s*((?:\d{2,}:)?\d{2}:\d{2}[.,]\d{3})\s*-->\s*((?:\d{2,}:)?\d{2}:\d{2}[.,]\d{3})(?:\s+.*)?$""")
    private val tags = Regex("<[^>]*>")
    private val entity = Regex("&(?:amp|lt|gt|quot|apos|nbsp|lrm|rlm|#\\d+|#x[0-9a-fA-F]+);")

    fun parse(content: String): List<SubtitleCue> {
        val blocks = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("\n[\\t ]*\n"))
        return blocks.mapNotNull { block ->
            val lines = block.trim().lines()
            val head = lines.firstOrNull().orEmpty()
            if (head == "NOTE" || head.startsWith("NOTE ") || head.startsWith("NOTE\t") ||
                head == "STYLE" || head == "REGION" || head.startsWith("WEBVTT")) return@mapNotNull null
            val index = lines.indexOfFirst { timing.matches(it) }
            if (index !in 0..1) return@mapNotNull null
            val match = timing.matchEntire(lines[index]) ?: return@mapNotNull null
            val start = timestamp(match.groupValues[1]) ?: return@mapNotNull null
            val end = timestamp(match.groupValues[2]) ?: return@mapNotNull null
            val text = cleanText(lines.drop(index + 1).joinToString("\n")).trim()
            if (end <= start || text.isBlank()) null else SubtitleCue(start, end, text)
        }.sortedWith(compareBy<SubtitleCue> { it.startNanos }.thenBy { it.endNanos })
    }

    /** Checked timestamp arithmetic: hostile hour fields must never wrap the playback clock. */
    internal fun timestamp(value: String): Long? = runCatching {
        val parts = value.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val tail = parts.last().split('.')
        if (tail.size != 2 || tail[1].length != 3) return null
        val seconds = tail[0].toLongOrNull() ?: return null
        val millis = tail[1].toLongOrNull() ?: return null
        if (hours < 0 || minutes !in 0..59 || seconds !in 0..59 || millis !in 0..999) return null
        val totalSeconds = Math.addExact(Math.multiplyExact(hours, 3600L), minutes * 60 + seconds)
        Math.multiplyExact(Math.addExact(Math.multiplyExact(totalSeconds, 1000L), millis), 1_000_000L)
    }.getOrNull()

    private fun cleanText(raw: String): String = entity.replace(raw.replace(tags, "")) { match ->
        when (val value = match.value) {
            "&amp;" -> "&"
            "&lt;" -> "<"
            "&gt;" -> ">"
            "&quot;" -> "\""
            "&apos;" -> "'"
            "&nbsp;" -> " "
            "&lrm;", "&rlm;" -> ""
            else -> {
                val code = if (value.startsWith("&#x")) value.substring(3, value.length - 1).toIntOrNull(16)
                else value.substring(2, value.length - 1).toIntOrNull()
                if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF)
                    String(Character.toChars(code)) else value
            }
        }
    }

    /** Convenience for callers without a cached index; playback uses [SubtitleTimeline] directly. */
    fun cueAt(cues: List<SubtitleCue>, positionNanos: Long): SubtitleCue? =
        SubtitleTimeline(cues).activeAt(positionNanos).firstOrNull()

    fun bilingualCueAt(primary: List<SubtitleCue>, secondary: List<SubtitleCue>, positionNanos: Long): SubtitleCue? {
        val active = (SubtitleTimeline(primary).activeAt(positionNanos) + SubtitleTimeline(secondary).activeAt(positionNanos))
        if (active.isEmpty()) return null
        return SubtitleCue(active.maxOf { it.startNanos }, active.minOf { it.endNanos },
            active.map { it.text }.distinct().joinToString("\n"))
    }
}

/** Prefix-max end times keep overlapping/long cues visible after a shorter cue ends, including seeks. */
class SubtitleTimeline(cues: List<SubtitleCue>) {
    private val cues = cues.distinct().sortedBy { it.startNanos }
    private val maxEnd = LongArray(this.cues.size)
    init {
        this.cues.forEachIndexed { index, cue ->
            maxEnd[index] = maxOf(cue.endNanos, if (index == 0) Long.MIN_VALUE else maxEnd[index - 1])
        }
    }

    fun activeAt(position: Long): List<SubtitleCue> {
        var low = 0
        var high = cues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startNanos <= position) low = mid + 1 else high = mid
        }
        val result = ArrayList<SubtitleCue>()
        var i = low - 1
        while (i >= 0 && maxEnd[i] > position) {
            if (position < cues[i].endNanos) result += cues[i]
            i--
        }
        return result.asReversed()
    }

    fun textAt(position: Long): String? = activeAt(position).map { it.text }.distinct()
        .joinToString("\n").takeIf { it.isNotBlank() }
}
