package com.dreamdisplayx.media.runtime.security

import kotlin.test.Test
import kotlin.test.assertFalse

class MediaHostGuardTest {
    @Test
    fun localCastPathDoesNotBypassPublicHostValidation() {
        assertFalse(MediaHostGuard.isAllowed("http://127.0.0.1/cast/test/index.m3u8"))
    }
}
