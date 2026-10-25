package com.dreamdisplays.media.source.youtube.binary

import com.dreamdisplays.media.source.youtube.model.Durations
import com.dreamdisplays.media.source.youtube.model.LiveStatus
import com.dreamdisplays.media.source.youtube.model.YtStream
import com.dreamdisplays.util.*
import com.dreamdisplays.util.json.DreamJson
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.*
import java.util.regex.Pattern

/**
 * Pure parsing of `yt-dlp -J` JSON output into [YtStream] descriptors: format filtering, live / seekable
 * detection, and resolution extraction.
 */
internal object YtDlpOutputParser {
    private val RESOLUTION_P_PATTERN: Pattern = Pattern.compile("(\\d{3,4})p")
    private val RESOLUTION_DIGITS_PATTERN: Pattern = Pattern.compile("(\\d{3,4})")

    /** Parses the JSON output of `yt-dlp -J` into a flat list of [YtStream] descriptors. */
    @Throws(IOException::class)
    fun parseFormats(json: String): List<YtStream> {
        val result = ArrayList<YtStream>()
        val root: JsonElement = run { DreamJson.compact.parseToJsonElement(json) }
        val obj = root.asJsonObjectOrNull() ?: throw IOException("Returned unexpected JSON shape.")
        val live = isLive(obj)
        val durationNanos = durationNanos(obj)
        val seekable = !live && durationNanos > 0L
        val title = obj.optString("title")
        val uploaderName = obj.optString("uploader") ?: obj.optString("channel")
        val thumbnailUrl = obj.optString("thumbnail")
        val viewCount = obj.optLong("view_count") ?: obj.optLong("concurrent_view_count")
        val formats = obj["formats"].asJsonArrayOrNull() ?: return result

        for (el in formats) {
            val f = el.asJsonObjectOrNull() ?: continue

            val url = f.optString("url")
            if (url.isNullOrEmpty()) continue

            val protocol = f.optString("protocol")
            if (!isSupportedProtocol(protocol, url)) continue

            val vcodec = f.optString("vcodec")
            val acodec = f.optString("acodec")
            val ext = f.optString("ext")
            val container = f.optString("container")

            val hasVideo = vcodec != null && vcodec != "none"
            val hasAudio = acodec != null && acodec != "none"
            if (!hasVideo && !hasAudio) continue

            val mime = (if (hasVideo) "video/" else "audio/") + (ext ?: "webm")

            val width = f.optInt("width")
            val height = f.optInt("height")

            val resolution: String? = if (hasVideo) {
                if (height != null && height > 0) "${height}p"
                else extractResolution(f.optString("resolution"), f.optString("format_note"), f.optString("format"))
            } else null

            result.add(
                YtStream(
                    url, mime, container, protocol, resolution,
                    width, height,
                    f.optString("language"), f.optString("format_note"), vcodec, acodec,
                    f.optDouble("fps"), f.optDouble("tbr"),
                    hasVideo, hasAudio, live, seekable, durationNanos,
                    title, uploaderName, thumbnailUrl, viewCount,
                )
            )
        }
        return result
    }

    private fun isLive(obj: JsonObject): Boolean {
        return obj.optBoolean("is_live") || LiveStatus.fromWire(obj.optString("live_status")).isLiveLike
    }

    private fun durationNanos(obj: JsonObject): Long {
        val durationSeconds = obj.optDouble("duration") ?: return 0L
        return Durations.secondsToNanos(durationSeconds)
    }

    private fun isSupportedProtocol(protocol: String?, url: String): Boolean {
        if (protocol.isNullOrBlank()) return true
        if (protocol.startsWith("http")) return true
        if ("m3u8" in protocol) return true
        if ("dash" in protocol) return true
        val lowerUrl = url.lowercase(Locale.ENGLISH)
        return ".m3u8" in lowerUrl || ".mpd" in lowerUrl
    }

    private fun extractResolution(vararg candidates: String?): String? {
        for (candidate in candidates) {
            if (candidate.isNullOrBlank()) continue
            var m = RESOLUTION_P_PATTERN.matcher(candidate)
            if (m.find()) return "${m.group(1)}p"
            m = RESOLUTION_DIGITS_PATTERN.matcher(candidate)
            if (m.find()) return "${m.group(1)}p"
        }
        return null
    }
}
