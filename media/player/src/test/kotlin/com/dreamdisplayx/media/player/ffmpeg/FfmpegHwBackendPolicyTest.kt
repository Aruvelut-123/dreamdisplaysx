package com.dreamdisplayx.media.player.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the hw-decode chain: what the candidates are, that falling back actually reaches
 * the second step, and that an explicit override wins over the chain.
 *
 * These cases exist because the chain is invisible at runtime until a device actually
 * fails to open MediaCodec. The libvlc equivalent could only be verified by reading logs;
 * here the whole decision is pure, so the "does it really step down to software?" question
 * is answered by a test instead of by a device.
 */
class FfmpegHwBackendPolicyTest {

    private fun select(
        codec: FfmpegCodec = FfmpegCodec.H264,
        platform: FfmpegHwBackendPolicy.Platform = FfmpegHwBackendPolicy.Platform.ANDROID,
        step: Int = 0,
        useHwAccel: Boolean = true,
        noHardwareAccel: Boolean = false,
        override: String? = null,
    ) = FfmpegHwBackendPolicy.select(codec, platform, step, useHwAccel, noHardwareAccel, override)

    @Test
    fun androidChainStartsWithMediacodecAndEndsWithSoftware() {
        val sel = assertNotNull(select())
        assertEquals(listOf("h264_mediacodec", "h264"), sel.chain.map { it.decoderName })
        assertEquals("h264_mediacodec", sel.decoderName)
        assertTrue(sel.hardware)
    }

    @Test
    fun desktopH264ChainHasHardwareThenSoftware() {
        // The desktop chain lists this platform's hardware decoders in priority order,
        // ending with the software fallback. Exact contents depend on the FFmpeg build;
        // here we verify: (1) there's at least one HW entry for H.264 on desktop, and
        // (2) the last entry is always the software decoder so falling back never fails.
        val sel = assertNotNull(select(platform = FfmpegHwBackendPolicy.Platform.WINDOWS))
        assertTrue(sel.chain.any { it.hardware }, "desktop H264 chain must contain a hardware backend")
        assertEquals(
            FfmpegCodec.H264.softwareDecoder,
            sel.chain.last().decoderName,
            "software decoder must be last in the chain",
        )
        assertTrue(sel.chain.last().kind == FfmpegHwBackendKind.SOFTWARE)
    }

    @Test
    fun eachDesktopPlatformListsOnlyItsOwnDecoders() {
        // A name that is not registered in a platform's FFmpeg build costs a pointless
        // failed open on every start, so the chain must be per-platform rather than the
        // union of everything.
        val win = FfmpegHwBackendPolicy.chain(FfmpegCodec.H264, FfmpegHwBackendPolicy.Platform.WINDOWS)
            .map { it.decoderName }
        assertTrue("h264_d3d11va" in win, "Windows must offer D3D11VA (the AMD path): $win")
        assertTrue("h264_mf" in win, "Windows must offer Media Foundation: $win")
        assertTrue("h264_videotoolbox" !in win, "Windows has no VideoToolbox: $win")

        val mac = FfmpegHwBackendPolicy.chain(FfmpegCodec.H264, FfmpegHwBackendPolicy.Platform.MACOS)
            .map { it.decoderName }
        assertEquals(listOf("h264_videotoolbox", "h264"), mac)

        val linux = FfmpegHwBackendPolicy.chain(FfmpegCodec.HEVC, FfmpegHwBackendPolicy.Platform.LINUX)
            .map { it.decoderName }
        assertTrue("hevc_vaapi" in linux, "Linux must offer VA-API: $linux")
        assertTrue("hevc_d3d11va" !in linux, "Linux has no D3D11VA: $linux")
        assertEquals("hevc", linux.last())
    }

    @Test
    fun desktopHardwareRungsAreNotMediacodec() {
        // The regression this whole kind field exists for: a desktop hardware rung is
        // hardware=true but must NOT be configured with AV_PIX_FMT_MEDIACODEC, or
        // avcodec_open2 fails and the pipeline silently ends up on software forever.
        FfmpegHwBackendPolicy.Platform.entries
            .filter { it != FfmpegHwBackendPolicy.Platform.ANDROID }
            .forEach { platform ->
                FfmpegHwBackendPolicy.chain(FfmpegCodec.H264, platform)
                    .filter { it.hardware }
                    .forEach { backend ->
                        assertEquals(
                            FfmpegHwBackendKind.DESKTOP,
                            backend.kind,
                            "${backend.decoderName} on $platform must be DESKTOP, not MEDIACODEC",
                        )
                    }
            }
    }

    @Test
    fun androidHardwareRungIsMediacodec() {
        val chain = FfmpegHwBackendPolicy.chain(FfmpegCodec.H264, FfmpegHwBackendPolicy.Platform.ANDROID)
        assertEquals(FfmpegHwBackendKind.MEDIACODEC, chain.first().kind)
        assertTrue(chain.first().hardware)
    }

