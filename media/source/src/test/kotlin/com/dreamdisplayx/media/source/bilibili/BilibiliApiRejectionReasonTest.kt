package com.dreamdisplayx.media.source.bilibili

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [BilibiliApi.biliRejectionReason], the guard that turns Bilibili's HTTP-200 error bodies
 * into a logged cause.
 *
 * Regression guard for issue #4: a player's `latest.log` showed five identical
 * `Initialization failed: Bilibili video/room could not be reached.` lines with no root cause,
 * because `resolveVod` only read `data` and every failure — a missing video, a WAF rejection, a
 * timeout — collapsed into the same bare `null`.
 */
class BilibiliApiRejectionReasonTest {

    private fun payload(code: Int, message: String? = null): JsonObject = buildJsonObject {
        put("code", code)
        if (message != null) put("message", message)
    }

    @Test
    fun `a successful payload reports no rejection`() {
        assertNull(BilibiliApi.biliRejectionReason(payload(0)))
    }

    @Test
    fun `a payload without a code field reports no rejection`() {
        // Some endpoints (and any non-standard proxy response) omit `code` entirely; that must not
        // be treated as a rejection, or a valid response would be dropped.
        assertNull(BilibiliApi.biliRejectionReason(buildJsonObject { put("data", "x") }))
    }

    @Test
    fun `a missing video code is surfaced with its message`() {
        // Bilibili answers HTTP 200 with this body for a deleted / region-locked / private video.
        assertEquals("code=-404 (啥都木有)", BilibiliApi.biliRejectionReason(payload(-404, "啥都木有")))
    }

    @Test
    fun `a WAF rejection code is surfaced`() {
        // -412 is Bilibili's anti-bot block; previously indistinguishable from a network failure.
        assertEquals("code=-412 (请求被拦截)", BilibiliApi.biliRejectionReason(payload(-412, "请求被拦截")))
    }

    @Test
    fun `a rejection with no usable message still reports the code`() {
        assertEquals("code=-10403 (no message)", BilibiliApi.biliRejectionReason(payload(-10403)))
        assertEquals("code=-412 (no message)", BilibiliApi.biliRejectionReason(payload(-412, "   ")))
    }
}
