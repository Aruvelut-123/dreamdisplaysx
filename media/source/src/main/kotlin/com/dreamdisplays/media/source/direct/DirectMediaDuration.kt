package com.dreamdisplays.media.source.direct

import com.dreamdisplays.util.net.DreamHttpClient
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the duration of a remote media file out of its container header, using a handful of small ranged `GET` requests
 * instead of downloading the whole file.
 */
internal object DirectMediaDuration {

    private val logger = LoggerFactory.getLogger(javaClass)
    private const val MAX_BOX_HOPS = 24
    private const val MATROSKA_HEAD_BYTES = 96 * 1024
    private const val MOOV_HEAD_BYTES = 256
    private const val NANOS_PER_SECOND = 1_000_000_000.0

    /**
     * Returns the duration of [url] in nanoseconds, or null when it cannot be determined cheaply.
     * [extension] picks the parser; [contentLength] is only used to avoid ranging past the end.
     */
    fun probe(url: String, extension: String?, contentLength: Long?): Long? = runCatching {
        when (extension) {
            "mp4", "m4v", "mov", "3gp", "3g2", "mxf" -> isoBmff(url, contentLength)
            "webm", "mkv" -> matroska(url)
            else -> null
        }
    }.onFailure { logger.debug("Duration probe failed for {}: {}", url.take(120), it.message) }.getOrNull()

    private fun isoBmff(url: String, contentLength: Long?): Long? {
        var offset = 0L
        repeat(MAX_BOX_HOPS) {
            if (contentLength != null && offset >= contentLength) return null
            val header = range(url, offset, offset + 15) ?: return null
            if (header.size < 8) return null

            val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            var size = buffer.int.toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.US_ASCII)
            var headerSize = 8L
            if (size == 1L) {
                if (header.size < 16) return null
                size = buffer.long
                headerSize = 16L
            } else if (size == 0L) {
                // "extends to end of file": only the last box may do this, and moov would have been
                // found already if it were earlier, so read it here or give up.
                size = (contentLength ?: return null) - offset
            }
            if (size < headerSize) return null

            if (type == "moov") {
                val moov = range(url, offset + headerSize, offset + headerSize + MOOV_HEAD_BYTES - 1)
                    ?: return null
                return mvhdDuration(moov)
            }
            offset += size
        }
        return null
    }

    private fun mvhdDuration(moov: ByteArray): Long? {
        var cursor = 0
        while (cursor + 8 <= moov.size) {
            val buffer = ByteBuffer.wrap(moov, cursor, moov.size - cursor).order(ByteOrder.BIG_ENDIAN)
            val boxSize = buffer.int.toLong() and 0xFFFFFFFFL
            val type = String(moov, cursor + 4, 4, Charsets.US_ASCII)
            if (type == "mvhd") return readMvhd(moov, cursor + 8)
            // A 0 / 1 extended size inside this small head window means we cannot safely keep walking
            if (boxSize < 8) return null
            cursor += boxSize.toInt()
        }
        return null
    }

    private fun readMvhd(moov: ByteArray, payloadStart: Int): Long? {
        if (payloadStart + 4 > moov.size) return null
        val buffer = ByteBuffer.wrap(moov, payloadStart, moov.size - payloadStart).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt() and 0xFF
        buffer.position(buffer.position() + 3) // Flags

        val timescale: Long
        val duration: Long
        if (version == 1) {
            if (payloadStart + 28 > moov.size) return null
            buffer.position(buffer.position() + 16) // Creation + modification time (8 bytes each)
            timescale = buffer.int.toLong() and 0xFFFFFFFFL
            duration = buffer.long
        } else {
            if (payloadStart + 16 > moov.size) return null
            buffer.position(buffer.position() + 8) // Creation + modification time (4 bytes each)
            timescale = buffer.int.toLong() and 0xFFFFFFFFL
            duration = buffer.int.toLong() and 0xFFFFFFFFL
        }
        if (timescale <= 0L || duration <= 0L) return null
        // 0xFFFFFFFF is the conventional "unknown" duration of a fragmented / still-writing file
        if (version == 0 && duration == 0xFFFFFFFFL) return null
        return (duration.toDouble() / timescale * NANOS_PER_SECOND).toLong()
    }

    private fun matroska(url: String): Long? {
        val head = range(url, 0, MATROSKA_HEAD_BYTES - 1L) ?: return null
        val infoAt = indexOf(head, byteArrayOf(0x15, 0x49.toByte(), 0xA9.toByte(), 0x66)) ?: return null

        var cursor = infoAt + 4
        val infoSize = readVint(head, cursor, stripMarker = true) ?: return null
        cursor += infoSize.width
        val infoEnd = minOf(head.size.toLong(), cursor + infoSize.value).toInt()

        var timecodeScale = 1_000_000L // Matroska's default: values are in milliseconds
        var duration: Double? = null

        while (cursor < infoEnd) {
            val id = readVint(head, cursor, stripMarker = false) ?: return null
            cursor += id.width
            val size = readVint(head, cursor, stripMarker = true) ?: return null
            cursor += size.width
            val payloadEnd = cursor + size.value.toInt()
            if (payloadEnd > head.size || size.value <= 0L) return null

            when (id.value) {
                0x2AD7B1L -> timecodeScale = readUnsigned(head, cursor, size.value.toInt())
                0x4489L -> duration = readFloat(head, cursor, size.value.toInt())
            }
            cursor = payloadEnd
        }

        val seconds = duration?.takeIf { it > 0.0 } ?: return null
        if (timecodeScale <= 0L) return null
        return (seconds * timecodeScale).toLong()
    }

    private data class Vint(val value: Long, val width: Int)

    private fun readVint(data: ByteArray, offset: Int, stripMarker: Boolean): Vint? {
        if (offset >= data.size) return null
        val first = data[offset].toInt() and 0xFF
        if (first == 0) return null
        var width = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            width++
        }
        if (width > 8 || offset + width > data.size) return null
        var value = (if (stripMarker) first and (mask - 1) else first).toLong()
        for (i in 1 until width) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return Vint(value, width)
    }

    private fun readUnsigned(data: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (i in 0 until length) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value
    }

    private fun readFloat(data: ByteArray, offset: Int, length: Int): Double? {
        val buffer = ByteBuffer.wrap(data, offset, length).order(ByteOrder.BIG_ENDIAN)
        return when (length) {
            4 -> buffer.float.toDouble()
            8 -> buffer.double
            else -> null
        }
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int? {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return null
    }

    private fun range(url: String, first: Long, last: Long): ByteArray? = runCatching {
        val window = (last - first + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (window <= 0) return@runCatching null
        val response = DreamHttpClient.executeLimited(
            url,
            maxBytes = window,
            options = DreamHttpClient.RequestOptions(
                headers = DreamHttpClient.headersOf("Range" to "bytes=$first-$last"),
                connectTimeoutMs = 8_000L,
                readTimeoutMs = 8_000L,
            ),
        )
        if (!response.isSuccessful || (response.code != 206 && first > 0L)) return@runCatching null
        response.body.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
