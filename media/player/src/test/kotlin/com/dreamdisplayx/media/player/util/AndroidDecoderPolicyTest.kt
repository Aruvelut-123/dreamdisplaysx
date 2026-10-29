package com.dreamdisplayx.media.player.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidDecoderPolicyTest {
    /**
     * Android must not send `--codec` by default.
     *
     * `--codec` is a hard module filter, not a preference list: a vmem video output has no Android
     * Surface, so `mediacodec_ndk` cannot attach to it and libvlc never falls back to `avcodec`.
     * The observed symptom was `hasVout=0`, `videoTracks=2`, `decoded=852x480`, `state=3` and
     * `setupCalls=0`/`lockCalls=0` — the stream decoded, the player played, no vout ever existed.
     */
    @Test
    fun noCodecOptionUnlessExplicitlyOverridden() {
        assertNull(AndroidDecoderPolicy.explicitModule(null))
        assertNull(AndroidDecoderPolicy.explicitModule("   "))
    }

    @Test
    fun emptyOverrideStillMeansSoftwareOnly() {
        assertEquals(AndroidDecoderPolicy.SOFTWARE_MODULE, AndroidDecoderPolicy.explicitModule(""))
        assertEquals("avcodec", AndroidDecoderPolicy.SOFTWARE_MODULE)
    }

    @Test
    fun nonEmptyOverrideIsForwardedTrimmed() {
        assertEquals("mediacodec_ndk", AndroidDecoderPolicy.explicitModule("mediacodec_ndk"))
        assertEquals(
            "mediacodec_ndk,mediacodec_jni,any",
            AndroidDecoderPolicy.explicitModule(" mediacodec_ndk,mediacodec_jni,any "),
        )
    }
}