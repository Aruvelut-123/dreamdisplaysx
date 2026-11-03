package com.dreamdisplayx.media.player.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the volume/mute mapping that fixed the "the mute button and a 0 % slider do nothing" report.
 *
 * The reporter's `libvlc.log` had 19 `creating audio output` lines and 0 `destroying audio output`
 * lines, with `integer_mixer` instantiated only on the 6 `reusing audio output` paths. `volume` is
 * per-aout, so every value written onto a freshly built aout evaporated; `mute` is per-player and is
 * re-applied by libvlc to whatever aout is current. Silence therefore has to be expressed through the
 * mute flag, and the mixer percent must NOT be zeroed at the same time — see
 * [LibVlcMutePolicy.mixerPercent].
 */
class LibVlcMutePolicyTest {

    @Test
    fun fullLevel_is_100_percent_and_unmuted() {
        assertEquals(100, LibVlcMutePolicy.mixerPercent(1.0))
        assertEquals(0, LibVlcMutePolicy.muteFlag(1.0))
        assertFalse(LibVlcMutePolicy.isSilent(1.0))
    }

    @Test
    fun half_level_is_50_percent_and_unmuted() {
        assertEquals(50, LibVlcMutePolicy.mixerPercent(0.5))
        assertEquals(0, LibVlcMutePolicy.muteFlag(0.5))
    }

    @Test
    fun boosted_level_reaches_libvlc_ceiling() {
        // 200 % is libvlc's own maximum; a proximity boost may ask for more and must clamp.
        assertEquals(200, LibVlcMutePolicy.mixerPercent(2.0))
        assertEquals(200, LibVlcMutePolicy.mixerPercent(5.0))
        assertEquals(0, LibVlcMutePolicy.muteFlag(2.0))
    }

    @Test
    fun zero_level_is_silence_and_keeps_the_mixer_at_full_scale() {
        assertTrue(LibVlcMutePolicy.isSilent(0.0))
        assertEquals(1, LibVlcMutePolicy.muteFlag(0.0))
        // Deliberately NOT 0: verifyVolume would otherwise read the leftover 0 back after an unmute,
        // treat it as drift and restore it, leaving the display permanently silent.
        assertEquals(LibVlcMutePolicy.MAX_PERCENT, LibVlcMutePolicy.mixerPercent(0.0))
    }

    @Test
    fun one_percent_times_a_half_global_multiplier_is_still_audible() {
        // DisplayScreen.applyEffectiveVolume passes a Float: 0.01 * 0.5 = 0.005. It rounds down to
        // percent 0, so the audible/silent decision must be made on the level, not the percent.
        val level = 0.005
        assertFalse(LibVlcMutePolicy.isSilent(level))
        assertEquals(0, LibVlcMutePolicy.muteFlag(level))
        // The percent floor keeps the mixer off 0; mute alone decides audibility.
        assertEquals(1, LibVlcMutePolicy.mixerPercent(level))
    }

    @Test
    fun exactly_the_silence_epsilon_is_audible() {
        // Boundary: the threshold is exclusive so the smallest representable audible level survives.
        assertTrue(LibVlcMutePolicy.isSilent(0.00009))
        assertFalse(LibVlcMutePolicy.isSilent(1e-4))
        assertEquals(0, LibVlcMutePolicy.muteFlag(1e-4))
    }

    @Test
    fun negative_and_non_finite_levels_are_silence() {
        listOf(-1.0, -1e-9, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach {
            assertTrue(LibVlcMutePolicy.isSilent(it), "expected silence for $it")
            assertEquals(1, LibVlcMutePolicy.muteFlag(it), "expected mute for $it")
            assertEquals(LibVlcMutePolicy.MAX_PERCENT, LibVlcMutePolicy.mixerPercent(it), "expected ceiling for $it")
        }
    }

    @Test
    fun mixer_percent_never_returns_zero_for_an_audible_level() {
        // A 0 write would be indistinguishable from the "volume = 0 means mute" bug, so the audible
        // branch floors at 1 %.
        (1..999).forEach { permille ->
            val level = permille / 1000.0
            val percent = LibVlcMutePolicy.mixerPercent(level)
            assertTrue(percent in 1..LibVlcMutePolicy.MAX_PERCENT, "level=$level gave $percent")
        }
    }
}
