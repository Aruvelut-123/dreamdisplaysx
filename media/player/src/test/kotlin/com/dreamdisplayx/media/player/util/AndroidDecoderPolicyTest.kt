package com.dreamdisplayx.media.player.util

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDecoderPolicyTest {
    @Test
    fun defaultsToSoftwareWhenMediaCodecBridgeIsUnavailable() {
        assertEquals(
            AndroidDecoderPolicy.SOFTWARE_MODULE,
            AndroidDecoderPolicy.module(useHwAccel = true, noHardwareAccel = false, override = null),
        )
        assertEquals(
            AndroidDecoderPolicy.SOFTWARE_MODULE,
            AndroidDecoderPolicy.module(useHwAccel = false, noHardwareAccel = false, override = null),
        )
    }

    @Test
    fun emptyOverrideAlsoPinsSoftwareDecode() {
        assertEquals(
            AndroidDecoderPolicy.SOFTWARE_MODULE,
            AndroidDecoderPolicy.module(useHwAccel = true, noHardwareAccel = false, override = ""),
        )
    }

    @Test
    fun nonEmptyOverrideCanOptIntoLauncherHardwareBridge() {
        assertEquals(
            "mediacodec_ndk",
            AndroidDecoderPolicy.module(useHwAccel = true, noHardwareAccel = false, override = "mediacodec_ndk"),
        )
    }
}
