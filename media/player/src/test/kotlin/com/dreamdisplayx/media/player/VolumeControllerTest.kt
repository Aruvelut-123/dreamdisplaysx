package com.dreamdisplayx.media.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the pure-JVM half of the "the display randomly gets quieter" fix: the attenuation curve and
 * the re-assertion hook that restores a mixer libvlc replaced behind our back.
 */
class VolumeControllerTest {

    private class Recorder {
        val applied = mutableListOf<Double>()
        val last: Double get() = applied.last()
    }

    private fun controller(initial: Double, recorder: Recorder) =
        VolumeController(initial) { recorder.applied += it }

    @Test
    fun `attenuation is full next to the display`() {
        assertEquals(1.0, VolumeController.attenuationFor(0.0, 96.0), 1e-9)
    }

    @Test
    fun `attenuation floors at half volume at the audio radius`() {
        // The old curve bottomed out at 25%, which is what made walking around a screen sound like
        // somebody turning the volume knob down.
        assertEquals(0.5, VolumeController.attenuationFor(96.0, 96.0), 1e-9)
        assertEquals(0.5, VolumeController.attenuationFor(500.0, 96.0), 1e-9)
    }

    @Test
    fun `attenuation never increases with distance`() {
        var previous = Double.MAX_VALUE
        for (step in 0..20) {
            val attenuation = VolumeController.attenuationFor(step * 5.0, 96.0)
            assertTrue(attenuation <= previous + 1e-9, "attenuation rose at step $step")
            previous = attenuation
        }
    }

    @Test
    fun `an unbounded radius disables attenuation`() {
        // The popout window passes Double.MAX_VALUE so the overlay plays at the user volume.
        assertEquals(1.0, VolumeController.attenuationFor(1234.0, Double.MAX_VALUE), 1e-9)
        assertEquals(1.0, VolumeController.attenuationFor(1234.0, 0.0), 1e-9)
    }

    @Test
    fun `user volume multiplies the distance attenuation`() {
        val recorder = Recorder()
        val controller = controller(0.5, recorder)
        controller.updateAttenuation(0.0, 96.0)
        assertEquals(0.5, recorder.last, 1e-9)
        controller.updateAttenuation(96.0, 96.0)
        assertEquals(0.25, recorder.last, 1e-9)
    }

    @Test
    fun `forceApply re-sends the last value without changing it`() {
        val recorder = Recorder()
        val controller = controller(0.8, recorder)
        controller.updateAttenuation(48.0, 96.0)
        val settled = recorder.last
        val before = recorder.applied.size
        controller.forceApply()
        assertEquals(before + 1, recorder.applied.size)
        assertEquals(settled, recorder.last, 1e-9)
        assertEquals(settled, controller.currentEffective(), 1e-9)
    }

    @Test
    fun `forceApply after a silent mixer reset restores the effective volume`() {
        // Regression: libvlc rebuilds its OpenSL ES aout on every DASH fragment seek and the fresh
        // integer_mixer starts at its own default, so the session must be able to re-push the value
        // it already decided on.
        val recorder = Recorder()
        val controller = controller(1.0, recorder)
        controller.updateAttenuation(0.0, 96.0)
        recorder.applied.clear() // the aout was replaced and silently forgot everything
        controller.forceApply()
        assertEquals(1.0, recorder.last, 1e-9)
    }

    @Test
    fun `user volume is clamped to the supported range`() {
        val recorder = Recorder()
        val controller = controller(1.0, recorder)
        controller.setUserVolume(5.0f)
        assertEquals(2.0, controller.currentEffective(), 1e-9)
        controller.setUserVolume(-1.0f)
        assertEquals(0.0, controller.currentEffective(), 1e-9)
    }

    @Test
    fun `a material attenuation change pushes exactly one update`() {
        val recorder = Recorder()
        val controller = controller(1.0, recorder)
        controller.updateAttenuation(0.0, 96.0)
        val before = recorder.applied.size
        // Same distance twice: the second call must not spam libvlc with an identical value.
        controller.updateAttenuation(0.0, 96.0)
        assertEquals(before, recorder.applied.size)
    }
}