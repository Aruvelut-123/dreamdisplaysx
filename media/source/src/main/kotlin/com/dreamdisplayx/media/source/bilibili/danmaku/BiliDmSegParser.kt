package com.dreamdisplayx.media.source.bilibili.danmaku

import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Parses Bilibili VOD danmaku payloads — the protobuf `seg.so` segment format (primary) and the
 * legacy XML `list.so` format (fallback) — into [DanmakuEntry]s. Ported from
 * squi2rel/VideoPlayer's `BiliDmSegParser` (independent implementation, same wire format).
 */
object BiliDmSegParser {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/BiliDmSegParser")

    /**
     * Parses a protobuf `DmSegMobileReply` (the `seg.so` segment body): a sequence of repeated
     * `DmSegMobileElem` messages on field 1. Unknown fields are skipped by wire type.
     */
    fun parseProtobuf(data: ByteArray): List<DanmakuEntry> {
        val reader = ProtoReader(data)
        val entries = ArrayList<DanmakuEntry>()
        while (reader.available()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == 1 && wire == 2) {
                parseElem(reader.readBytes())?.let { entries.add(it) }
            } else {
                reader.skip(wire)
            }
        }
        return entries.filter { it.renderable() }
    }

    /**
     * Parses the legacy XML danmaku list (`/x/v1/dm/list.so`): `<d p="progress,mode,fontSize,color,ctime,pool,uid,id">text</d>`.
     * Returns an empty list on any parse failure (the caller falls back to other sources).
     */
    fun parseXml(data: ByteArray): List<DanmakuEntry> = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder()
            .parse(InputSource(StringReader(String(data, StandardCharsets.UTF_8))))
        val nodes = document.getElementsByTagName("d")
        val entries = ArrayList<DanmakuEntry>(nodes.length)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node !is Element) continue
            val params = node.getAttribute("p").split(',')
            if (params.size < 4) continue
            val entry = DanmakuEntry(
                id = 0,
                idStr = params.getOrNull(7).orEmpty(),
                progressMs = params[0].toDoubleOrNull()?.let { (it * 1000.0).toLong().coerceAtLeast(0) } ?: 0L,
                mode = params[1].toIntOrNull() ?: 1,
                fontSize = params[2].toIntOrNull() ?: 25,
                color = params[3].toIntOrNull() ?: 0xFFFFFF,
                content = node.textContent.orEmpty(),
                pool = 0,
            )
            if (entry.renderable()) entries.add(entry)
        }
        entries
    }.onFailure { logger.debug("Failed to parse Bilibili XML danmaku: {}.", it.message) }.getOrDefault(emptyList())

    /** Parses one `DmSegMobileElem` protobuf message. */
    private fun parseElem(data: ByteArray): DanmakuEntry? {
        val reader = ProtoReader(data)
        var id = 0L
        var idStr = ""
        var progress = 0L
        var mode = 1
        var fontSize = 25
        var color = 0xFFFFFF
        var content = ""
        var pool = 0
        while (reader.available()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                1 -> id = reader.readVarint()
                2 -> progress = reader.readVarint()
                3 -> mode = reader.readVarint().toInt()
                4 -> fontSize = reader.readVarint().toInt()
                5 -> color = reader.readVarint().toInt()
                7 -> content = String(reader.readBytes(), StandardCharsets.UTF_8)
                11 -> pool = reader.readVarint().toInt()
                12 -> idStr = String(reader.readBytes(), StandardCharsets.UTF_8)
                else -> reader.skip(wire)
            }
        }
        return DanmakuEntry(id, idStr, progress, mode, fontSize, color, content, pool)
    }

    /** Minimal protobuf wire-format reader (varint / length-delimited / fixed32 / fixed64). */
    private class ProtoReader(source: ByteArray) {
        private val data: ByteArray = source
        private var offset = 0

        fun available(): Boolean = offset < data.size

        fun readTag(): Int = readVarint().toInt()

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (offset < data.size && shift < 64) {
                val value = data[offset++].toInt() and 0xFF
                result = result or ((value and 0x7F).toLong() shl shift)
                if (value and 0x80 == 0) return result
                shift += 7
            }
            return result
        }

        fun readBytes(): ByteArray {
            val length = readVarint().toInt()
            if (length <= 0) return ByteArray(0)
            val end = minOf(data.size, offset + length)
            val result = data.copyOfRange(offset, end)
            offset = end
            return result
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> offset = minOf(data.size, offset + 8)
                2 -> {
                    val length = readVarint().toInt()
                    offset = minOf(data.size, offset + maxOf(0, length))
                }
                5 -> offset = minOf(data.size, offset + 4)
                else -> offset = data.size
            }
        }
    }
}
