package com.dreamdisplayx.media.player.util

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDecoderPolicyTest {
    /**
     * MediaCodec has to stay first: it is the only Android decoder module that can hand a decoded
     * frame straight to the vmem output the display reads. Pinning bare `avcodec` here regressed every
     * Android display to "audio plays, placeholder forever, game at 2 FPS".
     */
    @Test
    fun defaultsToTheHardwareChainSoFramesReachVmem() {
        assertEquals(
            AndroidDecoderPolicy.HARDWARE_CHAIN,
            AndroidDecoderPolicy.module(useHwAccel = true, noHardwareAccel = false, override = null),
        )
        assertEquals(
            "mediacodec_ndk,mediacodec_jni,any",
            AndroidDecoderPolicy.HARDWARE_CHAIN,
        )
    }

    @Test
    fun disablingHardwareDecodeStillPinsSoftware() {
        assertEquals(
            AndroidDecoderPolicy.SOFTWARE_MODULE,
            AndroidDecoderPolicy.module(useHwAccel = false, noHardwareAccel = false, override = null),
        )
        assertEquals(
            AndroidDecoderPolicy.SOFTWARE_MODULE,
            AndroidDecoderPolicy.module(useHwAccel = true, noHardwareAccel = true, override = null),
        )
    }

    @Test
    fun emptyOverrideMeansSoftwareDecode() {
        assertEquals(
            AndroidDecoderPolicy.SOFTWARE_MODULE,
            AndroidDecoderPolicy.module(useHwAccel = true, noHardwareAccel = false, override = ""),
        )
        assertEquals(
            AndroidDecoderPolicy.SOFTWARE_MODULE,
            AndroidDecoderPolicy.module(useHwAccel = true, noHardwareAccel = false, override = "   "),
        )
    }

    @Test
    fun nonEmptyOverrideReplacesTheWholeChain() {
        assertEquals(
            "mediacodec_ndk",
            AndroidDecoderPolicy.module(useHwAccel = true, noHardwareAccel = false, override = "mediacodec_ndk"),
        )
        // Even with hardware decode globally disabled, an explicit override wins: it is the escape
        // hatch used to bisect a launcher's MediaCodec bridge.
        assertEquals(
            "mediacodec_ndk",
            AndroidDecoderPolicy.module(useHwAccel = false, noHardwareAccel = true, override = "mediacodec_ndk"),
        )
    }
}