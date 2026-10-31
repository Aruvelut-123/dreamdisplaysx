package com.dreamdisplayx.media.source.bilibili

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [BilibiliApi.isBannedResponse], the detector that separates Bilibili's WAF rejection from
 * an ordinary transport failure.
 *
 * Regression guard for issue #4. A tester's `latest.log` showed `x/web-interface/view` answering
 * `HTTP 412 {"code":-412,"message":"request was banned"}` while WBI-signed search still worked, and
 * ~30 identical retries inside one second — each retry extending the ban and re-rendering a display
 * that could not play. Detecting the ban is what lets [BilibiliApi] park the request instead.
 */
class BilibiliApiWafDetectionTest {

    private fun nested(message: String): Throwable =
        IOException("outer", IOException(message))

    @Test
    fun `the exact 412 line from the tester log is detected`() {
        // Copied verbatim from the reported latest.log.
        val line = "java.io.IOException: HTTP 412 for " +
            "https://api.bilibili.com/x/web-interface/view?bvid=BV1bsxC6KEAo: " +
            "{\"code\":-412,\"message\":\"request was banned\",\"ttl\":1}."
        assertTrue(BilibiliApi.isBannedResponse(IOException(line)))
    }

    @Test
    fun `a bare HTTP 412 status is detected`() {
        assertTrue(BilibiliApi.isBannedResponse(IOException("HTTP 412 for https://api.bilibili.com/x/player/playurl")))
    }

    @Test
    fun `the ban is detected through a wrapped cause chain`() {
        // DreamHttpClient wraps the real failure, so detection must walk the causes.
        assertTrue(BilibiliApi.isBannedResponse(nested("HTTP 412 for https://api.bilibili.com/x/web-interface/view")))
    }

    @Test
    fun `a timeout is not mistaken for a WAF ban`() {
        // Parking requests after a timeout would hide a real network problem behind a bogus cooldown.
        assertFalse(BilibiliApi.isBannedResponse(IOException("java.net.SocketTimeoutException: Read timed out")))
    }

    @Test
    fun `a missing video code is not mistaken for a WAF ban`() {
        // -404 is a deleted/region-locked video; retrying or cooling down would not help either.
        assertFalse(BilibiliApi.isBannedResponse(IOException("{\"code\":-404,\"message\":\"啥都木有\"}")))
    }

    @Test
    fun `an unrelated HTTP error is not a WAF ban`() {
        assertFalse(BilibiliApi.isBannedResponse(IOException("HTTP 500 for https://api.bilibili.com/x/web-interface/view")))
        assertFalse(BilibiliApi.isBannedResponse(IOException("Connection reset")))
    }
}