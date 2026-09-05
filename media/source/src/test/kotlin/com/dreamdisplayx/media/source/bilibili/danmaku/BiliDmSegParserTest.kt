package com.dreamdisplayx.media.source.bilibili.danmaku

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire-format tests for the Bilibili danmaku segment parser: protobuf `seg.so` (primary) and the
 * legacy XML `list.so` fallback. Samples are hand-built so no network or protobuf dependency is needed.
 */
class BiliDmSegParserTest {

    // --- protobuf wire helpers ---

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>()
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.add(b.toByte())
                break
            }
            out.add((b or 0x80).toByte())
        }
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int): ByteArray = varint(((field shl 3) or wire).toLong())

    private fun fieldVarint(field: Int, value: Long): ByteArray = tag(field, 0) + varint(value)

    private fun fieldBytes(field: Int, bytes: ByteArray): ByteArray = tag(field, 2) + varint(bytes.size.toLong()) + bytes

    private fun text(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    /** Builds one `DmSegMobileElem` message from the given fields (nulls are omitted). */
    private fun elem(
        id: Long? = null,
        progress: Long? = null,
        mode: Int? = null,
        fontSize: Int? = null,
        color: Int? = null,
        content: String? = null,
        pool: Int? = null,
        idStr: String? = null,
    ): ByteArray {
        val out = ArrayList<Byte>()
        fun append(bytes: ByteArray) = out.addAll(bytes.toList())
        id?.let { append(fieldVarint(1, it)) }
        progress?.let { append(fieldVarint(2, it)) }
        mode?.let { append(fieldVarint(3, it.toLong())) }
        fontSize?.let { append(fieldVarint(4, it.toLong())) }
        color?.let { append(fieldVarint(5, it.toLong())) }
        content?.let { append(fieldBytes(7, text(it))) }
        pool?.let { append(fieldVarint(11, it.toLong())) }
        idStr?.let { append(fieldBytes(12, text(it))) }
        return out.toByteArray()
    }

    /** Wraps elems in a `DmSegMobileReply` (repeated field 1) plus an unknown field 2 to exercise skipping. */
    private fun reply(vararg elems: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        fun append(bytes: ByteArray) = out.addAll(bytes.toList())
        for (e in elems) append(fieldBytes(1, e))
        // Unknown top-level field 2 (varint) must be skipped, not crash the parse.
        append(fieldVarint(2, 99L))
        return out.toByteArray()
    }

    // --- protobuf tests ---

    @Test
    fun `protobuf segment parses fields and skips unknown fields`() {
        val data = reply(
            elem(
                id = 1001, progress = 2500, mode = 1, fontSize = 25, color = 0xFFFFFF,
                content = "Hello danmaku", pool = 0, idStr = "abc123",
            ),
            elem(id = 1002, progress = 5000, mode = 5, fontSize = 30, color = 0xFF0000, content = "固定顶部"),
        )
        val entries = BiliDmSegParser.parseProtobuf(data)

        assertEquals(2, entries.size)
        val first = entries[0]
        assertEquals(1001L, first.id)
        assertEquals(2500L, first.progressMs)
        assertEquals(1, first.mode)
        assertEquals(25, first.fontSize)
        assertEquals(0xFFFFFF, first.color)
        assertEquals("Hello danmaku", first.content)
        assertEquals("abc123", first.idStr)
        assertTrue(first.rolling())
        assertEquals("abc123", first.key())

        val second = entries[1]
        assertTrue(second.fixedTop())
        assertTrue(!second.rolling())
        assertEquals(5, second.mode)
    }

    @Test
    fun `protobuf filters out blank or unsupported-mode entries`() {
        val data = reply(
            elem(id = 1, progress = 100, mode = 1, content = "ok"),
            elem(id = 2, progress = 200, mode = 0, content = "mode 0 ignored"),
            elem(id = 3, progress = 300, mode = 7, content = "mode 7 ignored"),
            elem(id = 4, progress = 400, mode = 1, content = "   "),
        )
        val entries = BiliDmSegParser.parseProtobuf(data)
        assertEquals(1, entries.size)
        assertEquals(1L, entries[0].id)
    }

    @Test
    fun `empty or malformed protobuf yields empty list`() {
        assertTrue(BiliDmSegParser.parseProtobuf(ByteArray(0)).isEmpty())
        // Garbage bytes: treat as unknown top-level field, skipped.
        assertTrue(BiliDmSegParser.parseProtobuf(byteArrayOf(0x0A, 0x01, 0x00)).isEmpty())
    }

    // --- XML tests ---

    @Test
    fun `xml fallback parses d elements with p attributes`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <i>
              <d p="1.5,1,25,16777215,0,0,0,xyz123">滚动弹幕</d>
              <d p="5.0,5,30,255,0,0,0,top9">固定顶部</d>
              <d p="bad,1">too few params</d>
            </i>
        """.trimIndent()
        val entries = BiliDmSegParser.parseXml(xml.toByteArray(StandardCharsets.UTF_8))

        assertEquals(2, entries.size)
        val rolling = entries[0]
        assertEquals(1500L, rolling.progressMs)
        assertEquals(1, rolling.mode)
        assertEquals(25, rolling.fontSize)
        assertEquals(16777215, rolling.color)
        assertEquals("滚动弹幕", rolling.content)
        assertEquals("xyz123", rolling.idStr)

        val fixed = entries[1]
        assertTrue(fixed.fixedTop())
        assertEquals(5000L, fixed.progressMs)
    }

    @Test
    fun `mode and scale helpers behave like VideoPlayer`() {
        val normal = DanmakuEntry(1, "", 0, 1, 25, 0xFFFFFF, "x", 0)
        assertEquals(1.5f, normal.scale())
        assertEquals(0xFFFFFFFF.toInt(), normal.argb())

        val large = DanmakuEntry(1, "", 0, 1, 45, 0x0000FF, "x", 0)
        assertEquals(1.15f * 1.5f, large.scale())

        val keyless = DanmakuEntry(0, "", 1500, 1, 25, 0xFFFFFF, "hi", 0)
        assertEquals("1500:1:16777215:hi", keyless.key())

        val live = DanmakuEntry.live(1, 25, 0x00FF00, "live!")
        assertTrue(live.rolling())
        assertEquals(-1L, live.progressMs)
    }
}
