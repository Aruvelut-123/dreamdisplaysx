package com.dreamdisplayx.media.player.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryPolicyTest {
    @Test
    fun `403 and 404 errors retry with cache invalidation`() {
        val d403 = RetryPolicy(maxRetries = 3)
            .evaluate("libvlc error [state=Stopped] | libvlc log: E: http error: 403 Forbidden", false, false)
        assertNotNull(d403)
        assertTrue(d403.invalidateCache)

        val d404 = RetryPolicy(maxRetries = 3).evaluate("Not Found", false, false)
        assertNotNull(d404)
        assertTrue(d404.invalidateCache)
    }

    @Test
    fun `transient network errors retry without cache invalidation`() {
        val d = RetryPolicy(maxRetries = 3).evaluate("Connection reset by peer", false, false)
        assertNotNull(d)
        assertTrue(!d.invalidateCache)
    }

    @Test
    fun `normal end of a live stream retries with cache invalidation`() {
        val d = RetryPolicy(maxRetries = 3).evaluate("End of stream", normalEos = true, isLive = true)
        assertNotNull(d)
        assertTrue(d.invalidateCache)
    }

    @Test
    fun `normal end of a vod is not a retry`() {
        assertNull(RetryPolicy(maxRetries = 3).evaluate("End of stream", normalEos = true, isLive = false))
    }

    @Test
    fun `generic libvlc errors are unrecoverable`() {
        assertNull(
            RetryPolicy(maxRetries = 3).evaluate(
                "libvlc error [state=Stopped] | libvlc log: E: avcodec error: corrupted frame",
                false,
                false,
            )
        )
    }

    @Test
    fun `retries are capped and resettable`() {
        val p = RetryPolicy(maxRetries = 2)
        assertEquals(1000L, p.nextDelay())
        assertEquals(3000L, p.nextDelay())
        assertTrue(p.exhausted)
        assertNull(p.evaluate("403 Forbidden", false, false))
        p.reset()
        assertEquals(0, p.retries)
        assertNotNull(p.evaluate("403 Forbidden", false, false))
    }
}