    @Test
    fun desktopAdvancingThroughMultipleHardwareStepsReachesSoftware() {
        val first = assertNotNull(select(platform = FfmpegHwBackendPolicy.Platform.WINDOWS))
        assertTrue(first.hardware, "first step should be hardware on desktop")
        var current = first
        while (current.hasNext) {
            current = assertNotNull(FfmpegHwBackendPolicy.advance(current))
        }
        assertTrue(current.isSoftware, "advancing from any hardware step must eventually reach software")
    }

    @Test
    fun advancingFromHardwareReachesSoftware() {
        val first = assertNotNull(select())
        val second = assertNotNull(FfmpegHwBackendPolicy.advance(first))
        assertEquals("h264", second.decoderName)
        assertTrue(second.isSoftware)
        assertEquals(1, second.step)
    }

    @Test
    fun advancingPastSoftwareStops() {
        val first = assertNotNull(select())
        val second = assertNotNull(FfmpegHwBackendPolicy.advance(first))
        assertNull(FfmpegHwBackendPolicy.advance(second))
        assertFalse(second.hasNext)
    }

    @Test
    fun hardwareDisabledLandsOnSoftwareNotOnNull() {
        // Returning null here would make the caller abandon the pipeline entirely even
        // though software decode is perfectly available.
        val sel = assertNotNull(select(useHwAccel = false))
        assertEquals("h264", sel.decoderName)
        assertTrue(sel.isSoftware)
    }

    @Test
    fun noHardwareAccelDiagnosticAlsoForcesSoftware() {
        val sel = assertNotNull(select(noHardwareAccel = true))
        assertEquals("h264", sel.decoderName)
    }

    @Test
    fun emptyOverrideMeansExplicitSoftware() {
        // `-Ddreamdisplayx.hwDecode=` is the control experiment for "turn hw off entirely";
        // treating it as "not set" would silently re-enable the chain. It must resolve to
        // the codec's software decoder: passing "" to avcodec_find_decoder_by_name would
        // turn "force software" into "force failure".
        val sel = assertNotNull(select(override = ""))
        assertEquals("h264", sel.decoderName)
        assertTrue(sel.isSoftware)
        assertEquals(-1, sel.step)
    }

    @Test
    fun forcedSoftwareDecoderIsReportedAsSoftware() {
        // Naming the software decoder explicitly must not be mistaken for hardware just
        // because it differs from the mediacodec wrapper.
        val sel = assertNotNull(select(override = "h264"))
        assertEquals("h264", sel.decoderName)
        assertTrue(sel.isSoftware)
        assertFalse(sel.hardware)
    }

    @Test
    fun nonEmptyOverrideWinsOverChain() {
        val sel = assertNotNull(select(override = "h264_mediacodec"))
        assertEquals("h264_mediacodec", sel.decoderName)
        assertTrue(sel.hardware)
        assertEquals(-1, sel.step)
        // A forced backend must not be silently replaced when it fails.
        assertNull(FfmpegHwBackendPolicy.advance(sel))
    }

    @Test
    fun unknownCodecHasNoChain() {
        assertNull(select(codec = FfmpegCodec.UNKNOWN))
        assertTrue(FfmpegHwBackendPolicy.chain(FfmpegCodec.UNKNOWN, FfmpegHwBackendPolicy.Platform.ANDROID).isEmpty())
    }

    @Test
    fun outOfRangeStepClampsToLastCandidate() {
        // A stale step (config reset mid-session) must not throw or return null.
        val sel = assertNotNull(select(step = 99))
        assertEquals("h264", sel.decoderName)
        assertTrue(sel.isSoftware)
    }

    @Test
    fun everyKnownCodecHasMediacodecAndSoftwareNames() {
        FfmpegCodec.entries
            .filter { it != FfmpegCodec.UNKNOWN }
            .forEach { codec ->
                assertTrue(codec.hasMediacodec, "$codec should have a mediacodec wrapper")
                assertTrue(codec.softwareDecoder.isNotBlank(), "$codec should have a software decoder")
            }
    }

    @Test
    fun codecNameLookupAcceptsBareAndWrappedNames() {
        assertEquals(FfmpegCodec.H264, FfmpegCodec.fromName("h264"))
        assertEquals(FfmpegCodec.H264, FfmpegCodec.fromName("h264_mediacodec"))
        assertEquals(FfmpegCodec.H264, FfmpegCodec.fromName("H264"))
        assertEquals(FfmpegCodec.HEVC, FfmpegCodec.fromName("hevc"))
        assertEquals(FfmpegCodec.HEVC, FfmpegCodec.fromName("h265"))
        assertEquals(FfmpegCodec.UNKNOWN, FfmpegCodec.fromName("id=42"))
    }

    @Test
    fun describeRendersTheChainReadably() {
        val chain = FfmpegHwBackendPolicy.chain(FfmpegCodec.HEVC, FfmpegHwBackendPolicy.Platform.ANDROID)
        assertEquals("hevc_mediacodec → hevc", FfmpegHwBackendPolicy.describe(chain))
        assertEquals("(none)", FfmpegHwBackendPolicy.describe(emptyList()))
    }
}